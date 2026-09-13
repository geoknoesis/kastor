package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity

internal data class Targets(
    val targetClasses: List<Iri> = emptyList(),
    val targetNodes: List<RdfTerm> = emptyList(),
    val targetSubjectsOf: List<Iri> = emptyList(),
    val targetObjectsOf: List<Iri> = emptyList(),
)

internal sealed class NodeLogicalPart {
    data class And(val operands: List<RdfResource>) : NodeLogicalPart()
    data class Or(val operands: List<RdfResource>) : NodeLogicalPart()
    data class Xone(val operands: List<RdfResource>) : NodeLogicalPart()
    data class Not(val operand: RdfResource) : NodeLogicalPart()

    fun operandRefs(): List<RdfResource> =
        when (this) {
            is And -> operands
            is Or -> operands
            is Xone -> operands
            is Not -> listOf(operand)
        }
}

internal enum class ClosedMode {
    NONE,
    TRUE,
    BY_TYPES,
}

internal sealed class PropertyConstraint {
    data class MinCount(val n: Int) : PropertyConstraint()
    data class MaxCount(val n: Int) : PropertyConstraint()
    /** Allowed RDF literals match if lexical/datatype fits **any** listed datatype (`sh:datatype` lists). */
    data class Datatype(
        val allowed: List<Iri>,
        val severityOverride: ViolationSeverity? = null,
        /** `sh:message` values annotated on the `sh:datatype` triple (RDF 1.2 reifier), overriding the shape messages. */
        val messages: List<Literal> = emptyList(),
    ) : PropertyConstraint()
    data class Class(val iri: Iri) : PropertyConstraint()
    /** `sh:class` with an RDF list of classes: value must satisfy at least one entry (OR). */
    data class ClassAnyOf(val options: List<Iri>) : PropertyConstraint()
    /** Focus/value satisfies **any** listed node kind (`sh:nodeKind` lists). */
    data class NodeKind(val kinds: List<Iri>) : PropertyConstraint()
    /** One `sh:pattern` value combined with the shape's (single) `sh:flags`, compiled once at shape compile time. */
    class Pattern(val pattern: String, val flags: String?, val regex: Regex) : PropertyConstraint()
    data class MinLength(val n: Int) : PropertyConstraint()
    data class MaxLength(val n: Int) : PropertyConstraint()
    data class In(val allowed: List<RdfTerm>) : PropertyConstraint()
    data class HasValue(val value: RdfTerm) : PropertyConstraint()
    data class LanguageIn(val langs: List<String>) : PropertyConstraint()
    data class UniqueLang(val enabled: Boolean) : PropertyConstraint()
    data class Node(val nestedShape: RdfResource) : PropertyConstraint()
    data class EqualsPath(val otherPath: ShaclPath) : PropertyConstraint()
    data class DisjointPath(val otherPath: ShaclPath) : PropertyConstraint()
    data class LessThanPath(val otherPath: ShaclPath) : PropertyConstraint()
    data class LessThanOrEqualsPath(val otherPath: ShaclPath) : PropertyConstraint()
    data class MinInclusive(val bound: RdfTerm) : PropertyConstraint()
    data class MaxInclusive(val bound: RdfTerm) : PropertyConstraint()
    data class MinExclusive(val bound: RdfTerm) : PropertyConstraint()
    data class MaxExclusive(val bound: RdfTerm) : PropertyConstraint()
    /**
     * `sh:qualifiedValueShape`. [siblings] are the `sh:qualifiedValueShape` values of the other property shapes of
     * the same parent shape(s), used when [disjoint] is true.
     */
    data class Qualified(
        val shape: RdfResource,
        val min: Int?,
        val max: Int?,
        val disjoint: Boolean,
        val siblings: List<RdfResource> = emptyList(),
    ) : PropertyConstraint()
    data class MinListLength(val n: Int) : PropertyConstraint()
    data class MaxListLength(val n: Int) : PropertyConstraint()
    data class MemberShape(val nestedShape: RdfResource) : PropertyConstraint()
    data class UniqueMembers(val enabled: Boolean) : PropertyConstraint()
    data class SubsetOfPath(val otherPath: ShaclPath) : PropertyConstraint()
    data class SingleLine(val enabled: Boolean) : PropertyConstraint()
    data class SomeValue(val nestedShape: RdfResource) : PropertyConstraint()
    data class RootClass(val roots: List<Iri>) : PropertyConstraint()
    data class Shape(val nestedShape: RdfResource) : PropertyConstraint()
    /**
     * SHACL-SPARQL constraint (`sh:sparql`). [query] has `sh:prefixes` declarations prepended and `$PATH`
     * substituted; [preBound] lists the pre-bound variables it references (`this`, `currentShape`, `shapesGraph`).
     */
    data class Sparql(
        val constraintNode: RdfResource,
        val query: String,
        val messages: List<Literal>,
        val severity: ViolationSeverity?,
        val severityCustomIri: Iri?,
        val preBound: Set<String>,
    ) : PropertyConstraint()
    /** SHACL 1.2: validate resources that reify triples matching this property shape. */
    data class ReifierShape(val nestedShape: RdfResource) : PropertyConstraint()
    /** SHACL 1.2: asserted triples matching the path must have at least one `rdf:reifies` reifier. */
    data class ReificationRequired(val required: Boolean) : PropertyConstraint()
}

