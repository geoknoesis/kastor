package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.ValidationProfile
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
    data class Datatype(val allowed: List<Iri>, val severityOverride: ViolationSeverity? = null) : PropertyConstraint()
    data class Class(val iri: Iri) : PropertyConstraint()
    /** `sh:class` with an RDF list of classes: value must satisfy at least one entry (OR). */
    data class ClassAnyOf(val options: List<Iri>) : PropertyConstraint()
    /** Focus/value satisfies **any** listed node kind (`sh:nodeKind` lists). */
    data class NodeKind(val kinds: List<Iri>) : PropertyConstraint()
    data class Pattern(val pattern: String, val flags: String?) : PropertyConstraint()
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
    data class Qualified(
        val shape: RdfResource,
        val min: Int?,
        val max: Int?,
        val disjoint: Boolean,
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
    data class Sparql(val constraintNode: RdfResource) : PropertyConstraint()
    /** SHACL 1.2: validate resources that reify triples matching this property shape. */
    data class ReifierShape(val nestedShape: RdfResource) : PropertyConstraint()
    /** SHACL 1.2: asserted triples matching the path must have at least one `rdf:reifies` reifier. */
    data class ReificationRequired(val required: Boolean) : PropertyConstraint()
}

internal data class CompiledPropertyShape(
    val shapeNode: RdfResource,
    val path: ShaclPath,
    val severity: ViolationSeverity,
    val severityCustomIri: Iri? = null,
    val messages: List<String>,
    val constraints: List<PropertyConstraint>,
    val logicalParts: List<NodeLogicalPart> = emptyList(),
    /** Nested `sh:property` constraints evaluated relative to each value of this shape's path. */
    val nestedPropertyShapes: List<CompiledPropertyShape> = emptyList(),
)

internal data class CompiledNodeShape(
    val shapeNode: RdfResource,
    val targets: Targets,
    /** Inline shapes from `sh:targetWhere` (membership = zero violations validating focus against each). */
    val targetWhereCompiled: List<CompiledNodeShape> = emptyList(),
    val propertyShapes: List<CompiledPropertyShape>,
    val severity: ViolationSeverity,
    val severityCustomIri: Iri? = null,
    val messages: List<String>,
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
)

internal data class CompiledShapeGraph(
    val shapesByNode: Map<RdfResource, CompiledNodeShape>,
    val orderedNodeShapes: List<CompiledNodeShape>,
)

internal object ShapesCompiler {

    private enum class ConstraintFlavor {
        PROPERTY_SHAPE,
        NODE_SCALAR,
    }

    fun compile(shapesTriples: List<RdfTriple>, config: ValidationConfig): CompiledShapeGraph {
        val index = ShapeGraphIndex(shapesTriples)
        val nodeShapeSubjects = findNodeShapes(shapesTriples)
        val compiled = mutableListOf<CompiledNodeShape>()
        val byNode = mutableMapOf<RdfResource, CompiledNodeShape>()
        for (subject in nodeShapeSubjects.sortedWith(resourceOrdering)) {
            if (isDeactivated(subject, index)) continue
            val cn = compileNodeShape(subject, index, config)
            compiled.add(cn)
            byNode[subject] = cn
        }
        return CompiledShapeGraph(byNode, compiled)
    }

    private val resourceOrdering: Comparator<RdfResource> = Comparator { a, b ->
        when {
            a is Iri && b is Iri -> a.value.compareTo(b.value)
            a is BlankNode && b is BlankNode -> a.id.compareTo(b.id)
            a is Iri -> -1
            else -> 1
        }
    }

