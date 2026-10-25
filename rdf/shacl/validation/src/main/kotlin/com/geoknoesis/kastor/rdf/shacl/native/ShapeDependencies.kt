package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfResource

/**
 * What checking one compiled shape for a focus node reads: the single description of a shape's dependencies, from
 * which both the compile closure ([ShapeClosure]) and the recursion analysis ([ShapeDependencies.recursiveComponents])
 * are derived ([ShapeDependencyResolver.resolve]), so that the two cannot disagree. A construct added to the compiled
 * model needs one entry here; [ShapeReads.targets] holds what only the targets of a shape read, which the compiler
 * compiles but which no conformance check evaluates.
 */
internal class ShapeReads(
    /** Constant shape references, the shape nodes of nested property shapes included. */
    val refs: List<RdfResource>,
    /** The node expressions evaluated: their shapes, and the functions their SPARQL queries call. */
    val expressions: List<NodeExpression> = emptyList(),
    /** The expressions of computed `sh:nodeByExpression` constraints: each output node is a shape to validate against. */
    val computedShapes: List<NodeExpression> = emptyList(),
    /** The nodes of the SPARQL queries run apart from those of [expressions]: `sh:sparql` constraints and validators. */
    val queries: List<RdfResource> = emptyList(),
) {
    companion object {
        /**
         * The reads of checking [ps]: its constraints, logical operands and nested property shapes, and the node
         * expressions of `sh:values`, `sh:defaultValue`, a non-constant `sh:deactivated`, the reifier conditions of
         * its triples and its constraints' ([constraintExpressions]).
         */
        fun of(ps: CompiledPropertyShape): ShapeReads =
            ShapeReads(
                refs = ps.constraints.flatMap { constraintRefs(it) } + ps.logicalParts.flatMap { it.operandRefs() } +
                    ps.nestedPropertyShapes.map { it.shapeNode },
                expressions = listOfNotNull(ps.values, ps.defaultValue, ps.deactivatedWhen) + ps.logicalParts.flatMap { it.deactivatedWhen } +
                    ps.reifierConditions.expressions() + ps.constraints.flatMap { constraintExpressions(it) },
                computedShapes = ps.constraints.mapNotNull { (it.unwrapped() as? PropertyConstraint.NodeByExpressionComputed)?.expr },
                queries = ps.constraints.mapNotNull { queryNode(it) },
            )

        /**
         * The reads of checking [cn]: its `sh:node` / `sh:nodeByExpression` references, logical operands, constraints
         * and property shapes, and the node expressions of a non-constant `sh:deactivated` (evaluated before anything
         * else of the shape), the reifier conditions of its triples, the computed parameters of `sh:closed`,
         * `sh:ignoredProperties` and `sh:uniqueValuesFor`, and its constraints'. Not its targets: see [targets].
         */
        fun of(cn: CompiledNodeShape): ShapeReads =
            ShapeReads(
                refs = cn.nodeRefs + cn.nodeByExpressionRefs + cn.logicalParts.flatMap { it.operandRefs() } +
                    cn.nodeConstraints.flatMap { constraintRefs(it) } + cn.propertyShapes.map { it.shapeNode },
                expressions = listOfNotNull(cn.deactivatedWhen) + cn.logicalParts.flatMap { it.deactivatedWhen } +
                    cn.reifierConditions.expressions() +
                    listOfNotNull(cn.closedParameter, cn.ignoredParameter, cn.uniqueValuesForParameter).map { it.expr } +
                    cn.nodeConstraints.flatMap { constraintExpressions(it) },
                computedShapes = cn.nodeConstraints.mapNotNull { (it.unwrapped() as? PropertyConstraint.NodeByExpressionComputed)?.expr },
                queries = cn.nodeConstraints.mapNotNull { queryNode(it) },
            )

        /**
         * What the targets of [cn] read when they are computed: the `sh:targetWhere` shapes and the node expressions
         * at `sh:targetNode`. The compiler compiles them; no conformance check of [cn] evaluates them, so they are no
         * edges of the recursion analysis.
         */
        fun targets(cn: CompiledNodeShape): ShapeReads = ShapeReads(refs = cn.targetWhereRefs, expressions = cn.targets.targetNodeExpressions)

        /** The constant shapes [c] validates against. */
        internal fun constraintRefs(c: PropertyConstraint): List<RdfResource> =
            when (c) {
                is PropertyConstraint.Deactivatable -> constraintRefs(c.constraint)
                is PropertyConstraint.Node -> listOf(c.nestedShape)
                is PropertyConstraint.NodeByExpression -> listOf(c.nestedShape)
                is PropertyConstraint.Qualified -> listOf(c.shape) + c.siblings
                is PropertyConstraint.MemberShape -> listOf(c.nestedShape)
                is PropertyConstraint.SomeValue -> listOf(c.nestedShape)
                is PropertyConstraint.Shape -> listOf(c.nestedShape)
                is PropertyConstraint.ReifierShape -> listOf(c.nestedShape)
                // The constant shapes of a multi-parameter component (SHACL 1.2 Node Expressions §8).
                is PropertyConstraint.Dynamic -> listOfNotNull(c.extras.qualifiedShape, c.extras.reifierShape) + c.extras.siblings
                else -> emptyList()
            }

        /**
         * The node expressions [c] evaluates: `sh:expression`, a computed `sh:nodeByExpression`, the computed parameters
         * of a [PropertyConstraint.Dynamic] and of a [PropertyConstraint.SparqlComponent] (SHACL 1.2 Node Expressions
         * §8), the non-constant `sh:deactivated` of a SPARQL-based constraint, and the non-constant `sh:deactivated` of
         * the reifiers of its parameter triples ([PropertyConstraint.Deactivatable]).
         */
        internal fun constraintExpressions(c: PropertyConstraint): List<NodeExpression> =
            when (c) {
                is PropertyConstraint.Expression -> listOf(c.expr)
                is PropertyConstraint.NodeByExpressionComputed -> listOf(c.expr)
                is PropertyConstraint.Dynamic -> c.expressions
                is PropertyConstraint.SparqlComponent -> c.computed.values.map { it.expr }
                is PropertyConstraint.Sparql -> listOfNotNull(c.deactivatedWhen)
                is PropertyConstraint.Deactivatable -> c.deactivatedWhen + constraintExpressions(c.constraint)
                else -> emptyList()
            }

        /** The node of the SPARQL query [c] runs, if any: an `sh:sparql` constraint, or a component's validator. */
        private fun queryNode(c: PropertyConstraint): RdfResource? =
            when (val u = c.unwrapped()) {
                is PropertyConstraint.Sparql -> u.constraintNode
                is PropertyConstraint.SparqlComponent -> u.validator
                else -> null
            }
    }
}