internal data class CompiledPropertyShape(
    val shapeNode: RdfResource,
    val path: ShaclPath,
    /** The `sh:path` value as written in the shapes graph (IRI or blank node). */
    val pathNode: RdfTerm,
    /** Blank-node structure of a complex [pathNode], reported as `sh:resultPath`. */
    val pathTriples: List<RdfTriple>,
    val severity: ViolationSeverity,
    val severityCustomIri: Iri? = null,
    val messages: List<Literal>,
    val constraints: List<PropertyConstraint>,
    val logicalParts: List<NodeLogicalPart> = emptyList(),
    /** Nested `sh:property` constraints evaluated relative to each value of this shape's path. */
    val nestedPropertyShapes: List<CompiledPropertyShape> = emptyList(),
    /** `sh:deactivated true`: every node conforms. */
    val deactivated: Boolean = false,
)

internal data class CompiledNodeShape(
    val shapeNode: RdfResource,
    val targets: Targets,
    /** `sh:targetWhere` shape references (membership = focus conforms to the referenced shape). */
    val targetWhereRefs: List<RdfResource> = emptyList(),
    val propertyShapes: List<CompiledPropertyShape>,
    val severity: ViolationSeverity,
    val severityCustomIri: Iri? = null,
    val messages: List<Literal>,
    val closed: ClosedMode,
    val ignoredProperties: Set<Iri>,
    val logicalParts: List<NodeLogicalPart>,
    /** `sh:node` constraints targeting the focus node itself. */
    val nodeRefs: List<RdfResource>,
    /** `sh:nodeByExpression` shape/expression references (SHACL 1.2). */
    val nodeByExpressionRefs: List<RdfResource> = emptyList(),
    /** Scalar constraints declared on the node shape (e.g. `sh:datatype`, `sh:hasValue`) without `sh:property`. */
    val nodeConstraints: List<PropertyConstraint> = emptyList(),
    /** SHACL 1.2 composite uniqueness across targets (`sh:uniqueValuesFor`). */
    val uniqueValuesForProps: List<Iri> = emptyList(),
    /** `sh:deactivated true`: every node conforms. */
    val deactivated: Boolean = false,
)

/**
 * Compiled shapes graph. Every shape reachable from a top-level shape (via `sh:node`, `sh:and`/`sh:or`/`sh:xone`/
 * `sh:not`, `sh:qualifiedValueShape`, `sh:shape`, `sh:someValue`, `sh:memberShape`, `sh:reifierShape`,
 * `sh:nodeByExpression`, `sh:targetWhere`, `sh:property`) is compiled exactly once — including deactivated and
 * constraint-free shapes, to which every node conforms.
 */
internal data class CompiledShapeGraph(
    /** Top-level, active shapes (targets are evaluated for these). */
    val shapesByNode: Map<RdfResource, CompiledNodeShape>,
    val orderedNodeShapes: List<CompiledNodeShape>,
    val referencedNodeShapes: Map<RdfResource, CompiledNodeShape> = emptyMap(),
    val referencedPropertyShapes: Map<RdfResource, CompiledPropertyShape> = emptyMap(),
    val index: ShapeGraphIndex = ShapeGraphIndex(emptyList()),
) {
    /** True when some SHACL-SPARQL constraint references `$shapesGraph`. */
    val sparqlUsesShapesGraph: Boolean by lazy {
        fun uses(cs: List<PropertyConstraint>) = cs.any { it is PropertyConstraint.Sparql && "shapesGraph" in it.preBound }
        referencedNodeShapes.values.any { uses(it.nodeConstraints) } || referencedPropertyShapes.values.any { uses(it.constraints) }
    }
}

internal object ShapesCompiler {

    private enum class ConstraintFlavor {
        PROPERTY_SHAPE,
        NODE_SCALAR,
    }

    fun compile(shapesTriples: List<RdfTriple>, config: ValidationConfig, budget: ValidationBudget = ValidationBudget.NONE): CompiledShapeGraph {
        val index = ShapeGraphIndex(shapesTriples, budget)
        val nodeShapeSubjects = findNodeShapes(index, budget)
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
        val pending = ArrayDeque<RdfResource>()
        fun visitProperty(ps: CompiledPropertyShape) {
            if (refProps.putIfAbsent(ps.shapeNode, ps) != null) return
            ps.constraints.forEach { pending.addAll(constraintRefs(it)) }
            ps.logicalParts.forEach { pending.addAll(it.operandRefs()) }
            ps.nestedPropertyShapes.forEach { visitProperty(it) }
        }
        fun visitNode(cn: CompiledNodeShape) {
            pending.addAll(cn.nodeRefs)
            pending.addAll(cn.nodeByExpressionRefs)
            pending.addAll(cn.targetWhereRefs)
            cn.logicalParts.forEach { pending.addAll(it.operandRefs()) }
            cn.nodeConstraints.forEach { pending.addAll(constraintRefs(it)) }
            cn.propertyShapes.forEach { visitProperty(it) }
        }
        compiled.forEach { visitNode(it) }
        while (pending.isNotEmpty()) {
            budget.check("shape compilation")
            val ref = pending.removeFirst()
            if (ref in refProps || ref in refNodes) continue
            if (index.objects(ref, SHACL.path).isNotEmpty()) {
                visitProperty(compilePropertyShape(ref, index, config))
            } else {
                val cn = compileNodeShape(ref, index, config)
                refNodes[ref] = cn
                visitNode(cn)
            }
        }
        return CompiledShapeGraph(byNode, compiled, refNodes, refProps, index)
    }

