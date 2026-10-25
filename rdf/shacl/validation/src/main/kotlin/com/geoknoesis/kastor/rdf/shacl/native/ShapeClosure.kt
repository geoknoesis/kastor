package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.UnsupportedFeatureHandling
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler.compileNodeShape
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler.compilePropertyShape

/**
 * The candidates of computed shape references ([Dependencies.candidates]), queued once each when a compiled shape
 * needs them, for [CandidateCompilation.compileCandidates].
 */
internal class CandidateQueue {
    val queue = ArrayDeque<RdfResource>()
    private val seen = HashSet<RdfResource>()

    /** The symbolic candidate sets ([Dependencies.anyIriShape], [Dependencies.anyShape]) already queued, once each. */
    private var allIriShapes = false
    private var allShapes = false

    fun want(candidates: Collection<RdfResource>) {
        candidates.forEach { if (seen.add(it)) queue.add(it) }
    }

    /** Queues the candidates of [dependencies], expanding each symbolic set over [shapes] only the first time. */
    fun want(dependencies: Dependencies, shapes: ExpressionShapes) {
        want(dependencies.candidates)
        if (dependencies.anyShape && !allShapes) {
            allShapes = true
            allIriShapes = true
            want(shapes.allCandidates)
        } else if (dependencies.anyIriShape && !allIriShapes) {
            allIriShapes = true
            want(shapes.iriCandidates)
        }
    }
}

/**
 * Compiles the shapes added to it and every shape they depend on into [nodes] / [props], skipping the shapes of
 * [baseNodes] / [baseProps]: the strict [Dependencies] of each compiled shape's [ShapeReads] (and of what its targets
 * read, [ShapeReads.targets]), as [resolver] resolves them for the recursion analysis too. [check] runs before a
 * shape is compiled here. The candidates of computed shape references are queued in [candidates] instead of being
 * compiled.
 */
internal class ShapeClosure(
    private val index: ShapeGraphIndex,
    private val config: ValidationConfig,
    private val budget: ValidationBudget,
    private val resolver: ShapeDependencyResolver,
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

    fun addExpression(expr: NodeExpression) = add(resolver.ofExpression(expr))

    private fun add(dependencies: Dependencies) {
        pending.addAll(dependencies.strict)
        candidates.want(dependencies, resolver.exprShapes)
    }

    fun visitProperty(ps: CompiledPropertyShape) {
        if (ps.shapeNode in props || ps.shapeNode in baseProps) return
        check(ps.shapeNode)
        props[ps.shapeNode] = ps
        // The nested property shapes are compiled with it: visited here, so that their nodes are not compiled again.
        ps.nestedPropertyShapes.forEach { visitProperty(it) }
        add(resolver.resolve(ShapeReads.of(ps)))
    }

    fun visitNode(cn: CompiledNodeShape) {
        cn.propertyShapes.forEach { visitProperty(it) }
        add(resolver.resolve(ShapeReads.of(cn)))
        add(resolver.resolve(ShapeReads.targets(cn)))
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

/** The tolerant compilation of the candidates of computed shape references. */
internal object CandidateCompilation {
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
        resolver: ShapeDependencyResolver,
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
                index, config, budget, resolver, candidates, LinkedHashMap(), LinkedHashMap(), refNodes, refProps,
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
}