/**
 * The shapes some reads ([ShapeReads]) depend on: [strict] ones, compiled like any referenced shape, and the
 * [candidates] of computed shape references, compiled tolerantly (R8, see [CandidateCompilation.compileCandidates]).
 */
internal class Dependencies(val strict: Set<RdfResource>, val candidates: Set<RdfResource>) {
    val all: Set<RdfResource> get() = strict + candidates
}

/**
 * The shapes a node expression of [index] can validate against, which the compiler must therefore compile: its
 * [NodeExpressions.shapeRefs] ([strict]), and the [candidates] of its computed `shnex:conformsToShape` shapes, which
 * are only known at evaluation time. A computed `sh:nodeByExpression` can produce any shape, IRI or blank node
 * ([nodeByExpressionCandidates]). Candidates are compiled up front, not on demand, so that the compiled graph and its
 * recursion analysis stay complete and immutable, but tolerantly (R8, see [CandidateCompilation.compileCandidates]).
 *
 * A computed shape whose possible outputs are known before evaluation ([staticIris]: constants, chosen by
 * `shnex:if` or combined by the list functions) can only name those of them that are shapes. Any other computed
 * shape (read from the data, a variable, a SPARQL result, a custom function's argument) may be any IRI shape of the
 * graph ([iriCandidates]), or any shape at all ([allCandidates]) for `sh:nodeByExpression`. The scans for those are
 * done once per index and tick the preparation budget.
 */
internal class ExpressionShapes(val index: ShapeGraphIndex) {
    val iriCandidates: Set<RdfResource> by lazy { ShapeDiscovery.shapeCandidates(index, blankNodes = false) }
    val allCandidates: Set<RdfResource> by lazy { ShapeDiscovery.shapeCandidates(index, blankNodes = true) }

    fun strict(expr: NodeExpression): Set<RdfResource> = NodeExpressions.shapeRefs(expr, index)

    fun computes(expr: NodeExpression): Boolean = NodeExpressions.computesShapes(expr, index)

    /** The candidates of the computed `shnex:conformsToShape` shapes of [expr] (a computed shape is an IRI, §4.5.3). */
    fun candidates(expr: NodeExpression): Set<RdfResource> {
        val arguments = NodeExpressions.computedShapeArguments(expr, index)
        if (arguments.isEmpty()) return emptySet()
        val out = LinkedHashSet<RdfResource>()
        for (argument in arguments) {
            val known = staticIris(argument) ?: return iriCandidates
            known.filterTo(out) { it in iriCandidates }
        }
        return out
    }