    private fun constraintRefs(c: PropertyConstraint): List<RdfResource> =
        when (c) {
            is PropertyConstraint.Node -> listOf(c.nestedShape)
            is PropertyConstraint.Qualified -> listOf(c.shape) + c.siblings
            is PropertyConstraint.MemberShape -> listOf(c.nestedShape)
            is PropertyConstraint.SomeValue -> listOf(c.nestedShape)
            is PropertyConstraint.Shape -> listOf(c.nestedShape)
            is PropertyConstraint.ReifierShape -> listOf(c.nestedShape)
            else -> emptyList()
        }

    private val resourceOrdering: Comparator<RdfResource> = Comparator { a, b ->
        when {
            a is Iri && b is Iri -> a.value.compareTo(b.value)
            a is BlankNode && b is BlankNode -> a.id.compareTo(b.id)
            a is Iri -> -1
            else -> 1
        }
    }

    private fun findNodeShapes(index: ShapeGraphIndex, budget: ValidationBudget): List<RdfResource> {
        val triples = index.triples
        val set = LinkedHashSet<RdfResource>()
        for (t in triples) {
            budget.tick("shape discovery")
            if (t.predicate == RDF.type &&
                (t.obj == SHACL.NodeShape || t.obj == SHACL.ShapeClass || t.obj == SHACL.Shape)
            ) {
                set.add(t.subject)
            }
        }
        // Implicit node shapes: subjects with targets (explicit, or an implicit class target because the subject is
        // a SHACL instance of rdfs:Class) and shape parameters but no `rdf:type` (W3C misc/severity-002, etc.).
        for (t in triples) {
            budget.tick("shape discovery")
            val s = t.subject
            if (s in set) continue
            if (index.objects(s, SHACL.path).isNotEmpty()) continue
            if (!hasShapeTargets(s, index)) continue
            if (looksLikeImplicitNodeShape(s, index)) set.add(s)
        }
        // Standalone shapes declared with `sh:path` plus targets (W3C path-* manifests); covers PropertyShape
        // roots whether or not an explicit `rdf:type` triple is present.
        for (t in triples) {
            budget.tick("shape discovery")
            val s = t.subject
            if (s in set) continue
            if (index.objects(s, SHACL.path).isEmpty()) continue
            if (!hasShapeTargets(s, index)) continue
            set.add(s)
        }
        budget.check("shape discovery")
        return set.toList()
    }

    private fun hasShapeTargets(s: RdfResource, index: ShapeGraphIndex): Boolean =
        index.objects(s, SHACL.targetClass).isNotEmpty() ||
            index.objects(s, SHACL.targetNode).isNotEmpty() ||
            index.objects(s, SHACL.targetSubjectsOf).isNotEmpty() ||
            index.objects(s, SHACL.targetObjectsOf).isNotEmpty() ||
            index.objects(s, SHACL.targetWhere).isNotEmpty() ||
            (s is Iri && index.isInstanceOf(s, RDFS.Class))

    private val shapeParameters: List<Iri> by lazy {
        listOf(
            SHACL.`property`, SHACL.node, SHACL.`and`, SHACL.`or`, SHACL.xone, SHACL.`not`, SHACL.closed,
            SHACL.ignoredProperties, SHACL.datatype, SHACL.nodeByExpression, SHACL.nodeKind, SHACL.`class`,
            SHACL.pattern, SHACL.minCount, SHACL.maxCount, SHACL.uniqueValuesFor, SHACL.severity, SHACL.targetWhere,
            SHACL.`in`, SHACL.hasValue, SHACL.minInclusive, SHACL.maxInclusive, SHACL.minExclusive, SHACL.maxExclusive,
            SHACL.minLength, SHACL.maxLength, SHACL.languageIn, SHACL.sparql, SHACL.equals, SHACL.disjoint,
            SHACL.shape, SHACL.rootClass, SHACL.singleLine, SHACL.minListLength, SHACL.maxListLength,
            SHACL.memberShape, SHACL.uniqueMembers, SHACL.someValue,
        )
    }

    private fun looksLikeImplicitNodeShape(s: RdfResource, index: ShapeGraphIndex): Boolean =
        shapeParameters.any { index.objects(s, it).isNotEmpty() }

