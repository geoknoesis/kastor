package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.UnsupportedFeatureHandling
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclFeatureException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import com.geoknoesis.kastor.rdf.shacl.native.ShapeDeactivation.isDeactivated
import com.geoknoesis.kastor.rdf.shacl.native.ShapeDeactivation.deactivationExpression
import com.geoknoesis.kastor.rdf.shacl.native.ShapeDeactivation.reifierDeactivation
import com.geoknoesis.kastor.rdf.shacl.native.ShapeDeactivation.activeLink
import com.geoknoesis.kastor.rdf.shacl.native.ShapeDeactivation.ineffectiveReifierDeactivations
import com.geoknoesis.kastor.rdf.shacl.native.ShapeFeatureDetection.isNodeExpression
import com.geoknoesis.kastor.rdf.shacl.native.ShapeFeatureDetection.isTargetNodeExpression
import com.geoknoesis.kastor.rdf.shacl.native.ShapeFeatureDetection.callPredicate
import com.geoknoesis.kastor.rdf.shacl.native.ShapeFeatureDetection.detectUnsupportedFeatures
import com.geoknoesis.kastor.rdf.shacl.native.ShapeDiscovery.findNodeShapes
import com.geoknoesis.kastor.rdf.shacl.native.CandidateCompilation.compileCandidates
import com.geoknoesis.kastor.rdf.shacl.native.ShapeDependencies.recursiveComponents
import com.geoknoesis.kastor.rdf.shacl.native.ConstraintCompiler.Companion.parameterValue

internal object ShapesCompiler {

    /**
     * Compiles the shapes of [shapesTriples]. [expressionShapes] names further shapes to compile although nothing in
     * the shapes graph references them: the shapes of a node expression evaluated outside validation (Node
     * Expressions §3.4), as [NodeExpressions.shapeRefs] collects them from the expression compiled over the index.
     */
    fun compile(
        shapesTriples: List<RdfTriple>,
        config: ValidationConfig,
        budget: ValidationBudget = ValidationBudget.NONE,
        expressionShapes: (ShapeGraphIndex) -> Collection<RdfResource> = { emptyList() },
    ): CompiledShapeGraph {
        val index = ShapeGraphIndex(shapesTriples, budget)
        val nodeShapeSubjects = findNodeShapes(index, budget)
        val extraRoots = expressionShapes(index).toList()
        val exprShapes = ExpressionShapes(index)
        // The functions come first, as the shapes of their node expression bodies are validated against (and so
        // inspected) although nothing else may reach them. An ill-formed declaration is reported after detection,
        // which keeps reporting the unsupported features first.
        var functionsError: ShapeCompileException? = null
        val functions = try {
            SparqlFunctionDeclarations.compile(index)
        } catch (e: ShapeCompileException) {
            functionsError = e
            SparqlFunctionDeclarationSet.EMPTY
        }
        val detected = detectUnsupportedFeatures(index, nodeShapeSubjects, extraRoots, functions.expressionBodies, exprShapes, budget)
        val unsupported = detected.strict
        if (unsupported.isNotEmpty() && config.unsupportedFeatures == UnsupportedFeatureHandling.FAIL) {
            throw UnsupportedShaclFeatureException(
                "Unsupported SHACL feature(s) for the native engine: ${unsupported.keys.joinToString("; ")}. " +
                    "Set ValidationConfig.unsupportedFeatures = IGNORE_WITH_WARNING to skip these constructs.",
                unsupported.values.toSet(),
            )
        }
        functionsError?.let { throw it }
        val compiled = mutableListOf<CompiledNodeShape>()
        val byNode = LinkedHashMap<RdfResource, CompiledNodeShape>()
        for (subject in nodeShapeSubjects.sortedWith { a, b -> budget.check("shape ordering"); resourceOrdering.compare(a, b) }) {
            budget.check("shape compilation")
            if (isDeactivated(subject, index)) continue
            val cn = compileNodeShape(subject, index, config)
            compiled.add(cn)
            byNode[subject] = cn
        }

        val refNodes = LinkedHashMap<RdfResource, CompiledNodeShape>(byNode)
        val refProps = LinkedHashMap<RdfResource, CompiledPropertyShape>()
        val resolver = ShapeDependencyResolver(exprShapes, functions, budget)
        val candidates = CandidateQueue()
        val referenced = ShapeClosure(index, config, budget, resolver, candidates, refNodes, refProps)
        compiled.forEach { referenced.visitNode(it) }
        referenced.add(extraRoots)
        // The shapes of the node expression bodies of the functions SPARQL queries may call.
        functions.expressionBodies.forEach { referenced.addExpression(it) }
        referenced.run()
        val ignoredInCandidates = ArrayList<String>()
        val candidateErrors = compileCandidates(index, config, budget, resolver, candidates, refNodes, refProps, detected.candidateOnly, ignoredInCandidates)
        val graph = CompiledShapeGraph(
            byNode, compiled, refNodes, refProps, index,
            recursiveComponents(refNodes, refProps, resolver, budget),
            (unsupported.keys + ignoredInCandidates).map { "Unsupported SHACL feature ignored: $it" },
            functions,
            candidateErrors,
            ineffectiveReifierDeactivations(index, refNodes.keys + refProps.keys, budget),
        )
        // The compiled graph may be cached and shared by later runs, which bring budgets of their own.
        index.detachBudget()
        return graph
    }