    /** The shapes the outputs of the computed `sh:nodeByExpression` [expr] can be (IRIs or blank nodes, §7.2). */
    fun nodeByExpressionCandidates(expr: NodeExpression): Set<RdfResource> =
        staticIris(expr)?.filterTo(LinkedHashSet()) { it in allCandidates } ?: allCandidates

    /** [strict] and [candidates]: every shape [expr] can validate against apart from its SPARQL functions'. */
    fun of(expr: NodeExpression): Set<RdfResource> = strict(expr) + candidates(expr)

    /**
     * The IRIs [expr] can output, or null when they are not known before evaluation. Exact for constants and lists,
     * a superset for the functions that select or combine their arguments' outputs; literal-valued functions output
     * no IRI. Depth is bounded by the expression nesting cap of the parser, and each node ticks the budget.
     */
    private fun staticIris(expr: NodeExpression): Set<Iri>? {
        index.tick("shape compilation")
        fun union(parts: List<NodeExpression?>): Set<Iri>? {
            val out = LinkedHashSet<Iri>()
            for (part in parts) out.addAll(staticIris(part ?: continue) ?: return null)
            return out
        }
        return when (expr) {
            is NodeExpression.Constant -> setOfNotNull(expr.term as? Iri)
            NodeExpression.Empty -> emptySet()
            is NodeExpression.ListExpr -> union(expr.members)
            is NodeExpression.If -> union(listOf(expr.then, expr.otherwise))
            is NodeExpression.Concat -> union(expr.members)
            // An intersection outputs nodes of each member: any member whose outputs are known bounds it.
            is NodeExpression.Intersection -> expr.members.firstNotNullOfOrNull { staticIris(it) }
            is NodeExpression.Distinct -> staticIris(expr.e)
            is NodeExpression.Remove -> staticIris(expr.nodes)
            is NodeExpression.FilterShape -> staticIris(expr.nodes)
            is NodeExpression.Limit -> staticIris(expr.nodes)
            is NodeExpression.Offset -> staticIris(expr.nodes)
            is NodeExpression.OrderBy -> staticIris(expr.nodes)
            is NodeExpression.Min -> staticIris(expr.e)
            is NodeExpression.Max -> staticIris(expr.e)
            is NodeExpression.FindFirst -> expr.nodes?.let { staticIris(it) }
            // The outputs of the mapped expression, whatever focus node each evaluation has.
            is NodeExpression.FlatMap -> staticIris(expr.e)
            is NodeExpression.MatchAll, is NodeExpression.Exists, is NodeExpression.Count, is NodeExpression.Sum,
            is NodeExpression.ConformsToShape -> emptySet()
            else -> null
        }
    }
}

/**
 * Resolves [ShapeReads] into the [Dependencies] of a shape over the compiled [functions]: the shapes of its
 * references and node expressions ([ExpressionShapes]), and those of the node expression bodies of the SHACL SPARQL
 * functions its queries may call. A query is the `sh:sparql` or validator query of a constraint, or a SPARQL node
 * expression anywhere in its expressions. The functions a query may call are found lexically per query node
 * ([ShapeGraphIndex.sparqlCallsByNode], an over-approximation), then transitively through the calls of the bodies
 * (a SPARQL body's query, the SPARQL expressions of a node expression body). A query whose calls are not known (a
 * called relative IRI without an absolute `BASE`, a query node without a scanned query) may call every function:
 * fail-closed.
 */