    private fun compileNodeShape(subject: RdfResource, index: ShapeGraphIndex, config: ValidationConfig): CompiledNodeShape {
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
        val targets = Targets(
            targetClasses = (explicitTargetClasses + implicitClassTargets).distinct(),
            targetNodes = index.objects(subject, SHACL.targetNode).onEach { ensureShapeTermAllowed(it, config) },
            targetSubjectsOf = index.objects(subject, SHACL.targetSubjectsOf).filterIsInstance<Iri>(),
            targetObjectsOf = index.objects(subject, SHACL.targetObjectsOf).filterIsInstance<Iri>(),
        )

        val nodeSev =
            parseSeverityValues(index.objects(subject, SHACL.severity).filterIsInstance<Iri>(), ViolationSeverity.VIOLATION)

        // Property shapes have their own severity (default sh:Violation); they never inherit the node shape's.
        val linkedPropShapes =
            index.objects(subject, SHACL.`property`)
                .mapNotNull { ps ->
                    val node = ps as? RdfResource ?: return@mapNotNull null
                    if (isDeactivated(node, index)) return@mapNotNull null
                    if (isParameterTripleDeactivated(subject, SHACL.`property`, ps, index)) return@mapNotNull null
                    compilePropertyShape(node, index, config)
                }

        val parametersOnShapeNode = index.objects(subject, SHACL.path).isNotEmpty()
        val selfAsPropertyShape =
            if (parametersOnShapeNode) {
                compilePropertyShape(subject, index, config)
            } else {
                null
            }
        val propShapes = linkedPropShapes + listOfNotNull(selfAsPropertyShape)

        val closedTerms = index.objects(subject, SHACL.closed)
        val closedMode =
            when {
                closedTerms.contains(SHACL.ByTypes) -> ClosedMode.BY_TYPES
                closedTerms.any { isLexicallyTrue(it) } -> ClosedMode.TRUE
                else -> ClosedMode.NONE
            }
        val ignored = index.objects(subject, SHACL.ignoredProperties).flatMap { term ->
            when (term) {
                is Iri -> listOf(term)
                is BlankNode -> index.parseRdfList(term).filterIsInstance<Iri>()
                else -> emptyList()
            }
        }.toSet()

        val logicalParts = if (parametersOnShapeNode) mutableListOf() else compileLogicalParts(subject, index)

        val nodeRefsOnShape =
            if (parametersOnShapeNode) emptyList() else index.objects(subject, SHACL.node).filterIsInstance<RdfResource>()
        val nodeByExpressionRefs =
            if (parametersOnShapeNode) emptyList() else index.objects(subject, SHACL.nodeByExpression).filterIsInstance<RdfResource>()
        val nodeConstraints =
            if (parametersOnShapeNode) emptyList() else compileConstraints(subject, index, config, ConstraintFlavor.NODE_SCALAR, null)
        val uniqueValuesForProps =
            if (parametersOnShapeNode) emptyList() else parseUniqueValuesFor(subject, index)
        val targetWhereRefs =
            if (parametersOnShapeNode) emptyList() else index.objects(subject, SHACL.targetWhere).filterIsInstance<RdfResource>()

        return CompiledNodeShape(
            shapeNode = subject,
            targets = targets,
            targetWhereRefs = targetWhereRefs,
            propertyShapes = propShapes,
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
        )
    }

    private fun compileLogicalParts(subject: RdfResource, index: ShapeGraphIndex): MutableList<NodeLogicalPart> {
        val logicalParts = mutableListOf<NodeLogicalPart>()
        index.objects(subject, SHACL.`and`).forEach { head -> logicalParts.add(NodeLogicalPart.And(parseShapeRefList(head, index))) }
        index.objects(subject, SHACL.`or`).forEach { head -> logicalParts.add(NodeLogicalPart.Or(parseShapeRefList(head, index))) }
        index.objects(subject, SHACL.xone).forEach { head -> logicalParts.add(NodeLogicalPart.Xone(parseShapeRefList(head, index))) }
        index.objects(subject, SHACL.`not`).forEach { n ->
            val ref = n as? RdfResource ?: throw ShapeCompileException("sh:not expects a shape reference, got $n")
            logicalParts.add(NodeLogicalPart.Not(ref))
        }
        return logicalParts
    }