    /**
     * The constraint parameters (SHACL 1.2 Core §3.1.1) of the core components, as predicates of a shape: a reifier of
     * a triple with one of these, or with the path of a parameter of a SPARQL-based component, deactivates the
     * constraints that use the triple (§3.1.6).
     */
    internal val coreParameterPredicates: Set<Iri> by lazy {
        setOf(
            SHACL.`class`, SHACL.datatype, SHACL.nodeKind, SHACL.minCount, SHACL.maxCount,
            SHACL.minExclusive, SHACL.minInclusive, SHACL.maxExclusive, SHACL.maxInclusive,
            SHACL.minLength, SHACL.maxLength, SHACL.pattern, SHACL.flags, SHACL.languageIn, SHACL.uniqueLang,
            SHACL.equals, SHACL.disjoint, SHACL.lessThan, SHACL.lessThanOrEquals, SHACL.subsetOf,
            SHACL.`not`, SHACL.`and`, SHACL.`or`, SHACL.xone, SHACL.node, SHACL.nodeByExpression, SHACL.`property`,
            SHACL.qualifiedValueShape, SHACL.qualifiedMinCount, SHACL.qualifiedMaxCount, SHACL.qualifiedValueShapesDisjoint,
            SHACL.closed, SHACL.ignoredProperties, SHACL.hasValue, SHACL.`in`, SHACL.sparql, shExpression,
            SHACL.someValue, SHACL.shape, SHACL.singleLine, SHACL.rootClass, SHACL.memberShape,
            SHACL.minListLength, SHACL.maxListLength, SHACL.uniqueMembers,
            SHACL.reifierShape, SHACL.reificationRequired, SHACL.uniqueValuesFor,
        )
    }

    /**
     * Whether a triple of a shape with [predicate] declares a constraint parameter, so that a reifier's
     * `sh:deactivated` on it deactivates the constraints that use it (SHACL 1.2 Core §3.1.6). On any other triple
     * (targets, `sh:path`, `sh:message`, `sh:severity`, `sh:values`…) it has no effect.
     */
    internal fun isParameterPredicate(predicate: Iri, index: ShapeGraphIndex): Boolean =
        predicate in coreParameterPredicates || index.sparqlComponents.isParameterPath(predicate)

    /** The shapes [expr] of [index] can validate against ([ExpressionShapes]), for expressions evaluated outside validation. */
    fun expressionShapes(expr: NodeExpression, index: ShapeGraphIndex): Set<RdfResource> = ExpressionShapes(index).of(expr)

    internal val shExpression = Iri(SHACL.namespace + "expression")

    internal val resourceOrdering: Comparator<RdfResource> = Comparator { a, b ->
        when {
            a is Iri && b is Iri -> a.value.compareTo(b.value)
            a is BlankNode && b is BlankNode -> a.id.compareTo(b.id)
            a is Iri -> -1
            else -> 1
        }
    }

