package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.UnsupportedFeatureHandling
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.native.ShapeDependencies.constraintExpressions
import com.geoknoesis.kastor.rdf.shacl.native.ShapeDependencies.constraintRefs
import com.geoknoesis.kastor.rdf.shacl.native.ShapeDependencies.shapeExpressions
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler.compileNodeShape
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler.compilePropertyShape
import com.geoknoesis.kastor.rdf.shacl.native.ShapeDiscovery.shapeCandidates

/**
 * The candidates of computed shape references ([ExpressionShapes.iriCandidates], [ExpressionShapes.allCandidates]),
 * queued once each when a compiled shape needs them, for [ShapeDependencies.compileCandidates].
 */
internal class CandidateQueue(private val exprShapes: ExpressionShapes) {
    val queue = ArrayDeque<RdfResource>()
    private val seen = HashSet<RdfResource>()
    private var iris = false
    private var all = false

    /** The IRI candidates: a computed `shnex:conformsToShape` shape (§4.5.3) is an IRI. */
    fun wantIris() {
        if (iris || all) return
        iris = true
        exprShapes.iriCandidates.forEach { if (seen.add(it)) queue.add(it) }
    }

    /** Every candidate: the outputs of a computed `sh:nodeByExpression` (§7.2) are IRIs or blank nodes. */
    fun wantAll() {
        if (all) return
        all = true
        exprShapes.allCandidates.forEach { if (seen.add(it)) queue.add(it) }
    }
}

/**
 * Compiles the shapes added to it and every shape they reference ([constraintRefs], logical operands, nested
 * property shapes, the shapes of node expressions) into [nodes] / [props], skipping the shapes of [baseNodes] /
 * [baseProps]. [check] runs before a shape is compiled here. A node expression that computes its shapes queues
 * the candidates in [candidates] instead of compiling them.
 */
internal class ShapeClosure(
    private val index: ShapeGraphIndex,
    private val config: ValidationConfig,
    private val budget: ValidationBudget,
    private val exprShapes: ExpressionShapes,
    private val candidates: CandidateQueue,
    val nodes: MutableMap<RdfResource, CompiledNodeShape>,
    val props: MutableMap<RdfResource, CompiledPropertyShape>,
    private val baseNodes: Map<RdfResource, CompiledNodeShape> = emptyMap(),
    private val baseProps: Map<RdfResource, CompiledPropertyShape> = emptyMap(),
    private val check: (RdfResource) -> Unit = {},
) {
    private val pending = ArrayDeque<RdfResource>()

    fun add(refs: Collection<RdfResource>) {
        pending.addAll(refs)
    }

    fun addExpression(expr: NodeExpression) {
        pending.addAll(exprShapes.strict(expr))
        if (exprShapes.computes(expr)) candidates.wantIris()
    }

    private fun addConstraint(c: PropertyConstraint) {
        pending.addAll(constraintRefs(c))
        constraintExpressions(c).forEach { addExpression(it) }
        if (c.unwrapped() is PropertyConstraint.NodeByExpressionComputed) candidates.wantAll()
    }

    fun visitProperty(ps: CompiledPropertyShape) {
        if (ps.shapeNode in props || ps.shapeNode in baseProps) return
        check(ps.shapeNode)
        props[ps.shapeNode] = ps
        ps.constraints.forEach { addConstraint(it) }
        ps.logicalParts.forEach { pending.addAll(it.operandRefs()) }
        shapeExpressions(ps).forEach { addExpression(it) }
        ps.nestedPropertyShapes.forEach { visitProperty(it) }
    }

    fun visitNode(cn: CompiledNodeShape) {
        pending.addAll(cn.nodeRefs)
        pending.addAll(cn.nodeByExpressionRefs)
        pending.addAll(cn.targetWhereRefs)
        cn.logicalParts.forEach { pending.addAll(it.operandRefs()) }
        cn.nodeConstraints.forEach { addConstraint(it) }
        cn.targets.targetNodeExpressions.forEach { addExpression(it) }
        shapeExpressions(cn).forEach { addExpression(it) }
        cn.propertyShapes.forEach { visitProperty(it) }
    }

    fun run() {
        while (pending.isNotEmpty()) {
            budget.check("shape compilation")
            val ref = pending.removeFirst()
            if (ref in props || ref in nodes || ref in baseProps || ref in baseNodes) continue
            check(ref)
            if (index.objects(ref, SHACL.path).isNotEmpty()) {
                visitProperty(compilePropertyShape(ref, index, config))
            } else {
                val cn = compileNodeShape(ref, index, config)
                nodes[ref] = cn
                visitNode(cn)
            }
        }
    }
}

