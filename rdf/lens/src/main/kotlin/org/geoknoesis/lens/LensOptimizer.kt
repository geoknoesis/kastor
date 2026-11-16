package org.geoknoesis.lens

/**
 * Provider-independent, semantics-preserving rewrites for the portable lens algebra.
 *
 * This pass intentionally does not claim to be a cost-based optimizer: it has no
 * provider statistics, cardinality estimates, or physical join-order control.
 * All rewrites operate over typed expressions, never over raw SPARQL fragments.
 *
 * The result is a canonical form: a core (a class selector, a union, or a flat
 * intersection of those) wrapped by the restrictions that test the selected node
 * itself, in the order Equals, Exists, Difference, then at most one Filter. Each
 * restriction only inspects the bound node, so restrictions commute with each
 * other and with intersection. Composed lenses that share a base therefore
 * collapse to one scan of that base.
 *
 * Every input node is visited once, bottom-up.
 */
class LensOptimizer {
    data class Plan(
        val lens: Lens,
        val rules: List<String>,
        val nodesBefore: Int,
        val nodesAfter: Int
    )

    fun optimize(input: Lens): Plan {
        val rules = linkedSetOf<String>()
        val result = rewrite(input, rules)
        return Plan(result, rules.toList(), count(input), count(result))
    }

    /** A test on the selected node; independent of how the node was selected. */
    private sealed interface Restriction {
        data class Eq(val property: String, val value: Value) : Restriction
        data class Ex(val property: String, val target: Lens) : Restriction
        data class Minus(val right: Lens) : Restriction
        data class Cond(val condition: BooleanPredicate) : Restriction
    }

    private class Peeled(val core: Lens, val restrictions: List<Restriction>)

    private fun rewrite(input: Lens, rules: MutableSet<String>): Lens = when (input) {
        is Lens.Type -> input
        is Lens.Intersect -> intersect(input, rules)
        is Lens.Union -> union(input, rules)
        is Lens.Difference -> difference(input, rules)
        is Lens.Equals, is Lens.Exists, is Lens.Filter -> chain(input, rules)
    }

    /** Walks a run of Equals/Exists/Filter wrappers iteratively, then rebuilds it canonically. */
    private fun chain(input: Lens, rules: MutableSet<String>): Lens {
        val outerFirst = ArrayList<Restriction>()
        var current = input
        while (true) {
            current = when (current) {
                is Lens.Equals -> {
                    outerFirst.add(Restriction.Eq(current.property, current.value)); current.input
                }
                is Lens.Exists -> {
                    outerFirst.add(Restriction.Ex(current.property, rewrite(current.target, rules)))
                    current.input
                }
                is Lens.Filter -> {
                    outerFirst.add(Restriction.Cond(current.condition)); current.input
                }
                else -> break
            }
        }
        val inner = peel(rewrite(current, rules))
        return build(inner.core, inner.restrictions + outerFirst.asReversed(), rules)
    }

    private fun difference(input: Lens.Difference, rules: MutableSet<String>): Lens {
        val left = peel(rewrite(input.left, rules))
        return build(left.core, left.restrictions + Restriction.Minus(rewrite(input.right, rules)), rules)
    }

    /**
     * `L minus R`, where R selects from the same core under restrictions L already
     * satisfies plus extra filter clauses, equals L filtered by the negation of
     * those clauses. Only valid when the extra clauses cannot raise a SPARQL
     * error: `!(error)` drops the node whereas NOT EXISTS would keep it.
     * Returns null when the rewrite does not apply.
     */
    private fun negatedRemainder(
        core: Lens, others: Set<Restriction>, held: Set<BooleanPredicate>, minus: Restriction.Minus
    ): BooleanPredicate? {
        val right = peel(minus.right)
        if (right.core != core) return null
        val rightClauses = ArrayList<BooleanPredicate>()
        for (r in right.restrictions) {
            if (r is Restriction.Cond) rightClauses.addAll(clauses(r.condition))
            else if (r == minus || r !in others) return null
        }
        val extra = rightClauses.filter { it !in held }
        if (extra.isEmpty() || !extra.all { errorFree(it) }) return null
        return BooleanPredicate.Not(if (extra.size == 1) extra.single() else BooleanPredicate.All(extra))
    }