    internal fun compileNodeShape(subject: RdfResource, index: ShapeGraphIndex, config: ValidationConfig): CompiledNodeShape {
        if (isDeactivated(subject, index)) {
            return CompiledNodeShape(
                shapeNode = subject, targets = Targets(), propertyShapes = emptyList(),
                severity = ViolationSeverity.VIOLATION, messages = emptyList(), closed = ClosedMode.NONE,
                ignoredProperties = emptySet(), logicalParts = emptyList(), nodeRefs = emptyList(), deactivated = true,
            )
        }
        val explicitTargetClasses = index.objects(subject, SHACL.targetClass).filterIsInstance<Iri>()
        // SHACL §2.1.3.3: a shape that is a SHACL instance of rdfs:Class in the shapes graph targets its instances.
        val implicitClassTargets =
            if (subject is Iri && (index.isInstanceOf(subject, RDFS.Class) || index.objects(subject, RDF.type).contains(SHACL.ShapeClass))) {
                listOf(subject)
            } else {
                emptyList()
            }
        val targetNodeValues = index.objects(subject, SHACL.targetNode)
        // An expression that does not compile (no node expression at all) is reported as unsupported.
        val targetNodeExpressions = targetNodeValues
            .filter { isTargetNodeExpression(it, index) }
            .mapNotNull { NodeExpressions.compile(it, index) }
        val targetNodes = targetNodeValues
            .filterNot { isTargetNodeExpression(it, index) }
            .onEach { ensureShapeTermAllowed(it, config) }
        val callShapedTargets = LinkedHashMap<BlankNode, Iri>()
        for (node in targetNodes) if (node is BlankNode) callPredicate(node, index)?.let { callShapedTargets[node] = it }
        val targets = Targets(
            targetClasses = (explicitTargetClasses + implicitClassTargets).distinct(),
            targetNodes = targetNodes,
            targetSubjectsOf = index.objects(subject, SHACL.targetSubjectsOf).filterIsInstance<Iri>(),
            targetObjectsOf = index.objects(subject, SHACL.targetObjectsOf).filterIsInstance<Iri>(),
            callShapedTargets = callShapedTargets,
            targetNodeExpressions = targetNodeExpressions,
        )

        val nodeSev =
            parseSeverityValues(index.objects(subject, SHACL.severity).filterIsInstance<Iri>(), ViolationSeverity.VIOLATION)
        val targetWhereRefs =
            index.objects(subject, SHACL.targetWhere).filterIsInstance<RdfResource>().filterNot { isNodeExpression(it, index) }

        if (index.objects(subject, SHACL.path).isNotEmpty()) {
            // A shape with sh:path is a property shape even when it declares targets: its sh:property values apply to
            // each value node (nested property shapes) and node-shape-only parameters such as sh:closed are ignored.
            return CompiledNodeShape(
                shapeNode = subject,
                targets = targets,
                targetWhereRefs = targetWhereRefs,
                propertyShapes = listOf(compilePropertyShape(subject, index, config)),
                severity = nodeSev.level,
                severityCustomIri = nodeSev.customIri,
                messages = index.objects(subject, SHACL.message).filterIsInstance<Literal>(),
                closed = ClosedMode.NONE,
                ignoredProperties = emptySet(),
                logicalParts = emptyList(),
                nodeRefs = emptyList(),
            )
        }

        // SHACL 1.2 Core 3.3: sh:values / sh:defaultValue belong to property shapes with a predicate path.
        for (predicate in listOf(ShaclSparqlTerms.values, ShaclSparqlTerms.defaultValue)) {
            if (index.objects(subject, predicate).isNotEmpty()) {
                val name = predicate.value.substringAfter(SHACL.namespace)
                throw ShapeCompileException("Shape $subject has sh:$name, which needs a property shape with a predicate path as sh:path")
            }
        }

        val links = LinkedHashMap<Pair<Iri, RdfResource>, List<NodeExpression>>()
        // Property shapes have their own severity (default sh:Violation); they never inherit the node shape's.
        val linkedPropShapes =
            index.objects(subject, SHACL.`property`)
                .mapNotNull { ps ->
                    val node = ps as? RdfResource ?: return@mapNotNull null
                    if (isDeactivated(node, index)) return@mapNotNull null
                    if (!activeLink(subject, SHACL.`property`, node, index, links)) return@mapNotNull null
                    compilePropertyShape(node, index, config)
                }

        val closedAllowedPredicates =
            index.objects(subject, SHACL.`property`).filterIsInstance<RdfResource>()
                .flatMap { index.objects(it, SHACL.path) }.filterIsInstance<Iri>().toSet()

        val closedTerms = index.objects(subject, SHACL.closed)
        val ignoredTerms = index.objects(subject, SHACL.ignoredProperties)
        // sh:ignoredProperties is an optional parameter of sh:ClosedConstraintComponent: a reifier of either triple
        // deactivates the constraint.
        val closedConditions = reifierDeactivation(
            subject, closedTerms.map { SHACL.closed to it } + ignoredTerms.map { SHACL.ignoredProperties to it }, index,
        )
        // SHACL 1.2 Node Expressions §8: either parameter may be computed. Null = an unsupported expression ignored with
        // a warning, which leaves the constraint out.
        val closedValues = closedTerms.map { parameterValue(subject, SHACL.closed, it, index, config) }
        val ignoredValues = ignoredTerms.map { parameterValue(subject, SHACL.ignoredProperties, it, index, config) }
        val closedComputed = closedValues.filterIsInstance<ParameterValue.Computed>()
        if (closedComputed.isNotEmpty() && closedTerms.size > 1) {
            throw ShapeCompileException("Shape $subject has ${closedTerms.size} values for sh:closed; a computed sh:closed must be the only one")
        }
        val ignoredComputed = ignoredValues.filterIsInstance<ParameterValue.Computed>()
        if (ignoredComputed.size > 1) {
            throw ShapeCompileException("Shape $subject has ${ignoredComputed.size} computed sh:ignoredProperties values; at most one is allowed")
        }
        val closedActive = closedConditions != null && closedValues.none { it == null } && ignoredValues.none { it == null }
        val closedParameter = closedComputed.singleOrNull()?.takeIf { closedActive }
        val closedMode =
            when {
                !closedActive || closedParameter != null -> ClosedMode.NONE
                closedTerms.contains(SHACL.ByTypes) -> ClosedMode.BY_TYPES
                closedTerms.any { isLexicallyTrue(it) } -> ClosedMode.TRUE
                else -> ClosedMode.NONE
            }
        val closedMayApply = closedMode != ClosedMode.NONE || closedParameter != null
        // The constant values, checked whatever sh:closed is, as ever.
        val ignored = ignoredTerms.zip(ignoredValues).filter { it.second is ParameterValue.Constant }.flatMap { (term, _) ->
            when (term) {
                is Iri -> listOf(term)
                is BlankNode -> index.parseRdfList(term).filterIsInstance<Iri>()
                else -> emptyList()
            }
        }.toSet()

        val logicalParts = compileLogicalParts(subject, index)
        val nodeRefsOnShape = index.objects(subject, SHACL.node).filterIsInstance<RdfResource>()
            .filter { activeLink(subject, SHACL.node, it, index, links) }
        // IRIs are constant shape references; other values are computed (PropertyConstraint.NodeByExpressionComputed).
        val nodeByExpressionRefs = index.objects(subject, SHACL.nodeByExpression).filterIsInstance<Iri>()
            .filter { activeLink(subject, SHACL.nodeByExpression, it, index, links) }
        val nodeConstraints = ConstraintCompiler(subject, index, config, ConstraintFlavor.NODE_SCALAR, null).compile()
        val uniqueValuesForConditions =
            reifierDeactivation(subject, index.objects(subject, SHACL.uniqueValuesFor).map { SHACL.uniqueValuesFor to it }, index)
        // A computed value (§8) is resolved per focus node; null = an unsupported expression ignored with a warning.
        val uniqueValuesForValue = uniqueValuesForValue(subject, index, config)
        val uniqueValuesForParameter = (uniqueValuesForValue as? ParameterValue.Computed)?.takeIf { uniqueValuesForConditions != null }
        val uniqueValuesForProps =
            if (uniqueValuesForValue is ParameterValue.Constant) {
                parseUniqueValuesFor(subject, index).takeIf { uniqueValuesForConditions != null }.orEmpty()
            } else {
                emptyList()
            }

        return CompiledNodeShape(
            deactivatedWhen = deactivationExpression(subject, index),
            shapeNode = subject,
            targets = targets,
            targetWhereRefs = targetWhereRefs,
            propertyShapes = linkedPropShapes,
            severity = nodeSev.level,
            severityCustomIri = nodeSev.customIri,
            messages = index.objects(subject, SHACL.message).filterIsInstance<Literal>(),
            closed = closedMode,
            ignoredProperties = ignored,
            logicalParts = logicalParts,
            nodeRefs = nodeRefsOnShape,
            nodeByExpressionRefs = nodeByExpressionRefs,
            nodeConstraints = nodeConstraints,
            uniqueValuesForProps = uniqueValuesForProps,
            closedAllowedPredicates = closedAllowedPredicates,
            reifierConditions = ReifierConditions(
                links = links,
                closed = closedConditions.orEmpty().takeIf { closedMayApply }.orEmpty(),
                uniqueValuesFor = uniqueValuesForConditions.orEmpty()
                    .takeIf { uniqueValuesForProps.isNotEmpty() || uniqueValuesForParameter != null }.orEmpty(),
            ),
            closedParameter = closedParameter,
            ignoredParameter = ignoredComputed.singleOrNull()?.takeIf { closedMayApply },
            uniqueValuesForParameter = uniqueValuesForParameter,
        )
    }