    /**
     * Compiles the constraint parameters of [subject].
     *
     * Parameters SHACL allows to repeat (`sh:pattern`, `sh:hasValue`, `sh:equals`, `sh:disjoint`, `sh:lessThan`,
     * `sh:lessThanOrEquals`, value range bounds, `sh:qualifiedValueShape`, `sh:class`, `sh:datatype`, nested shape
     * references…) yield one constraint per value. Parameters restricted to at most one value (`sh:minCount`,
     * `sh:maxCount`, `sh:minLength`, `sh:maxLength`, `sh:flags`, `sh:in`, `sh:languageIn`, `sh:uniqueLang`,
     * `sh:qualifiedMinCount`, `sh:qualifiedMaxCount`, `sh:qualifiedValueShapesDisjoint`, and the SHACL 1.2 list and
     * boolean parameters) raise [ShapeCompileException] when repeated.
     */
    private fun compileConstraints(
        subject: RdfResource,
        index: ShapeGraphIndex,
        config: ValidationConfig,
        flavor: ConstraintFlavor,
        path: ShaclPath?,
    ): MutableList<PropertyConstraint> {
        val constraints = mutableListOf<PropertyConstraint>()
        fun atMostOne(pred: Iri): RdfTerm? {
            val values = index.objects(subject, pred)
            if (values.size > 1) {
                throw ShapeCompileException("Shape $subject has ${values.size} values for $pred; SHACL allows at most one")
            }
            return values.firstOrNull()
        }
        if (flavor == ConstraintFlavor.PROPERTY_SHAPE) {
            atMostOne(SHACL.minCount)?.let { constraints.add(PropertyConstraint.MinCount(parseNonNegativeInt(it, "sh:minCount"))) }
            atMostOne(SHACL.maxCount)?.let { constraints.add(PropertyConstraint.MaxCount(parseNonNegativeInt(it, "sh:maxCount"))) }
        }
        for (dtObj in index.objects(subject, SHACL.datatype)) {
            if (isParameterTripleDeactivated(subject, SHACL.datatype, dtObj, index)) continue
            val override = annotatedConstraintSeverity(subject, SHACL.datatype, dtObj, index)
            val allowed =
                when (dtObj) {
                    is Iri -> listOf(dtObj)
                    is BlankNode ->
                        index.parseRdfList(dtObj).map {
                            it as? Iri ?: throw ShapeCompileException("sh:datatype list entries must be IRIs: $it")
                        }
                    else -> throw ShapeCompileException("sh:datatype expects an IRI or RDF list, got $dtObj")
                }
            if (allowed.isNotEmpty()) {
                constraints.add(PropertyConstraint.Datatype(allowed, override, annotatedConstraintMessages(subject, SHACL.datatype, dtObj, index)))
            }
        }
        for (co in index.objects(subject, SHACL.`class`)) {
            when (co) {
                is Iri -> constraints.add(PropertyConstraint.Class(co))
                is BlankNode -> {
                    val opts =
                        index.parseRdfList(co).map {
                            it as? Iri ?: throw ShapeCompileException("sh:class list entries must be IRIs: $it")
                        }
                    when (opts.size) {
                        0 -> Unit
                        1 -> constraints.add(PropertyConstraint.Class(opts.first()))
                        else -> constraints.add(PropertyConstraint.ClassAnyOf(opts))
                    }
                }
                else -> Unit
            }
        }
        val nodeKindKinds = mutableListOf<Iri>()
        for (nk in index.objects(subject, SHACL.nodeKind)) {
            when (nk) {
                is Iri -> nodeKindKinds.add(nk)
                is BlankNode ->
                    nodeKindKinds.addAll(
                        index.parseRdfList(nk).map {
                            it as? Iri ?: throw ShapeCompileException("sh:nodeKind list entries must be IRIs: $it")
                        },
                    )
                else -> Unit
            }
        }
        if (nodeKindKinds.isNotEmpty()) {
            constraints.add(PropertyConstraint.NodeKind(nodeKindKinds.distinct()))
        }
        val flags = atMostOne(SHACL.flags)?.let { literalString(it) ?: throw ShapeCompileException("sh:flags must be a string literal") }
        for (p in index.objects(subject, SHACL.pattern)) {
            val pat = literalString(p) ?: throw ShapeCompileException("sh:pattern must be a string literal")
            constraints.add(PropertyConstraint.Pattern(pat, flags, compileShaclPattern(pat, flags)))
        }
        atMostOne(SHACL.minLength)?.let { constraints.add(PropertyConstraint.MinLength(parseNonNegativeInt(it, "sh:minLength"))) }
        atMostOne(SHACL.maxLength)?.let { constraints.add(PropertyConstraint.MaxLength(parseNonNegativeInt(it, "sh:maxLength"))) }
        atMostOne(SHACL.`in`)?.let { head ->
            val terms = index.parseRdfList(head)
            terms.forEach { ensureShapeTermAllowed(it, config) }
            constraints.add(PropertyConstraint.In(terms))
        }
        for (hv in index.objects(subject, SHACL.hasValue)) {
            ensureShapeTermAllowed(hv, config)
            constraints.add(PropertyConstraint.HasValue(hv))
        }
        atMostOne(SHACL.languageIn)?.let { head ->
            val langs = index.parseRdfList(head).map {
                literalString(it) ?: throw ShapeCompileException("sh:languageIn list must be string literals")
            }
            constraints.add(PropertyConstraint.LanguageIn(langs))
        }
        atMostOne(SHACL.uniqueLang)?.let { constraints.add(PropertyConstraint.UniqueLang(isLexicallyTrue(it))) }
        atMostOne(SHACL.minListLength)?.let { constraints.add(PropertyConstraint.MinListLength(parseNonNegativeInt(it, "sh:minListLength"))) }
        atMostOne(SHACL.maxListLength)?.let { constraints.add(PropertyConstraint.MaxListLength(parseNonNegativeInt(it, "sh:maxListLength"))) }
        index.objects(subject, SHACL.memberShape).filterIsInstance<RdfResource>().forEach {
            constraints.add(PropertyConstraint.MemberShape(it))
        }
        atMostOne(SHACL.uniqueMembers)?.let { constraints.add(PropertyConstraint.UniqueMembers(isLexicallyTrue(it))) }
        atMostOne(SHACL.singleLine)?.let { constraints.add(PropertyConstraint.SingleLine(isLexicallyTrue(it))) }
        for (rc in index.objects(subject, SHACL.rootClass)) {
            val roots =
                when (rc) {
                    is Iri -> listOf(rc)
                    is BlankNode ->
                        index.parseRdfList(rc).map {
                            it as? Iri ?: throw ShapeCompileException("sh:rootClass list members must be IRIs")
                        }
                    else -> throw ShapeCompileException("sh:rootClass expects an IRI or RDF list")
                }
            constraints.add(PropertyConstraint.RootClass(roots))
        }
        index.objects(subject, SHACL.subsetOf).forEach {
            constraints.add(PropertyConstraint.SubsetOfPath(ShaclPathParser.parse(it, index)))
        }
        index.objects(subject, SHACL.someValue).filterIsInstance<RdfResource>().forEach {
            constraints.add(PropertyConstraint.SomeValue(it))
        }
        index.objects(subject, SHACL.shape).filterIsInstance<RdfResource>().forEach {
            constraints.add(PropertyConstraint.Shape(it))
        }
        if (flavor == ConstraintFlavor.PROPERTY_SHAPE) {
            index.objects(subject, SHACL.node).filterIsInstance<RdfResource>().forEach {
                constraints.add(PropertyConstraint.Node(it))
            }
            index.objects(subject, SHACL.lessThan).forEach {
                constraints.add(PropertyConstraint.LessThanPath(ShaclPathParser.parse(it, index)))
            }
            index.objects(subject, SHACL.lessThanOrEquals).forEach {
                constraints.add(PropertyConstraint.LessThanOrEqualsPath(ShaclPathParser.parse(it, index)))
            }
        }
        index.objects(subject, SHACL.equals).forEach {
            constraints.add(PropertyConstraint.EqualsPath(ShaclPathParser.parse(it, index)))
        }
        index.objects(subject, SHACL.disjoint).forEach {
            constraints.add(PropertyConstraint.DisjointPath(ShaclPathParser.parse(it, index)))
        }
        index.objects(subject, SHACL.minInclusive).forEach { constraints.add(PropertyConstraint.MinInclusive(it)) }
        index.objects(subject, SHACL.maxInclusive).forEach { constraints.add(PropertyConstraint.MaxInclusive(it)) }
        index.objects(subject, SHACL.minExclusive).forEach { constraints.add(PropertyConstraint.MinExclusive(it)) }
        index.objects(subject, SHACL.maxExclusive).forEach { constraints.add(PropertyConstraint.MaxExclusive(it)) }
        if (flavor == ConstraintFlavor.PROPERTY_SHAPE) {
            val qualifiedShapes = index.objects(subject, SHACL.qualifiedValueShape).filterIsInstance<RdfResource>()
            val min = atMostOne(SHACL.qualifiedMinCount)?.let { parseNonNegativeInt(it, "sh:qualifiedMinCount") }
            val max = atMostOne(SHACL.qualifiedMaxCount)?.let { parseNonNegativeInt(it, "sh:qualifiedMaxCount") }
            val disjoint = atMostOne(SHACL.qualifiedValueShapesDisjoint)?.let { isLexicallyTrue(it) } ?: false
            for (qvs in qualifiedShapes) {
                val siblings =
                    if (!disjoint) {
                        emptyList()
                    } else {
                        index.subjects(SHACL.`property`, subject)
                            .flatMap { parent -> index.objects(parent, SHACL.`property`) }
                            .filterIsInstance<RdfResource>()
                            .flatMap { sibling -> index.objects(sibling, SHACL.qualifiedValueShape) }
                            .filterIsInstance<RdfResource>()
                            .distinct()
                            .filter { it != qvs }
                    }
                constraints.add(PropertyConstraint.Qualified(qvs, min, max, disjoint, siblings))
            }
            index.objects(subject, SHACL.reifierShape).filterIsInstance<RdfResource>().forEach {
                constraints.add(PropertyConstraint.ReifierShape(it))
            }
            atMostOne(SHACL.reificationRequired)?.let {
                constraints.add(PropertyConstraint.ReificationRequired(isLexicallyTrue(it)))
            }
        }
        index.objects(subject, SHACL.sparql).filterIsInstance<RdfResource>().forEach {
            compileSparqlConstraint(it, index, path)?.let { c -> constraints.add(c) }
        }
        return constraints
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

    private fun compilePropertyShape(
        ps: RdfResource,
        index: ShapeGraphIndex,
        config: ValidationConfig,
        ancestors: Set<RdfResource> = emptySet(),
    ): CompiledPropertyShape {
        if (ps in ancestors || ancestors.size >= 128) throw ShapeCompileException("Cyclic or excessively nested property shapes")
        val pathValues = index.objects(ps, SHACL.path)
        if (isDeactivated(ps, index)) {
            val pathNode = pathValues.firstOrNull() ?: RDF.nil
            val path = runCatching { ShaclPathParser.parse(pathNode, index) }.getOrDefault(ShaclPath.Predicate(RDF.nil))
            return CompiledPropertyShape(
                shapeNode = ps, path = path, pathNode = pathNode, pathTriples = emptyList(),
                severity = ViolationSeverity.VIOLATION, messages = emptyList(), constraints = emptyList(), deactivated = true,
            )
        }
        val pathTerm = pathValues.singleOrNull()
            ?: throw ShapeCompileException("Property shape $ps must have exactly one sh:path")
        val path = ShaclPathParser.parse(pathTerm, index)

        val constraints = compileConstraints(ps, index, config, ConstraintFlavor.PROPERTY_SHAPE, path)
        val logicalParts = compileLogicalParts(ps, index)

        val nestedPropertyShapes =
            index.objects(ps, SHACL.`property`)
                .mapNotNull { child ->
                    val node = child as? RdfResource ?: return@mapNotNull null
                    if (isDeactivated(node, index)) return@mapNotNull null
                    if (isParameterTripleDeactivated(ps, SHACL.`property`, child, index)) return@mapNotNull null
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
        )
    }

    // --- SHACL-SPARQL ---------------------------------------------------------------------------------------------

    private val preBoundNames = listOf("this", "currentShape", "shapesGraph")
    private val pathToken = Regex("\\\$PATH\\b")

    private fun compileSparqlConstraint(node: RdfResource, index: ShapeGraphIndex, path: ShaclPath?): PropertyConstraint.Sparql? {
        if (isDeactivated(node, index)) return null
        val text = (index.objects(node, SHACL.select).singleOrNull() as? Literal)?.lexical
            ?: throw ShapeCompileException("SPARQL constraint $node must have exactly one sh:select string literal")
        val body =
            if (pathToken.containsMatchIn(text)) {
                val p = path ?: throw ShapeCompileException("SPARQL constraint $node uses \$PATH but is not attached to a property shape")
                text.replace(pathToken, Regex.escapeReplacement(renderSparqlPath(p)))
            } else {
                text
            }
        val stripped = stripSparqlLexicalNoise(body)
        validatePreBinding(stripped, node)
        val prefixes = collectPrefixDeclarations(node, index)
        val query = prefixes.entries.joinToString("") { (p, ns) -> "PREFIX $p: <$ns>\n" } + body
        val severityIris = index.objects(node, SHACL.severity).filterIsInstance<Iri>()
        val sev = if (severityIris.isEmpty()) null else parseSeverityValues(severityIris, ViolationSeverity.VIOLATION)
        return PropertyConstraint.Sparql(
            constraintNode = node,
            query = query,
            messages = index.objects(node, SHACL.message).filterIsInstance<Literal>(),
            severity = sev?.level,
            severityCustomIri = sev?.customIri,
            preBound = preBoundNames.filter { variableRegex(it).containsMatchIn(stripped) }.toSet(),
        )
    }

    private fun variableRegex(name: String) = Regex("[?\$]$name\\b")

    /** `sh:prefixes` → `sh:declare [ sh:prefix; sh:namespace ]`, following `owl:imports` of the prefix holders. */
    private fun collectPrefixDeclarations(node: RdfResource, index: ShapeGraphIndex): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val visited = HashSet<RdfTerm>()
        val queue = ArrayDeque(index.objects(node, SHACL.prefixes))
        while (queue.isNotEmpty()) {
            val holder = queue.removeFirst()
            if (holder !is RdfResource || !visited.add(holder)) continue
            for (decl in index.objects(holder, SHACL.declare)) {
                val d = decl as? RdfResource ?: throw ShapeCompileException("sh:declare value must be a resource: $decl")
                val prefix = (index.objectSingle(d, SHACL.prefixProperty) as? Literal)?.lexical
                    ?: throw ShapeCompileException("sh:declare $d must have exactly one sh:prefix literal")
                val namespace = (index.objectSingle(d, SHACL.namespaceProperty) as? Literal)?.lexical
                    ?: throw ShapeCompileException("sh:declare $d must have exactly one sh:namespace literal")
                val existing = out.putIfAbsent(prefix, namespace)
                if (existing != null && existing != namespace) {
                    throw ShapeCompileException("Conflicting sh:declare for prefix '$prefix': <$existing> vs <$namespace>")
                }
            }
            queue.addAll(index.objects(holder, OWL.imports))
        }
        return out
    }

