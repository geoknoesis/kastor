package org.geoknoesis.lens

/**
 * Provider-independent, semantics-preserving rewrites for the portable lens algebra.
 *
 * This pass intentionally does not claim to be a cost-based optimizer: it has no
 * provider statistics, cardinality estimates, or physical join-order control.
 * All rewrites operate over typed expressions, never over raw SPARQL fragments.
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
        val normalized = LensNormalizer.normalized(input)
        if (normalized != input) rules.add("flatten-and-deduplicate-set-operations")
        val rewritten = rewrite(normalized, rules)
        val result = LensNormalizer.normalized(rewritten)
        if (result != rewritten) rules.add("flatten-and-deduplicate-set-operations")
        return Plan(result, rules.toList(), count(input), count(result))
    }

    private fun rewrite(input: Lens, rules: MutableSet<String>): Lens = when (input) {
        is Lens.Type -> input
        is Lens.Equals -> input.copy(input = rewrite(input.input, rules))
        is Lens.Exists -> input.copy(
            input = rewrite(input.input, rules),
            target = rewrite(input.target, rules)
        )
        is Lens.Difference -> input.copy(
            left = rewrite(input.left, rules),
            right = rewrite(input.right, rules)
        )
        is Lens.Intersect -> {
            val parts = input.parts.map { rewrite(it, rules) }
            val normalized = LensNormalizer.normalized(Lens.Intersect(parts))
            if (normalized != Lens.Intersect(parts)) {
                rules.add("flatten-and-deduplicate-set-operations")
            }
            normalized
        }
        is Lens.Union -> {
            val parts = input.parts.map { rewrite(it, rules) }
            val normalized = LensNormalizer.normalized(Lens.Union(parts))
            if (normalized != Lens.Union(parts)) {
                rules.add("flatten-and-deduplicate-set-operations")
            }
            normalized
        }
        is Lens.Filter -> {
            val condition = predicate(input.condition, rules)
            var source = rewrite(input.input, rules)
            val conditions = mutableListOf(condition)
            while (source is Lens.Filter) {
                conditions.add(source.condition)
                source = source.input
            }
            if (conditions.size > 1) rules.add("fuse-consecutive-node-filters")
            Lens.Filter(source, predicate(BooleanPredicate.All(conditions.asReversed()), rules))
        }
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
                if (distinct.size != input.clauses.size || rewritten != input.clauses) {
                    rules.add("flatten-and-deduplicate-boolean-conditions")
                }
                if (distinct.size == 1) distinct.single() else BooleanPredicate.All(distinct)
            }
            is BooleanPredicate.Any -> {
                require(input.clauses.isNotEmpty()) { "Empty disjunction needs an explicit false predicate" }
                val rewritten = input.clauses.map { predicate(it, rules) }
                val flattened = rewritten.flatMap { if (it is BooleanPredicate.Any) it.clauses else listOf(it) }
                val distinct = flattened.distinct()
                if (distinct.size != input.clauses.size || rewritten != input.clauses) {
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