    /**
     * The `sh:and`, `sh:or`, `sh:xone` and `sh:not` constraints of [subject], without those a reifier of their triple
     * deactivates with the constant `true`, and with the non-constant deactivations (SHACL 1.2 Core §3.1.6).
     */
    private fun compileLogicalParts(subject: RdfResource, index: ShapeGraphIndex): MutableList<NodeLogicalPart> {
        val logicalParts = mutableListOf<NodeLogicalPart>()
        fun each(predicate: Iri, build: (RdfTerm, List<NodeExpression>) -> NodeLogicalPart) {
            for (value in index.objects(subject, predicate)) {
                val part = build(value, emptyList())
                val conditions = reifierDeactivation(subject, listOf(predicate to value), index) ?: continue
                logicalParts.add(if (conditions.isEmpty()) part else build(value, conditions))
            }
        }
        each(SHACL.`and`) { head, d -> NodeLogicalPart.And(parseShapeRefList(head, index), d) }
        each(SHACL.`or`) { head, d -> NodeLogicalPart.Or(parseShapeRefList(head, index), d) }
        each(SHACL.xone) { head, d -> NodeLogicalPart.Xone(parseShapeRefList(head, index), d) }
        each(SHACL.`not`) { n, d ->
            val ref = n as? RdfResource ?: throw ShapeCompileException("sh:not expects a shape reference, got $n")
            NodeLogicalPart.Not(ref, d)
        }
        return logicalParts
    }