    private fun findNodeShapes(triples: List<RdfTriple>): List<RdfResource> {
        val set = LinkedHashSet<RdfResource>()
        val index = ShapeGraphIndex(triples)
        for (t in triples) {
            if (t.predicate == RDF.type &&
                (t.obj == SHACL.NodeShape || t.obj == SHACL.ShapeClass || t.obj == SHACL.Shape)
            ) {
                set.add(t.subject)
            }
        }
        // Implicit node shapes: subjects with targets and parameters but no `rdf:type` (W3C misc/severity-002, etc.).
        for (t in triples) {
            val s = t.subject as? RdfResource ?: continue
            if (s in set) continue
            if (index.objects(s, SHACL.path).isNotEmpty()) continue
            if (!hasShapeTargets(s, index)) continue
            if (looksLikeImplicitNodeShape(s, index)) set.add(s)
        }
        // Standalone shapes declared with `sh:path` plus targets (W3C path-* manifests); covers PropertyShape
        // roots whether or not an explicit `rdf:type` triple is present.
        for (t in triples) {
            val s = t.subject as? RdfResource ?: continue
            if (s in set) continue
            if (index.objects(s, SHACL.path).isEmpty()) continue
            if (!hasShapeTargets(s, index)) continue
            set.add(s)
        }
        return set.toList()
    }

    private fun hasShapeTargets(s: RdfResource, index: ShapeGraphIndex): Boolean =
        index.objects(s, SHACL.targetClass).isNotEmpty() ||
            index.objects(s, SHACL.targetNode).isNotEmpty() ||
            index.objects(s, SHACL.targetSubjectsOf).isNotEmpty() ||
            index.objects(s, SHACL.targetObjectsOf).isNotEmpty() ||
            index.objects(s, SHACL.targetWhere).isNotEmpty()

    private fun looksLikeImplicitNodeShape(s: RdfResource, index: ShapeGraphIndex): Boolean =
        index.objects(s, SHACL.`property`).isNotEmpty() ||
            index.objects(s, SHACL.node).isNotEmpty() ||
            index.objects(s, SHACL.`and`).isNotEmpty() ||
            index.objects(s, SHACL.`or`).isNotEmpty() ||
            index.objects(s, SHACL.xone).isNotEmpty() ||
            index.objects(s, SHACL.`not`).isNotEmpty() ||
            index.objects(s, SHACL.closed).isNotEmpty() ||
            index.objects(s, SHACL.ignoredProperties).isNotEmpty() ||
            index.objects(s, SHACL.datatype).isNotEmpty() ||
            index.objects(s, SHACL.nodeByExpression).isNotEmpty() ||
            index.objects(s, SHACL.nodeKind).isNotEmpty() ||
            index.objects(s, SHACL.`class`).isNotEmpty() ||
            index.objects(s, SHACL.pattern).isNotEmpty() ||
            index.objects(s, SHACL.minCount).isNotEmpty() ||
            index.objects(s, SHACL.maxCount).isNotEmpty() ||
            index.objects(s, SHACL.uniqueValuesFor).isNotEmpty() ||
            index.objects(s, SHACL.severity).isNotEmpty() ||
            index.objects(s, SHACL.targetWhere).isNotEmpty()