/**
 * The shapes a node expression of [index] can validate against, which the compiler must therefore compile: its
 * [NodeExpressions.shapeRefs] ([strict]: compiled like any referenced shape), and, when it computes a
 * `shnex:conformsToShape` shape ([computes]), every IRI of the shapes graph that is a shape ([iriCandidates]),
 * since the computed IRI is only known at evaluation time. A computed `sh:nodeByExpression` can produce any shape,
 * IRI or blank node ([allCandidates]). Candidates are compiled up front, not on demand, so that the compiled graph
 * and its recursion analysis stay complete and immutable, but tolerantly (R8, see [ShapeDependencies.compileCandidates]); the scan
 * is done once per index and ticks the preparation budget, and the compile loop checks the budget per shape.
 */
internal class ExpressionShapes(val index: ShapeGraphIndex) {
    val iriCandidates: Set<RdfResource> by lazy { shapeCandidates(index, blankNodes = false) }
    val allCandidates: Set<RdfResource> by lazy { shapeCandidates(index, blankNodes = true) }

    fun strict(expr: NodeExpression): Set<RdfResource> = NodeExpressions.shapeRefs(expr, index)

    fun computes(expr: NodeExpression): Boolean = NodeExpressions.computesShapes(expr, index)

    fun of(expr: NodeExpression): Set<RdfResource> = if (computes(expr)) strict(expr) + iriCandidates else strict(expr)

    /**
     * The shapes of the node expressions [ps] evaluates: `sh:values`, `sh:defaultValue`, a non-constant
     * `sh:deactivated`, the reifier conditions of its parameter triples and its constraints'.
     */
    fun of(ps: CompiledPropertyShape): List<RdfResource> =
        (shapeExpressions(ps) + ps.constraints.flatMap { constraintExpressions(it) }).flatMap { of(it) }
}

/** The shapes and node expressions compiled shapes reference, and the closure of a compiled shape graph. */
internal object ShapeDependencies {
    /**
     * The candidates of computed shape references that the shapes graph needs ([CandidateQueue]), compiled tolerantly
     * (R8): a candidate whose closure (the shapes it references) does not compile, or uses an unsupported feature
     * ([candidateOnly], under [UnsupportedFeatureHandling.FAIL]), is left out and mapped to the reason, so that an
     * unrelated ill-formed shape fails only an expression that validates against it. A candidate that compiles is
     * added to [refNodes] / [refProps] with its closure; under [UnsupportedFeatureHandling.IGNORE_WITH_WARNING] the
     * unsupported constructs of the shapes added that way are appended to [ignored].
     */
    internal fun compileCandidates(
        index: ShapeGraphIndex,
        config: ValidationConfig,
        budget: ValidationBudget,
        exprShapes: ExpressionShapes,
        candidates: CandidateQueue,
        refNodes: MutableMap<RdfResource, CompiledNodeShape>,
        refProps: MutableMap<RdfResource, CompiledPropertyShape>,
        candidateOnly: Map<RdfResource, String>,
        ignored: MutableList<String>,
    ): Map<RdfResource, String> {
        val errors = LinkedHashMap<RdfResource, String>()
        val failOnUnsupported = config.unsupportedFeatures == UnsupportedFeatureHandling.FAIL
        while (candidates.queue.isNotEmpty()) {
            budget.check("shape compilation")
            val candidate = candidates.queue.removeFirst()
            if (candidate in refNodes || candidate in refProps) continue
            val closure = ShapeClosure(
                index, config, budget, exprShapes, candidates, LinkedHashMap(), LinkedHashMap(), refNodes, refProps,
            ) { shape ->
                errors[shape]?.let { throw ShapeCompileException(it) }
                if (failOnUnsupported) {
                    candidateOnly[shape]?.let { throw ShapeCompileException("Shape $shape uses unsupported SHACL feature(s): $it") }
                }
            }
            try {
                closure.add(listOf(candidate))
                closure.run()
            } catch (e: ShapeCompileException) {
                errors[candidate] = "shape $candidate cannot be validated against: ${e.message}"
                continue
            }
            if (!failOnUnsupported) {
                (closure.nodes.keys + closure.props.keys).forEach { shape -> candidateOnly[shape]?.let { ignored.add(it) } }
            }
            closure.nodes.forEach { (k, v) -> refNodes.putIfAbsent(k, v) }
            closure.props.forEach { (k, v) -> refProps.putIfAbsent(k, v) }
        }
        return errors
    }

    /**
     * The node expressions [ps] evaluates apart from its constraints': `sh:values`, `sh:defaultValue`, a non-constant
     * `sh:deactivated`, and the non-constant `sh:deactivated` of the reifiers of its logical and `sh:property` triples.
     */
    internal fun shapeExpressions(ps: CompiledPropertyShape): List<NodeExpression> =
        listOfNotNull(ps.values, ps.defaultValue, ps.deactivatedWhen) + ps.logicalParts.flatMap { it.deactivatedWhen } +
            ps.reifierConditions.expressions()