    /**
     * SHACL-SPARQL pre-binding restrictions: queries must not use MINUS, SERVICE or VALUES, must not re-bind a
     * pre-bound variable with `AS`, and sub-queries must project every pre-bound variable the query references.
     */
    private fun validatePreBinding(stripped: String, node: RdfResource) {
        fun fail(what: String): Nothing =
            throw ShapeCompileException("SPARQL constraint $node uses $what, which is not supported with pre-bound variables")
        for (keyword in listOf("MINUS", "SERVICE", "VALUES")) {
            if (Regex("\\b$keyword\\b", RegexOption.IGNORE_CASE).containsMatchIn(stripped)) fail(keyword)
        }
        if (Regex("\\bAS\\s*[?\$](this|currentShape|shapesGraph)\\b", RegexOption.IGNORE_CASE).containsMatchIn(stripped)) {
            fail("AS to re-bind a pre-bound variable")
        }
        val used = preBoundNames.filter { variableRegex(it).containsMatchIn(stripped) }
        val selects = Regex("\\bSELECT\\b", RegexOption.IGNORE_CASE).findAll(stripped).toList()
        for (m in selects.drop(1)) {
            val rest = stripped.substring(m.range.last + 1)
            val end = Regex("\\bWHERE\\b|\\{", RegexOption.IGNORE_CASE).find(rest)?.range?.first ?: rest.length
            val projection = rest.substring(0, end)
            for (v in used) {
                if (!variableRegex(v).containsMatchIn(projection)) fail("a sub-query that does not project \$$v")
            }
        }
    }