    /** The `sh:uniqueValuesFor` of [subject], if any ([parameterValue]); at most one is allowed. */
    private fun uniqueValuesForValue(subject: RdfResource, index: ShapeGraphIndex, config: ValidationConfig): ParameterValue? {
        val values = index.objects(subject, SHACL.uniqueValuesFor)
        if (values.size > 1) throw ShapeCompileException("Shape $subject has several sh:uniqueValuesFor values; at most one is allowed")
        return values.firstOrNull()?.let { parameterValue(subject, SHACL.uniqueValuesFor, it, index, config) }
    }

    private fun parseUniqueValuesFor(subject: RdfResource, index: ShapeGraphIndex): List<Iri> {
        val values = index.objects(subject, SHACL.uniqueValuesFor)
        if (values.size > 1) throw ShapeCompileException("Shape $subject has several sh:uniqueValuesFor values; at most one is allowed")
        val raw = values.firstOrNull() ?: return emptyList()
        return when (raw) {
            is Iri -> listOf(raw)
            is BlankNode ->
                index.parseRdfList(raw).map {
                    it as? Iri ?: throw ShapeCompileException("sh:uniqueValuesFor list members must be IRIs")
                }
            else -> throw ShapeCompileException("sh:uniqueValuesFor expects an IRI or RDF list")
        }
    }