    private fun compileNodeShape(subject: RdfResource, index: ShapeGraphIndex, config: ValidationConfig): CompiledNodeShape {
        val explicitTargetClasses = index.objects(subject, SHACL.targetClass).filterIsInstance<Iri>()
        val implicitClassTargets =
            if (subject is Iri && index.objects(subject, RDF.type).contains(RDFS.Class)) {
                listOf(subject)
            } else {
                emptyList()
            }
        val implicitShapeClassTargets =
            if (subject is Iri && index.objects(subject, RDF.type).contains(SHACL.ShapeClass)) {
                listOf(subject)
            } else {
                emptyList()
            }
        val targets = Targets(
            targetClasses = (explicitTargetClasses + implicitClassTargets + implicitShapeClassTargets).distinct(),
            targetNodes = index.objects(subject, SHACL.targetNode).onEach { ensureShapeTermAllowed(it, config) },
            targetSubjectsOf = index.objects(subject, SHACL.targetSubjectsOf).filterIsInstance<Iri>(),
            targetObjectsOf = index.objects(subject, SHACL.targetObjectsOf).filterIsInstance<Iri>(),
        )

        val nodeSev =
            parseSeverityValues(index.objects(subject, SHACL.severity).filterIsInstance<Iri>(), ViolationSeverity.VIOLATION)

        val linkedPropShapes =
            index.objects(subject, SHACL.`property`)
                .mapNotNull { ps ->
                    val node = ps as? RdfResource ?: return@mapNotNull null
                    if (isDeactivated(node, index)) return@mapNotNull null
                    if (isParameterTripleDeactivated(subject, SHACL.`property`, ps, index)) return@mapNotNull null
                    compilePropertyShape(node, index, config, nodeSev.level)
                }

        val parametersOnShapeNode = index.objects(subject, SHACL.path).isNotEmpty()
        val selfAsPropertyShape =
            if (parametersOnShapeNode) {
                compilePropertyShape(subject, index, config, nodeSev.level)
            } else {
                null
            }
        val propShapes = linkedPropShapes + listOfNotNull(selfAsPropertyShape)

        val severity = nodeSev.level
        val severityCustomIri = nodeSev.customIri
        val messages = index.objects(subject, SHACL.message).mapNotNull { literalString(it) }

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

        val logicalParts = mutableListOf<NodeLogicalPart>()
        if (!parametersOnShapeNode) {
            index.objects(subject, SHACL.`and`).forEach { head ->
                logicalParts.add(NodeLogicalPart.And(parseShapeRefList(head, index)))
            }
            index.objects(subject, SHACL.`or`).forEach { head ->
                logicalParts.add(NodeLogicalPart.Or(parseShapeRefList(head, index)))
            }
            index.objects(subject, SHACL.xone).forEach { head ->
                logicalParts.add(NodeLogicalPart.Xone(parseShapeRefList(head, index)))
            }
            index.objects(subject, SHACL.`not`).forEach { n ->
                val ref = n as? RdfResource ?: throw ShapeCompileException("sh:not expects a shape reference, got $n")
                logicalParts.add(NodeLogicalPart.Not(ref))
            }
        }

        val nodeRefsOnShape =
            if (parametersOnShapeNode) {
                emptyList()
            } else {
                index.objects(subject, SHACL.node).filterIsInstance<RdfResource>()
            }
        val nodeByExpressionRefs =
            if (parametersOnShapeNode) {
                emptyList()
            } else {
                index.objects(subject, SHACL.nodeByExpression).filterIsInstance<RdfResource>()
            }

        val nodeConstraints =
            if (parametersOnShapeNode) {
                emptyList()
            } else {
                compileConstraints(subject, index, config, ConstraintFlavor.NODE_SCALAR)
            }

        val uniqueValuesForProps =
            if (parametersOnShapeNode) {
                emptyList()
            } else {
                parseUniqueValuesFor(subject, index)
            }

        val targetWhereCompiled =
            if (parametersOnShapeNode) {
                emptyList()
            } else {
                index.objects(subject, SHACL.targetWhere).mapNotNull { tw ->
                    val node = tw as? RdfResource ?: return@mapNotNull null
                    tryCompileInlineNodeShape(node, index, config)
                }
            }

        detectUnsupported(subject, index, config)

        return CompiledNodeShape(
            shapeNode = subject,
            targets = targets,
            targetWhereCompiled = targetWhereCompiled,
            propertyShapes = propShapes,
            severity = severity,
            severityCustomIri = severityCustomIri,
            messages = messages,
            closed = closedMode,
            ignoredProperties = ignored,
            logicalParts = logicalParts,
            nodeRefs = nodeRefsOnShape,
            nodeByExpressionRefs = nodeByExpressionRefs,
            nodeConstraints = nodeConstraints,
            uniqueValuesForProps = uniqueValuesForProps,
        )
    }