    private val iriRef = java.util.regex.Pattern.compile("<[^<>\"{}|^`\\\\\\u0000-\\u0020]*>")

    /** Blanks out string literals, IRI references and comments so keyword checks only see query syntax. */
    private fun stripSparqlLexicalNoise(q: String): String {
        val sb = StringBuilder(q.length)
        var i = 0
        val matcher = iriRef.matcher(q)
        while (i < q.length) {
            val c = q[i]
            when {
                q.startsWith("\"\"\"", i) || q.startsWith("'''", i) -> {
                    val end = q.indexOf(q.substring(i, i + 3), i + 3)
                    i = if (end < 0) q.length else end + 3
                    sb.append(" \"\" ")
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < q.length && q[j] != c && q[j] != '\n') {
                        if (q[j] == '\\') j++
                        j++
                    }
                    i = j + 1
                    sb.append(" \"\" ")
                }
                c == '<' && matcher.region(i, q.length).lookingAt() -> {
                    i = matcher.end()
                    sb.append(" <> ")
                }
                c == '#' -> {
                    val end = q.indexOf('\n', i)
                    i = if (end < 0) q.length else end
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    /** Renders a SHACL property path as a SPARQL 1.1 property path (for `$PATH` substitution). */
    internal fun renderSparqlPath(path: ShaclPath): String =
        when (path) {
            is ShaclPath.Predicate -> "<${path.iri.value}>"
            is ShaclPath.Inverse -> "^(${renderSparqlPath(path.child)})"
            is ShaclPath.Sequence -> path.segments.joinToString("/", "(", ")") { renderSparqlPath(it) }
            is ShaclPath.Alternative -> path.options.joinToString("|", "(", ")") { renderSparqlPath(it) }
            is ShaclPath.ZeroOrMore -> "(${renderSparqlPath(path.child)})*"
            is ShaclPath.OneOrMore -> "(${renderSparqlPath(path.child)})+"
            is ShaclPath.ZeroOrOne -> "(${renderSparqlPath(path.child)})?"
        }

    // --- helpers --------------------------------------------------------------------------------------------------

    private fun isParameterTripleDeactivated(subject: RdfResource, pred: Iri, obj: RdfTerm, index: ShapeGraphIndex): Boolean {
        val claim = RdfTriple(subject, pred, obj)
        return index.reifiersForClaim(claim).any { r ->
            index.objects(r, SHACL.deactivated).any { isLexicallyTrue(it) }
        }
    }

    private fun annotatedConstraintSeverity(
        subject: RdfResource,
        pred: Iri,
        obj: RdfTerm,
        index: ShapeGraphIndex,
    ): ViolationSeverity? {
        val claim = RdfTriple(subject, pred, obj)
        for (r in index.reifiersForClaim(claim)) {
            val iris = index.objects(r, SHACL.severity).filterIsInstance<Iri>()
            if (iris.isNotEmpty()) return parseSeverityValues(iris, ViolationSeverity.VIOLATION).level
        }
        return null
    }

    private fun annotatedConstraintMessages(subject: RdfResource, pred: Iri, obj: RdfTerm, index: ShapeGraphIndex): List<Literal> =
        index.reifiersForClaim(RdfTriple(subject, pred, obj)).flatMap { r -> index.objects(r, SHACL.message).filterIsInstance<Literal>() }

    private fun parseShapeRefList(head: RdfTerm, index: ShapeGraphIndex): List<RdfResource> =
        index.parseRdfList(head).map {
            it as? RdfResource ?: throw ShapeCompileException("Shape list entries must be IRIs or blank nodes: $it")
        }

    private fun ensureShapeTermAllowed(term: RdfTerm, config: ValidationConfig) {
        if (term is TripleTerm && !config.allowTripleTermsInShapeParameters) {
            throw ShapeCompileException(
                "Triple-term value in shape parameter requires ValidationConfig.allowTripleTermsInShapeParameters=true (architecture §5.1 P1b)",
            )
        }
    }

    private fun isDeactivated(subject: RdfResource, index: ShapeGraphIndex): Boolean =
        index.objects(subject, SHACL.deactivated).any { isLexicallyTrue(it) }

    private data class SeverityParse(val level: ViolationSeverity, val customIri: Iri?)

    private fun parseSeverityValues(iris: List<Iri>, default: ViolationSeverity): SeverityParse {
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

    private fun literalString(term: RdfTerm): String? =
        when (term) {
            is Literal -> term.lexical
            else -> null
        }

    private fun parseNonNegativeInt(term: RdfTerm, role: String): Int {
        val lit = term as? TypedLiteral ?: throw ShapeCompileException("$role expects xsd:integer, got $term")
        if (lit.datatype != XSD.integer && lit.datatype != XSD.nonNegativeInteger && lit.datatype != XSD.long) {
            throw ShapeCompileException("$role expects integer datatype, got ${lit.datatype}")
        }
        val v = lit.lexical.toIntOrNull() ?: throw ShapeCompileException("$role invalid lexical ${lit.lexical}")
        if (v < 0) throw ShapeCompileException("$role must be non-negative")
        return v
    }
}

/**
 * Compiles `sh:pattern` with `sh:flags` into a JVM [Regex].
 *
 * Supported flags follow XPath `fn:matches`: `i` (case-insensitive, Unicode-aware), `m` (multi-line), `s` (dot
 * matches all), `x` (whitespace outside character classes is removed from the pattern) and `q` (the pattern is
 * matched literally). Unknown flags or syntactically invalid patterns raise [ShapeCompileException].
 *
 * Note: patterns are evaluated with `java.util.regex`, which accepts a superset of XML Schema/XPath regular
 * expressions (e.g. lookarounds, possessive quantifiers) and differs in some corner cases (character class
 * subtraction `[a-z-[aeiou]]`, `\i`/`\c` escapes are not supported). Portable shapes should stick to the common
 * subset.
 */
internal fun compileShaclPattern(pattern: String, flags: String?): Regex {
    val options = mutableSetOf<RegexOption>()
    var quote = false
    var extended = false
    flags?.forEach { c ->
        when (c) {
            'i' -> options.add(RegexOption.IGNORE_CASE)
            'm' -> options.add(RegexOption.MULTILINE)
            's' -> options.add(RegexOption.DOT_MATCHES_ALL)
            'x' -> extended = true
            'q' -> quote = true
            else -> throw ShapeCompileException("Unsupported sh:flags character '$c' in \"$flags\"")
        }
    }
    val source =
        when {
            quote -> Regex.escape(pattern)
            extended -> stripXPathRegexWhitespace(pattern)
            else -> pattern
        }
    return try {
        Regex(source, options)
    } catch (e: java.util.regex.PatternSyntaxException) {
        throw ShapeCompileException("Invalid sh:pattern \"$pattern\": ${e.description}", e)
    }
}

private fun stripXPathRegexWhitespace(pattern: String): String =
    buildString {
        var inClass = false
        var escaped = false
        for (ch in pattern) {
            when {
                escaped -> { append(ch); escaped = false }
                ch == '\\' -> { append(ch); escaped = true }
                inClass -> { if (ch == ']') inClass = false; append(ch) }
                ch == '[' -> { inClass = true; append(ch) }
                ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' -> Unit
                else -> append(ch)
            }
        }
    }

internal fun constraintStub(type: ConstraintType, pathIri: Iri? = null, params: Map<String, Any> = emptyMap()) =
    ShaclConstraint(
        constraintType = type,
        path = pathIri?.value,
        parameters = params,
    )