    internal fun compilePropertyShape(
        ps: RdfResource,
        index: ShapeGraphIndex,
        config: ValidationConfig,
        ancestors: Set<RdfResource> = emptySet(),
    ): CompiledPropertyShape {
        if (ps in ancestors || ancestors.size >= 128) throw ShapeCompileException("Cyclic or excessively nested property shapes")
        val pathValues = index.objects(ps, SHACL.path)
        if (isDeactivated(ps, index)) {
            val pathNode = pathValues.firstOrNull() ?: RDF.nil
            // An ill-formed path does not matter on a deactivated shape; a timeout or cancellation still stops compilation.
            val path = try {
                ShaclPathParser.parse(pathNode, index)
            } catch (_: ShapeCompileException) {
                ShaclPath.Predicate(RDF.nil)
            }
            return CompiledPropertyShape(
                shapeNode = ps, path = path, pathNode = pathNode, pathTriples = emptyList(),
                severity = ViolationSeverity.VIOLATION, messages = emptyList(), constraints = emptyList(), deactivated = true,
            )
        }
        val pathTerm = pathValues.singleOrNull()
            ?: throw ShapeCompileException("Property shape $ps must have exactly one sh:path")
        val path = ShaclPathParser.parse(pathTerm, index)

        val constraints = ConstraintCompiler(ps, index, config, ConstraintFlavor.PROPERTY_SHAPE, path).compile()
        val logicalParts = compileLogicalParts(ps, index)

        val links = LinkedHashMap<Pair<Iri, RdfResource>, List<NodeExpression>>()
        val nestedPropertyShapes =
            index.objects(ps, SHACL.`property`)
                .mapNotNull { child ->
                    val node = child as? RdfResource ?: return@mapNotNull null
                    if (isDeactivated(node, index)) return@mapNotNull null
                    if (!activeLink(ps, SHACL.`property`, node, index, links)) return@mapNotNull null
                    compilePropertyShape(node, index, config, ancestors + ps)
                }

        val psSev = parseSeverityValues(index.objects(ps, SHACL.severity).filterIsInstance<Iri>(), ViolationSeverity.VIOLATION)

        return CompiledPropertyShape(
            shapeNode = ps,
            path = path,
            pathNode = pathTerm,
            pathTriples = index.blankNodeClosure(pathTerm),
            severity = psSev.level,
            severityCustomIri = psSev.customIri,
            messages = index.objects(ps, SHACL.message).filterIsInstance<Literal>(),
            constraints = constraints,
            logicalParts = logicalParts,
            nestedPropertyShapes = nestedPropertyShapes,
            values = NodeExpressions.compileParameter(ps, ShaclSparqlTerms.values, path, index),
            defaultValue = NodeExpressions.compileParameter(ps, ShaclSparqlTerms.defaultValue, path, index),
            deactivatedWhen = deactivationExpression(ps, index),
            reifierConditions = ReifierConditions(links = links),
        )
    }

    private fun parseShapeRefList(head: RdfTerm, index: ShapeGraphIndex): List<RdfResource> =
        index.parseRdfList(head).map {
            it as? RdfResource ?: throw ShapeCompileException("Shape list entries must be IRIs or blank nodes: $it")
        }

    internal fun ensureShapeTermAllowed(term: RdfTerm, config: ValidationConfig) {
        if (term is TripleTerm && !config.allowTripleTermsInShapeParameters) {
            throw ShapeCompileException(
                "Triple-term value in shape parameter requires ValidationConfig.allowTripleTermsInShapeParameters=true (architecture §5.1 P1b)",
            )
        }
    }

    internal data class SeverityParse(val level: ViolationSeverity, val customIri: Iri?)

    internal fun parseSeverityValues(iris: List<Iri>, default: ViolationSeverity): SeverityParse {
        val i = iris.firstOrNull() ?: return SeverityParse(default, null)
        return when (i) {
            SHACL.Info -> SeverityParse(ViolationSeverity.INFO, null)
            SHACL.Warning -> SeverityParse(ViolationSeverity.WARNING, null)
            SHACL.Violation -> SeverityParse(ViolationSeverity.VIOLATION, null)
            SHACL.Debug -> SeverityParse(ViolationSeverity.DEBUG, null)
            SHACL.Trace -> SeverityParse(ViolationSeverity.TRACE, null)
            else -> SeverityParse(ViolationSeverity.VIOLATION, i)
        }
    }
}