    private fun compileConstraints(
        subject: RdfResource,
        index: ShapeGraphIndex,
        config: ValidationConfig,
        flavor: ConstraintFlavor,
    ): MutableList<PropertyConstraint> {
        val constraints = mutableListOf<PropertyConstraint>()
        if (flavor == ConstraintFlavor.PROPERTY_SHAPE) {
            index.objects(subject, SHACL.minCount).singleOrNull()?.let {
                constraints.add(PropertyConstraint.MinCount(parseNonNegativeInt(it, "sh:minCount")))
            }
            index.objects(subject, SHACL.maxCount).singleOrNull()?.let {
                constraints.add(PropertyConstraint.MaxCount(parseNonNegativeInt(it, "sh:maxCount")))
            }
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
                constraints.add(PropertyConstraint.Datatype(allowed, override))
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
        index.objects(subject, SHACL.pattern).singleOrNull()?.let { p ->
            val pat = literalString(p) ?: throw ShapeCompileException("sh:pattern must be a string literal")
            val flags = index.objects(subject, SHACL.flags).singleOrNull()?.let { literalString(it) }
            constraints.add(PropertyConstraint.Pattern(pat, flags))
        }
        index.objects(subject, SHACL.minLength).singleOrNull()?.let {
            constraints.add(PropertyConstraint.MinLength(parseNonNegativeInt(it, "sh:minLength")))
        }
        index.objects(subject, SHACL.maxLength).singleOrNull()?.let {
            constraints.add(PropertyConstraint.MaxLength(parseNonNegativeInt(it, "sh:maxLength")))
        }
        index.objects(subject, SHACL.`in`).singleOrNull()?.let { head ->
            val terms = index.parseRdfList(head)
            terms.forEach { ensureShapeTermAllowed(it, config) }
            constraints.add(PropertyConstraint.In(terms))
        }
        index.objects(subject, SHACL.hasValue).singleOrNull()?.let {
            ensureShapeTermAllowed(it, config)
            constraints.add(PropertyConstraint.HasValue(it))
        }
        index.objects(subject, SHACL.languageIn).singleOrNull()?.let { head ->
            val langs = index.parseRdfList(head).map {
                literalString(it) ?: throw ShapeCompileException("sh:languageIn list must be string literals")
            }
            constraints.add(PropertyConstraint.LanguageIn(langs))
        }
        index.objects(subject, SHACL.uniqueLang).singleOrNull()?.let {
            constraints.add(PropertyConstraint.UniqueLang(isLexicallyTrue(it)))
        }
        index.objects(subject, SHACL.minListLength).singleOrNull()?.let {
            constraints.add(PropertyConstraint.MinListLength(parseNonNegativeInt(it, "sh:minListLength")))
        }
        index.objects(subject, SHACL.maxListLength).singleOrNull()?.let {
            constraints.add(PropertyConstraint.MaxListLength(parseNonNegativeInt(it, "sh:maxListLength")))
        }
        index.objects(subject, SHACL.memberShape).filterIsInstance<RdfResource>().singleOrNull()?.let {
            constraints.add(PropertyConstraint.MemberShape(it))
        }
        index.objects(subject, SHACL.uniqueMembers).singleOrNull()?.let {
            constraints.add(PropertyConstraint.UniqueMembers(isLexicallyTrue(it)))
        }
        index.objects(subject, SHACL.singleLine).singleOrNull()?.let {
            constraints.add(PropertyConstraint.SingleLine(isLexicallyTrue(it)))
        }
        index.objects(subject, SHACL.rootClass).singleOrNull()?.let { rc ->
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
        index.objects(subject, SHACL.subsetOf).singleOrNull()?.let {
            constraints.add(PropertyConstraint.SubsetOfPath(ShaclPathParser.parse(it, index)))
        }
        index.objects(subject, SHACL.someValue).filterIsInstance<RdfResource>().singleOrNull()?.let {
            constraints.add(PropertyConstraint.SomeValue(it))
        }
        index.objects(subject, SHACL.shape).filterIsInstance<RdfResource>().forEach {
            constraints.add(PropertyConstraint.Shape(it))
        }
        if (flavor == ConstraintFlavor.PROPERTY_SHAPE) {
            index.objects(subject, SHACL.node).filterIsInstance<RdfResource>().forEach {
                constraints.add(PropertyConstraint.Node(it))
            }
            index.objects(subject, SHACL.lessThan).singleOrNull()?.let {
                constraints.add(PropertyConstraint.LessThanPath(ShaclPathParser.parse(it, index)))
            }
            index.objects(subject, SHACL.lessThanOrEquals).singleOrNull()?.let {
                constraints.add(PropertyConstraint.LessThanOrEqualsPath(ShaclPathParser.parse(it, index)))
            }
        }
        index.objects(subject, SHACL.equals).singleOrNull()?.let {
            constraints.add(PropertyConstraint.EqualsPath(ShaclPathParser.parse(it, index)))
        }
        index.objects(subject, SHACL.disjoint).singleOrNull()?.let {
            constraints.add(PropertyConstraint.DisjointPath(ShaclPathParser.parse(it, index)))
        }
        index.objects(subject, SHACL.minInclusive).singleOrNull()?.let {
            constraints.add(PropertyConstraint.MinInclusive(it))
        }
        index.objects(subject, SHACL.maxInclusive).singleOrNull()?.let {
            constraints.add(PropertyConstraint.MaxInclusive(it))
        }
        index.objects(subject, SHACL.minExclusive).singleOrNull()?.let {
            constraints.add(PropertyConstraint.MinExclusive(it))
        }
        index.objects(subject, SHACL.maxExclusive).singleOrNull()?.let {
            constraints.add(PropertyConstraint.MaxExclusive(it))
        }
        if (flavor == ConstraintFlavor.PROPERTY_SHAPE) {
            index.objects(subject, SHACL.qualifiedValueShape).filterIsInstance<RdfResource>().singleOrNull()?.let { qvs ->
                val min = index.objects(subject, SHACL.qualifiedMinCount).singleOrNull()?.let { parseNonNegativeInt(it, "sh:qualifiedMinCount") }
                val max = index.objects(subject, SHACL.qualifiedMaxCount).singleOrNull()?.let { parseNonNegativeInt(it, "sh:qualifiedMaxCount") }
                val disjoint = index.objects(subject, SHACL.qualifiedValueShapesDisjoint).any { isLexicallyTrue(it) }
                constraints.add(PropertyConstraint.Qualified(qvs, min, max, disjoint))
            }
            index.objects(subject, SHACL.reifierShape).filterIsInstance<RdfResource>().singleOrNull()?.let {
                constraints.add(PropertyConstraint.ReifierShape(it))
            }
            index.objects(subject, SHACL.reificationRequired).singleOrNull()?.let {
                constraints.add(PropertyConstraint.ReificationRequired(isLexicallyTrue(it)))
            }
        }
        index.objects(subject, SHACL.sparql).filterIsInstance<RdfResource>().forEach {
            constraints.add(PropertyConstraint.Sparql(it))
        }
        return constraints
    }

    private fun parseUniqueValuesFor(subject: RdfResource, index: ShapeGraphIndex): List<Iri> {
        val raw = index.objects(subject, SHACL.uniqueValuesFor).singleOrNull() ?: return emptyList()
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
        inheritedSeverity: ViolationSeverity,
    ): CompiledPropertyShape {
        val pathTerm = index.objects(ps, SHACL.path).singleOrNull()
            ?: throw ShapeCompileException("Property shape $ps must have exactly one sh:path")
        val path = ShaclPathParser.parse(pathTerm, index)

        val constraints = compileConstraints(ps, index, config, ConstraintFlavor.PROPERTY_SHAPE)

        val logicalParts = mutableListOf<NodeLogicalPart>()
        index.objects(ps, SHACL.`and`).forEach { head ->
            logicalParts.add(NodeLogicalPart.And(parseShapeRefList(head, index)))
        }
        index.objects(ps, SHACL.`or`).forEach { head ->
            logicalParts.add(NodeLogicalPart.Or(parseShapeRefList(head, index)))
        }
        index.objects(ps, SHACL.xone).forEach { head ->
            logicalParts.add(NodeLogicalPart.Xone(parseShapeRefList(head, index)))
        }
        index.objects(ps, SHACL.`not`).forEach { n ->
            val ref = n as? RdfResource ?: throw ShapeCompileException("sh:not expects a shape reference, got $n")
            logicalParts.add(NodeLogicalPart.Not(ref))
        }

        val nestedPropertyShapes =
            index.objects(ps, SHACL.`property`)
                .mapNotNull { child ->
                    val node = child as? RdfResource ?: return@mapNotNull null
                    if (isDeactivated(node, index)) return@mapNotNull null
                    if (isParameterTripleDeactivated(ps, SHACL.`property`, child, index)) return@mapNotNull null
                    compilePropertyShape(node, index, config, inheritedSeverity)
                }

        detectUnsupported(ps, index, config)

        val psSev = parseSeverityValues(index.objects(ps, SHACL.severity).filterIsInstance<Iri>(), inheritedSeverity)
        val messages = index.objects(ps, SHACL.message).mapNotNull { literalString(it) }

        return CompiledPropertyShape(
            shapeNode = ps,
            path = path,
            severity = psSev.level,
            severityCustomIri = psSev.customIri,
            messages = messages,
            constraints = constraints,
            logicalParts = logicalParts,
            nestedPropertyShapes = nestedPropertyShapes,
        )
    }

    /**
     * `sh:and` / `sh:or` / `sh:xone` / `sh:not` operands may reference a standalone `sh:PropertyShape`
     * that is not attached via `sh:property` on a node shape.
     */
    internal fun tryCompilePropertyShape(
        ref: RdfResource,
        index: ShapeGraphIndex,
        config: ValidationConfig,
    ): CompiledPropertyShape? {
        if (index.objects(ref, SHACL.path).isEmpty()) return null
        if (isDeactivated(ref, index)) return null
        return try {
            val sev =
                parseSeverityValues(index.objects(ref, SHACL.severity).filterIsInstance<Iri>(), ViolationSeverity.VIOLATION)
            compilePropertyShape(ref, index, config, sev.level)
        } catch (_: ShapeCompileException) {
            null
        }
    }

    /**
     * Operands of `sh:and` / `sh:or` / etc. are often blank node shapes with `sh:property` but no
     * `rdf:type sh:NodeShape`, so they are absent from [CompiledShapeGraph.shapesByNode].
     */
    internal fun tryCompileInlineNodeShape(
        ref: RdfResource,
        index: ShapeGraphIndex,
        config: ValidationConfig,
    ): CompiledNodeShape? {
        if (isDeactivated(ref, index)) return null
        if (index.objects(ref, SHACL.path).isNotEmpty()) return null
        val cn =
            try {
                compileNodeShape(ref, index, config)
            } catch (_: ShapeCompileException) {
                return null
            }
        return if (isNonVacuousCompiledNodeShape(cn)) cn else null
    }

    private fun isNonVacuousCompiledNodeShape(cn: CompiledNodeShape): Boolean =
        cn.propertyShapes.isNotEmpty() ||
            cn.logicalParts.isNotEmpty() ||
            cn.nodeRefs.isNotEmpty() ||
            cn.nodeByExpressionRefs.isNotEmpty() ||
            cn.nodeConstraints.isNotEmpty() ||
            cn.closed != ClosedMode.NONE ||
            cn.uniqueValuesForProps.isNotEmpty()

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

    private fun parseShapeRefList(head: RdfTerm, index: ShapeGraphIndex): List<RdfResource> =
        index.parseRdfList(head).map {
            it as? RdfResource ?: throw ShapeCompileException("Shape list entries must be IRIs or blank nodes: $it")
        }

    private fun detectUnsupported(subject: RdfResource, index: ShapeGraphIndex, config: ValidationConfig) {
        // Extension hooks (SHACL-JS, DASH-only constructs, etc.) can throw here under strictMode.
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

internal fun constraintStub(type: ConstraintType, pathIri: Iri? = null, params: Map<String, Any> = emptyMap()) =
    ShaclConstraint(
        constraintType = type,
        path = pathIri?.value,
        parameters = params,
    )