    private fun clauses(condition: BooleanPredicate): List<BooleanPredicate> =
        if (condition is BooleanPredicate.All) condition.clauses else listOf(condition)

    /** A comparison with a path operand compiles to EXISTS, which never raises an error. */
    private fun errorFree(p: BooleanPredicate): Boolean = when (p) {
        is BooleanPredicate.Compare ->
            p.left !is ValueExpression.Constant || p.right !is ValueExpression.Constant
        is BooleanPredicate.Not -> errorFree(p.inner)
        is BooleanPredicate.All -> p.clauses.all { errorFree(it) }
        is BooleanPredicate.Any -> p.clauses.all { errorFree(it) }
    }

    private fun intersect(input: Lens.Intersect, rules: MutableSet<String>): Lens {
        require(input.parts.isNotEmpty()) { "Empty intersection requires a universe" }
        val cores = LinkedHashSet<Lens>()
        val restrictions = ArrayList<Restriction>()
        var seen = 0
        var flattened = input.parts.size == 1
        for (part in input.parts) {
            val peeled = peel(rewrite(part, rules))
            if (peeled.restrictions.isNotEmpty() && input.parts.size > 1) {
                rules.add("pull-restrictions-out-of-intersection")
            }
            restrictions.addAll(peeled.restrictions)
            val core = peeled.core
            if (core is Lens.Intersect) {
                flattened = true
                seen += core.parts.size
                cores.addAll(core.parts)
            } else {
                seen++
                cores.add(core)
            }
        }
        if (flattened || cores.size != seen) rules.add("flatten-and-deduplicate-set-operations")
        val base = if (cores.size == 1) cores.single() else Lens.Intersect(cores.toList())
        return build(base, restrictions, rules)
    }

    private fun union(input: Lens.Union, rules: MutableSet<String>): Lens {
        require(input.parts.isNotEmpty()) { "Empty union is undefined" }
        val parts = LinkedHashSet<Lens>()
        var seen = 0
        var flattened = input.parts.size == 1
        for (part in input.parts) {
            val rewritten = rewrite(part, rules)
            if (rewritten is Lens.Union) {
                flattened = true
                seen += rewritten.parts.size
                parts.addAll(rewritten.parts)
            } else {
                seen++
                parts.add(rewritten)
            }
        }
        if (flattened || parts.size != seen) rules.add("flatten-and-deduplicate-set-operations")

        // Group operands by the lens under their outermost filter; a null entry
        // marks the unfiltered base itself.
        val groups = LinkedHashMap<Lens, MutableList<BooleanPredicate?>>()
        for (part in parts) {
            if (part is Lens.Filter) groups.getOrPut(part.input) { ArrayList() }.add(part.condition)
            else groups.getOrPut(part) { ArrayList() }.add(null)
        }
        val merged = groups.map { (base, conditions) ->
            when {
                conditions.any { it == null } -> {
                    if (conditions.size > 1) rules.add("absorb-filtered-union-operand")
                    base
                }
                conditions.size == 1 -> Lens.Filter(base, conditions.single()!!)
                else -> {
                    rules.add("merge-union-filters-over-shared-input")
                    Lens.Filter(base, predicate(BooleanPredicate.Any(conditions.map { it!! }), rules))
                }
            }
        }
        return if (merged.size == 1) merged.single() else Lens.Union(merged)
    }

    /** Splits a canonical lens into its core and its restrictions, innermost first. */
    private fun peel(lens: Lens): Peeled {
        val outerFirst = ArrayList<Restriction>()
        var current = lens
        while (true) {
            current = when (current) {
                is Lens.Equals -> {
                    outerFirst.add(Restriction.Eq(current.property, current.value)); current.input
                }
                is Lens.Exists -> {
                    outerFirst.add(Restriction.Ex(current.property, current.target)); current.input
                }
                is Lens.Difference -> {
                    outerFirst.add(Restriction.Minus(current.right)); current.left
                }
                is Lens.Filter -> {
                    outerFirst.add(Restriction.Cond(current.condition)); current.input
                }
                else -> break
            }
        }
        return Peeled(current, outerFirst.asReversed())
    }