internal class ShapeDependencyResolver(
    val exprShapes: ExpressionShapes,
    private val functions: SparqlFunctionDeclarationSet,
    private val budget: ValidationBudget,
) {
    private val index get() = exprShapes.index
    private val byIri: Map<String, SparqlFunctionDeclarationSet.Declaration> = functions.declarations.associateBy { it.iri.value }

    /** Per function, the calls of its body's queries. */
    private val bodyCalls = HashMap<String, List<ShapeGraphIndex.SparqlFunctionCalls>>()

    /** Per function with a node expression body, the shapes of its body. */
    private val bodyDependencies = HashMap<String, Dependencies>()

    /** The dependencies of [reads]. */
    fun resolve(reads: ShapeReads): Dependencies {
        val strict = LinkedHashSet<RdfResource>(reads.refs)
        val candidates = LinkedHashSet<RdfResource>()
        val calls = ArrayList<ShapeGraphIndex.SparqlFunctionCalls>()
        for (query in reads.queries) calls.add(index.callsOf(query))
        for (expr in reads.expressions) {
            budget.tick("shape compilation")
            strict.addAll(exprShapes.strict(expr))
            candidates.addAll(exprShapes.candidates(expr))
            NodeExpressions.selects(expr, index).forEach { calls.add(index.callsOf(it.node)) }
        }
        for (expr in reads.computedShapes) candidates.addAll(exprShapes.nodeByExpressionCandidates(expr))
        for (function in calledFunctions(calls)) {
            val body = bodyDependencies(function) ?: continue
            strict.addAll(body.strict)
            candidates.addAll(body.candidates)
        }
        candidates.removeAll(strict)
        return Dependencies(strict, candidates)
    }

    /** The dependencies of one node expression ([ShapeReads.expressions] of its own). */
    fun ofExpression(expr: NodeExpression): Dependencies = resolve(ShapeReads(refs = emptyList(), expressions = listOf(expr)))

    /** The declared functions reachable from [calls] through the calls of their bodies. */
    private fun calledFunctions(calls: List<ShapeGraphIndex.SparqlFunctionCalls>): Collection<SparqlFunctionDeclarationSet.Declaration> {
        if (byIri.isEmpty() || calls.isEmpty()) return emptyList()
        val reached = LinkedHashMap<String, SparqlFunctionDeclarationSet.Declaration>()
        val pending = ArrayDeque(calls)
        while (pending.isNotEmpty()) {
            budget.tick("shape compilation")
            val call = pending.removeFirst()
            if (call.any) return functions.declarations
            for (iri in call.iris) {
                val declaration = byIri[iri] ?: continue
                if (reached.put(iri, declaration) == null) pending.addAll(bodyCalls(declaration))
            }
        }
        return reached.values
    }

    private fun bodyCalls(declaration: SparqlFunctionDeclarationSet.Declaration): List<ShapeGraphIndex.SparqlFunctionCalls> =
        bodyCalls.getOrPut(declaration.iri.value) {
            when (val body = declaration.body) {
                is NodeExpression.Select -> listOf(index.callsOf(body.node))
                else -> NodeExpressions.selects(body, index).map { index.callsOf(it.node) }
            }
        }

    private fun bodyDependencies(declaration: SparqlFunctionDeclarationSet.Declaration): Dependencies? {
        val body = declaration.body
        if (body is NodeExpression.Select) return null
        return bodyDependencies.getOrPut(declaration.iri.value) {
            Dependencies(exprShapes.strict(body), exprShapes.candidates(body))
        }
    }
}

/** The recursion analysis of a compiled shape graph. */
internal object ShapeDependencies {
    /**
     * The shapes of [refNodes] / [refProps] that belong to a recursive strongly connected component of the static
     * shape dependency graph, mapped to their component id. The successors of a shape are its [Dependencies], strict
     * and candidates ([ShapeDependencyResolver.resolve] of [ShapeReads.of]), the very shapes [ShapeClosure] compiles
     * for it. A shape node that is also a property shape is always checked as a property shape (see conformance
     * checks), so only its property shape's reads count.
     */
    internal fun recursiveComponents(
        refNodes: Map<RdfResource, CompiledNodeShape>,
        refProps: Map<RdfResource, CompiledPropertyShape>,
        resolver: ShapeDependencyResolver,
        budget: ValidationBudget,
    ): Map<RdfResource, Int> {
        val successors = dependencyGraph(refNodes, refProps, resolver)
        val components = stronglyConnectedComponents(successors.keys.toList(), budget) { v -> successors[v].orEmpty().filter { it in successors } }
        val out = HashMap<RdfResource, Int>()
        var id = 0
        for (component in components) {
            if (component.size > 1 || successors[component[0]].orEmpty().contains(component[0])) {
                component.forEach { out[it] = id }
                id++
            }
        }
        return out
    }

    /** The static shape dependency graph of [recursiveComponents]: each shape's successors. */
    internal fun dependencyGraph(
        refNodes: Map<RdfResource, CompiledNodeShape>,
        refProps: Map<RdfResource, CompiledPropertyShape>,
        resolver: ShapeDependencyResolver,
    ): Map<RdfResource, Set<RdfResource>> {
        val successors = HashMap<RdfResource, Set<RdfResource>>()
        for ((node, ps) in refProps) successors[node] = resolver.resolve(ShapeReads.of(ps)).all
        for ((node, cn) in refNodes) if (node !in refProps) successors[node] = resolver.resolve(ShapeReads.of(cn)).all
        return successors
    }
}