    /** The node expressions [cn] evaluates apart from its constraints' and its targets' (see the other overload). */
    internal fun shapeExpressions(cn: CompiledNodeShape): List<NodeExpression> =
        listOfNotNull(cn.deactivatedWhen) + cn.logicalParts.flatMap { it.deactivatedWhen } + cn.reifierConditions.expressions() +
            listOfNotNull(cn.closedParameter, cn.ignoredParameter, cn.uniqueValuesForParameter).map { it.expr }

    /** Static shape dependency graph → shapes that belong to a recursive strongly connected component. */
    internal fun recursiveComponents(
        refNodes: Map<RdfResource, CompiledNodeShape>,
        refProps: Map<RdfResource, CompiledPropertyShape>,
        exprShapes: ExpressionShapes,
        functions: SparqlFunctionDeclarationSet,
        budget: ValidationBudget,
    ): Map<RdfResource, Int> {
        // A SPARQL query of a shape may call any SHACL SPARQL function, whose node expression body validates against
        // its shapes while the shape is checked: those shapes are dependencies of every shape that runs SPARQL
        // (sh:sparql, a SPARQL-based constraint component, a SPARQL node expression). A function body's own queries
        // may call the others, so the shapes of all bodies are taken, with the candidates of computed shapes.
        val sparqlBodyShapes: List<RdfResource> = functions.expressionBodies.flatMap { budget.tick("recursion analysis"); exprShapes.of(it) }.distinct()
        fun runsSparql(constraints: List<PropertyConstraint>, expressions: List<NodeExpression>) =
            sparqlBodyShapes.isNotEmpty() &&
                (constraints.any { it.unwrapped().let { c -> c is PropertyConstraint.Sparql || c is PropertyConstraint.SparqlComponent } } ||
                    expressions.any { NodeExpressions.runsSparql(it, exprShapes.index) })
        val successors = HashMap<RdfResource, List<RdfResource>>()
        for ((node, ps) in refProps) {
            // The node expressions of sh:values / sh:defaultValue / sh:expression / sh:nodeByExpression are evaluated
            // while checking the shape, so their shapes are dependencies; so are the candidates of a computed
            // sh:nodeByExpression, any of which it can produce.
            val expressions = shapeExpressions(ps) + ps.constraints.flatMap { constraintExpressions(it) }
            successors[node] = ps.constraints.flatMap { constraintRefs(it) + computedRefs(it, exprShapes) } +
                ps.logicalParts.flatMap { it.operandRefs() } + ps.nestedPropertyShapes.map { it.shapeNode } + exprShapes.of(ps) +
                (if (runsSparql(ps.constraints, expressions)) sparqlBodyShapes else emptyList())
        }
        // A shape node that is also a property shape is always checked as a property shape (see conformance checks).
        for ((node, cn) in refNodes) {
            if (node in refProps) continue
            // A non-constant sh:deactivated is evaluated before anything else of the shape: its shapes are
            // dependencies too, and so are those of the reifiers' sh:deactivated of its parameter triples.
            val expressions = cn.nodeConstraints.flatMap { constraintExpressions(it) } + shapeExpressions(cn)
            successors[node] = cn.nodeRefs + cn.nodeByExpressionRefs + cn.logicalParts.flatMap { it.operandRefs() } +
                cn.nodeConstraints.flatMap { constraintRefs(it) + computedRefs(it, exprShapes) } + cn.propertyShapes.map { it.shapeNode } +
                expressions.flatMap { exprShapes.of(it) } +
                (if (runsSparql(cn.nodeConstraints, expressions)) sparqlBodyShapes else emptyList())
        }
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

    /** The shapes a computed `sh:nodeByExpression` [c] can validate against: every candidate ([ExpressionShapes.allCandidates]). */
    private fun computedRefs(c: PropertyConstraint, exprShapes: ExpressionShapes): Collection<RdfResource> =
        if (c.unwrapped() is PropertyConstraint.NodeByExpressionComputed) exprShapes.allCandidates else emptyList()

    /**
     * The node expressions [c] evaluates: `sh:expression`, a computed `sh:nodeByExpression`, the computed parameters of
     * a [PropertyConstraint.Dynamic] and of a [PropertyConstraint.SparqlComponent] (SHACL 1.2 Node Expressions §8), the non-constant `sh:deactivated` of a
     * SPARQL-based constraint, and the non-constant `sh:deactivated` of the reifiers of its parameter triples
     * ([PropertyConstraint.Deactivatable]).
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
}