    private fun build(core: Lens, restrictions: List<Restriction>, rules: MutableSet<String>): Lens {
        if (restrictions.isEmpty()) return core
        val distinct = LinkedHashSet(restrictions)
        if (distinct.size != restrictions.size) rules.add("deduplicate-node-restrictions")
        val conditions = distinct.filterIsInstance<Restriction.Cond>().map { it.condition }
        if (conditions.size > 1) rules.add("fuse-consecutive-node-filters")
        var fused = when (conditions.size) {
            0 -> null
            1 -> predicate(conditions.single(), rules)
            else -> predicate(BooleanPredicate.All(conditions), rules)
        }

        var result = core
        for (r in distinct) if (r is Restriction.Eq) result = Lens.Equals(result, r.property, r.value)
        for (r in distinct) if (r is Restriction.Ex) result = Lens.Exists(result, r.property, r.target)
        val held = fused?.let { clauses(it).toSet() } ?: emptySet()
        val negations = ArrayList<BooleanPredicate>()
        for (r in distinct) if (r is Restriction.Minus) {
            val negated = negatedRemainder(core, distinct, held, r)
            if (negated == null) result = Lens.Difference(result, r.right) else negations.add(negated)
        }
        if (negations.isNotEmpty()) {
            rules.add("rewrite-difference-as-negated-filter")
            fused = predicate(BooleanPredicate.All(listOfNotNull(fused) + negations), rules)
        }
        return if (fused == null) result else Lens.Filter(result, fused)
    }

    private fun predicate(input: BooleanPredicate, rules: MutableSet<String>): BooleanPredicate =
        when (input) {
            is BooleanPredicate.Compare -> input
            is BooleanPredicate.Not -> {
                val child = predicate(input.inner, rules)
                if (child is BooleanPredicate.Not) {
                    rules.add("eliminate-double-negation")
                    child.inner
                } else BooleanPredicate.Not(child)
            }
            is BooleanPredicate.All -> {
                require(input.clauses.isNotEmpty()) { "Empty conjunction needs an explicit true predicate" }
                val rewritten = input.clauses.map { predicate(it, rules) }
                val flattened = rewritten.flatMap { if (it is BooleanPredicate.All) it.clauses else listOf(it) }
                val distinct = flattened.distinct()
                if (distinct != rewritten || rewritten != input.clauses) {
                    rules.add("flatten-and-deduplicate-boolean-conditions")
                }
                if (distinct.size == 1) distinct.single() else BooleanPredicate.All(distinct)
            }
            is BooleanPredicate.Any -> {
                require(input.clauses.isNotEmpty()) { "Empty disjunction needs an explicit false predicate" }
                val rewritten = input.clauses.map { predicate(it, rules) }
                val flattened = rewritten.flatMap { if (it is BooleanPredicate.Any) it.clauses else listOf(it) }
                val distinct = flattened.distinct()
                if (distinct != rewritten || rewritten != input.clauses) {
                    rules.add("flatten-and-deduplicate-boolean-conditions")
                }
                if (distinct.size == 1) distinct.single() else BooleanPredicate.Any(distinct)
            }
        }

    private fun count(lens: Lens): Int = 1 + when (lens) {
        is Lens.Type -> 0
        is Lens.Equals -> count(lens.input)
        is Lens.Exists -> count(lens.input) + count(lens.target)
        is Lens.Difference -> count(lens.left) + count(lens.right)
        is Lens.Intersect -> lens.parts.sumOf { count(it) }
        is Lens.Union -> lens.parts.sumOf { count(it) }
        is Lens.Filter -> count(lens.input)
    }
}
