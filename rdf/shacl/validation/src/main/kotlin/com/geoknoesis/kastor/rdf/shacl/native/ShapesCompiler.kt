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
import com.geoknoesis.kastor.rdf.shacl.UnsupportedFeatureHandling
import com.geoknoesis.kastor.rdf.shacl.SparqlPreBindingRestrictionException
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclFeature
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclFeatureException
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
    /** SHACL 1.2 `sh:nodeByExpression` on a property shape whose value is a shape IRI (a constant node expression). */
    data class NodeByExpression(val nestedShape: RdfResource) : PropertyConstraint()
    data class EqualsPath(val otherPath: ShaclPath) : PropertyConstraint()
    data class DisjointPath(val otherPath: ShaclPath) : PropertyConstraint()
    data class LessThanPath(val otherPath: ShaclPath) : PropertyConstraint()
    data class LessThanOrEqualsPath(val otherPath: ShaclPath) : PropertyConstraint()
    data class MinInclusive(val bound: RdfTerm) : PropertyConstraint()
    data class MaxInclusive(val bound: RdfTerm) : PropertyConstraint()
    data class MinExclusive(val bound: RdfTerm) : PropertyConstraint()
    data class MaxExclusive(val bound: RdfTerm) : PropertyConstraint()
    /**
     * `sh:qualifiedValueShape`. [siblings] are the sibling shapes of SHACL §4.7.3 used when [disjoint] is true: all
     * values of `sh:property/sh:qualifiedValueShape` of the parent shape(s), minus this constraint's own
     * `sh:qualifiedValueShape` value.
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
     * SHACL-SPARQL constraint (`sh:sparql`). [template] holds the query with `sh:prefixes` declarations prepended
     * and `$PATH` substituted; pre-binding is applied by the engine through [SparqlQueryTemplate.bind].
     */
    data class Sparql(
        val constraintNode: RdfResource,
        val template: SparqlQueryTemplate,
        val messages: List<Literal>,
        val severity: ViolationSeverity?,
        val severityCustomIri: Iri?,
    ) : PropertyConstraint() {
        /** Pre-bound variables the query references (`this`, `currentShape`, `shapesGraph`). */
        val preBound: Set<String> get() = template.preBound
    }
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
    /**
     * Predicates allowed by `sh:closed true` (SHACL §4.8.1): the IRI `sh:path` values of **all** `sh:property`
     * values, including deactivated property shapes.
     */
    val closedAllowedPredicates: Set<Iri> = emptySet(),
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
    /**
     * Shapes that can reach themselves through shape references (a strongly connected component of the static
     * shape dependency graph, or a self-reference), mapped to their component id. Only these shapes can recurse
     * over data; all other nested checks are bounded by the static nesting depth of the shapes graph.
     */
    val recursiveComponents: Map<RdfResource, Int> = emptyMap(),
    /** Descriptions of unsupported constructs that were ignored ([UnsupportedFeatureHandling.IGNORE_WITH_WARNING]). */
    val unsupportedFeatureWarnings: List<String> = emptyList(),
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
        val unsupported = detectUnsupportedFeatures(index, nodeShapeSubjects, budget)
        if (unsupported.isNotEmpty() && config.unsupportedFeatures == UnsupportedFeatureHandling.FAIL) {
            throw UnsupportedShaclFeatureException(
                "Unsupported SHACL feature(s) for the native engine: ${unsupported.keys.joinToString("; ")}. " +
                    "Set ValidationConfig.unsupportedFeatures = IGNORE_WITH_WARNING to skip these constructs.",
                unsupported.values.toSet(),
            )
        }
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
        return CompiledShapeGraph(
            byNode, compiled, refNodes, refProps, index,
            recursiveComponents(refNodes, refProps, budget),
            unsupported.keys.map { "Unsupported SHACL feature ignored: $it" },
        )
    }

    /** Static shape dependency graph → shapes that belong to a recursive strongly connected component. */
    private fun recursiveComponents(
        refNodes: Map<RdfResource, CompiledNodeShape>,
        refProps: Map<RdfResource, CompiledPropertyShape>,
        budget: ValidationBudget,
    ): Map<RdfResource, Int> {
        val successors = HashMap<RdfResource, List<RdfResource>>()
        for ((node, ps) in refProps) {
            successors[node] = ps.constraints.flatMap { constraintRefs(it) } +
                ps.logicalParts.flatMap { it.operandRefs() } + ps.nestedPropertyShapes.map { it.shapeNode }
        }
        // A shape node that is also a property shape is always checked as a property shape (see conformance checks).
        for ((node, cn) in refNodes) {
            if (node in refProps) continue
            successors[node] = cn.nodeRefs + cn.nodeByExpressionRefs + cn.logicalParts.flatMap { it.operandRefs() } +
                cn.nodeConstraints.flatMap { constraintRefs(it) } + cn.propertyShapes.map { it.shapeNode }
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

    // --- unsupported features -------------------------------------------------------------------------------------

    private val shValues = Iri(SHACL.namespace + "values")
    private val shExpression = Iri(SHACL.namespace + "expression")
    private val shTarget = Iri(SHACL.namespace + "target")
    private val shSparqlExpr = Iri(SHACL.namespace + "sparqlExpr")
    private val shOptional = Iri(SHACL.namespace + "optional")
    private val shBodyExpression = Iri(SHACL.namespace + "bodyExpression")
    private val shShapesGraph = Iri(SHACL.namespace + "ShapesGraph")
    private val validatorPredicates = setOf(
        Iri(SHACL.namespace + "validator"), Iri(SHACL.namespace + "nodeValidator"), Iri(SHACL.namespace + "propertyValidator"),
    )

    /** True for SHACL 1.2 SPARQL node expressions (`sh:SelectExpression`, `sh:SPARQLExprExpression`, `sh:select`, `sh:sparqlExpr`). */
    private fun isNodeExpression(term: RdfTerm, index: ShapeGraphIndex): Boolean {
        val node = term as? RdfResource ?: return false
        val types = index.objects(node, RDF.type)
        return SHACL.SelectExpression in types || SHACL.SPARQLExprExpression in types ||
            listOf(SHACL.selectExpression, SHACL.exprExpression, SHACL.select, shSparqlExpr).any { index.objects(node, it).isNotEmpty() }
    }

    /**
     * A blank node `sh:targetNode` value is a node expression when its own triples are expression syntax: SHACL
     * vocabulary (`sh:path`, `sh:select`, a SHACL type…), an RDF list, or a single function call `[ ex:fn ( … ) ]`.
     * A blank node that merely has data triples (shapes and data sharing one graph) is a plain target node.
     */
    private fun isTargetNodeExpression(term: RdfTerm, index: ShapeGraphIndex): Boolean {
        if (term !is BlankNode) return false
        val predicates = index.predicates(term)
        if (predicates.isEmpty()) return false
        fun inShaclNamespace(t: RdfTerm) = t is Iri && t.value.startsWith(SHACL.namespace)
        if (predicates.any { inShaclNamespace(it) || it == RDF.first || it == RDF.rest }) return true
        if (index.objects(term, RDF.type).any { inShaclNamespace(it) }) return true
        val call = predicates.singleOrNull() ?: return false
        val argument = index.objects(term, call).singleOrNull() ?: return false
        return argument == RDF.nil || (argument is BlankNode && index.objects(argument, RDF.first).isNotEmpty())
    }

    private val shapeReferencePredicates: List<Iri> by lazy {
        listOf(
            SHACL.`property`, SHACL.node, SHACL.`not`, SHACL.qualifiedValueShape, SHACL.someValue, SHACL.memberShape,
            SHACL.shape, SHACL.reifierShape, SHACL.nodeByExpression, SHACL.targetWhere,
        )
    }

    /** Declared and implicit shapes plus every node reachable from them through shape-valued parameters. */
    private fun shapeReachableNodes(index: ShapeGraphIndex, nodeShapes: List<RdfResource>, budget: ValidationBudget): Set<RdfResource> {
        val out = LinkedHashSet<RdfResource>()
        val pending = ArrayDeque<RdfResource>()
        fun visit(term: RdfTerm) {
            if (term is RdfResource && out.add(term)) pending.add(term)
        }
        nodeShapes.forEach(::visit)
        index.subjects(RDF.type, SHACL.PropertyShape).forEach(::visit)
        while (pending.isNotEmpty()) {
            budget.tick("feature detection")
            val shape = pending.removeFirst()
            for (p in shapeReferencePredicates) index.objects(shape, p).forEach(::visit)
            for (p in listOf(SHACL.`and`, SHACL.`or`, SHACL.xone)) {
                // Malformed lists are reported by the compiler itself.
                index.objects(shape, p).forEach { head -> runCatching { index.parseRdfList(head) }.getOrDefault(emptyList()).forEach(::visit) }
            }
        }
        return out
    }

    /**
     * Recognised SHACL constructs the native engine cannot evaluate. They are reported instead of silently
     * compiling to something that always conforms (or targets every node): SHACL-SPARQL constraint components that
     * some shape uses, SHACL 1.2 node expressions (`sh:values`, `sh:expression`, SPARQL expressions as
     * `sh:targetWhere` / `sh:targetNode` values, computed `sh:nodeByExpression` values) and `sh:target`.
     * Node-expression positions are only inspected on shape-reachable nodes, so data triples that share the shapes
     * graph (`validate(g, g)`, discovered shapes graphs) are never mistaken for expressions.
     */
    private fun detectUnsupportedFeatures(
        index: ShapeGraphIndex,
        nodeShapes: List<RdfResource>,
        budget: ValidationBudget,
    ): Map<String, UnsupportedShaclFeature> {
        val out = LinkedHashMap<String, UnsupportedShaclFeature>()
        val components = LinkedHashSet<RdfResource>()
        val functions = LinkedHashSet<Iri>()
        val shapes = shapeReachableNodes(index, nodeShapes, budget)
        fun expression(term: RdfTerm) =
            if (isNodeExpression(term, index)) UnsupportedShaclFeature.SPARQL_NODE_EXPRESSION else UnsupportedShaclFeature.NODE_EXPRESSION
        for (t in index.triples) {
            budget.tick("feature detection")
            when {
                t.predicate == shBodyExpression && t.subject is Iri -> functions.add(t.subject as Iri)
                t.predicate in validatorPredicates -> components.add(t.subject)
                t.predicate == RDF.type && t.obj != SHACL.NodeShape && index.isInstanceOf(t.subject, SHACL.ConstraintComponent) ->
                    components.add(t.subject)
                t.subject !in shapes -> Unit
                t.predicate == shValues -> out["sh:values node expression on ${t.subject}"] = expression(t.obj)
                t.predicate == shExpression -> out["sh:expression constraint on ${t.subject}"] = expression(t.obj)
                t.predicate == shTarget ->
                    out["sh:target (SPARQL-based or custom target) on ${t.subject}"] = UnsupportedShaclFeature.CUSTOM_TARGET
                t.predicate == SHACL.targetWhere && isNodeExpression(t.obj, index) ->
                    out["sh:targetWhere with a SPARQL node expression on ${t.subject}"] = UnsupportedShaclFeature.SPARQL_NODE_EXPRESSION
                t.predicate == SHACL.targetNode && isTargetNodeExpression(t.obj, index) ->
                    out["sh:targetNode with a node expression on ${t.subject}"] = expression(t.obj)
                t.predicate == SHACL.nodeByExpression && t.obj is BlankNode ->
                    out["sh:nodeByExpression with a computed node expression on ${t.subject}"] = expression(t.obj)
            }
        }
        for (component in components) {
            if (constraintComponentUsed(component, index, shapes)) {
                out["SPARQL-based constraint component $component (sh:validator / sh:nodeValidator / sh:propertyValidator)"] =
                    UnsupportedShaclFeature.SPARQL_CONSTRAINT_COMPONENT
            }
        }
        if (functions.isNotEmpty()) {
            // A declared function library is harmless; calling one of its functions from SPARQL is not evaluable.
            val sparqlTexts = index.triples
                .filter { it.predicate == SHACL.select || it.predicate == SHACL.ask || it.predicate == shSparqlExpr }
                .mapNotNull { (it.obj as? Literal)?.lexical }
            for (function in functions) {
                val localName = function.value.substringAfterLast('#').substringAfterLast('/')
                val call = Regex("(?<![A-Za-z0-9_])" + Regex.escape(localName) + "[ ]*[(]")
                if (sparqlTexts.any { it.contains(function.value) || (localName.isNotEmpty() && call.containsMatchIn(it)) }) {
                    out["SHACL 1.2 function $function (sh:bodyExpression) called from a SPARQL query"] = UnsupportedShaclFeature.SHACL_FUNCTION
                }
            }
        }
        return out
    }

    /** Whether some shape node has values for every mandatory parameter of [component]. */
    private fun constraintComponentUsed(component: RdfResource, index: ShapeGraphIndex, shapes: Set<RdfResource>): Boolean {
        val parameters = index.objects(component, SHACL.parameter).filterIsInstance<RdfResource>()
        val mandatory = parameters.mapNotNull { parameter ->
            if (index.objects(parameter, shOptional).any { isLexicallyTrue(it) }) null else index.objects(parameter, SHACL.path).singleOrNull() as? Iri
        }
        if (mandatory.isEmpty()) return true
        val declarations = parameters.toSet()
        return index.triples.any { t ->
            t.predicate == mandatory[0] && t.subject !in declarations && t.subject in shapes &&
                mandatory.all { index.objects(t.subject, it).isNotEmpty() }
        }
    }

    internal fun constraintRefs(c: PropertyConstraint): List<RdfResource> =
        when (c) {
            is PropertyConstraint.Node -> listOf(c.nestedShape)
            is PropertyConstraint.NodeByExpression -> listOf(c.nestedShape)
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
            targetNodes = index.objects(subject, SHACL.targetNode)
                .filterNot { isTargetNodeExpression(it, index) }
                .onEach { ensureShapeTermAllowed(it, config) },
            targetSubjectsOf = index.objects(subject, SHACL.targetSubjectsOf).filterIsInstance<Iri>(),
            targetObjectsOf = index.objects(subject, SHACL.targetObjectsOf).filterIsInstance<Iri>(),
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

        // Property shapes have their own severity (default sh:Violation); they never inherit the node shape's.
        val linkedPropShapes =
            index.objects(subject, SHACL.`property`)
                .mapNotNull { ps ->
                    val node = ps as? RdfResource ?: return@mapNotNull null
                    if (isDeactivated(node, index)) return@mapNotNull null
                    if (isParameterTripleDeactivated(subject, SHACL.`property`, ps, index)) return@mapNotNull null
                    compilePropertyShape(node, index, config)
                }

        val closedAllowedPredicates =
            index.objects(subject, SHACL.`property`).filterIsInstance<RdfResource>()
                .flatMap { index.objects(it, SHACL.path) }.filterIsInstance<Iri>().toSet()

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

        val logicalParts = compileLogicalParts(subject, index)
        val nodeRefsOnShape = index.objects(subject, SHACL.node).filterIsInstance<RdfResource>()
        // Blank node values are computed node expressions (reported as unsupported); IRIs are constant shape references.
        val nodeByExpressionRefs = index.objects(subject, SHACL.nodeByExpression).filterIsInstance<Iri>()
        val nodeConstraints = compileConstraints(subject, index, config, ConstraintFlavor.NODE_SCALAR, null)
        val uniqueValuesForProps = parseUniqueValuesFor(subject, index)

        return CompiledNodeShape(
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
        // Every sh:nodeKind value is its own constraint (all must hold); an RDF list value allows any listed kind.
        for (nk in index.objects(subject, SHACL.nodeKind)) {
            val kinds =
                when (nk) {
                    is Iri -> listOf(nk)
                    is BlankNode ->
                        index.parseRdfList(nk).map {
                            it as? Iri ?: throw ShapeCompileException("sh:nodeKind list entries must be IRIs: $it")
                        }
                    else -> emptyList()
                }
            if (kinds.isNotEmpty()) constraints.add(PropertyConstraint.NodeKind(kinds.distinct()))
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
            index.objects(subject, SHACL.nodeByExpression).filterIsInstance<Iri>().forEach {
                constraints.add(PropertyConstraint.NodeByExpression(it))
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

    private fun compileSparqlConstraint(node: RdfResource, index: ShapeGraphIndex, path: ShaclPath?): PropertyConstraint.Sparql? {
        if (isDeactivated(node, index)) return null
        val text = (index.objects(node, SHACL.select).singleOrNull() as? Literal)?.lexical
            ?: throw ShapeCompileException("SPARQL constraint $node must have exactly one sh:select string literal")
        // $PATH is replaced only as a variable token, never inside strings, IRIs or comments.
        val body = SparqlQueryTemplate.substitutePath(text) {
            val p = path ?: throw ShapeCompileException("SPARQL constraint $node uses \$PATH but is not attached to a property shape")
            renderSparqlPath(p)
        }
        SparqlQueryTemplate.restrictionViolation(body)?.let { what ->
            throw SparqlPreBindingRestrictionException("SPARQL constraint $node uses $what, which is not supported with pre-bound variables")
        }
        val prefixes = collectPrefixDeclarations(node, index)
        val query = prefixes.entries.joinToString("") { (p, ns) ->
            val namespace = SparqlQueryTemplate.renderIri(ns)
                ?: throw ShapeCompileException("sh:namespace <$ns> of prefix '$p' is not a valid SPARQL IRI")
            "PREFIX $p: $namespace\n"
        } + body
        val severityIris = index.objects(node, SHACL.severity).filterIsInstance<Iri>()
        val sev = if (severityIris.isEmpty()) null else parseSeverityValues(severityIris, ViolationSeverity.VIOLATION)
        return PropertyConstraint.Sparql(
            constraintNode = node,
            template = SparqlQueryTemplate.compile(query),
            messages = index.objects(node, SHACL.message).filterIsInstance<Literal>(),
            severity = sev?.level,
            severityCustomIri = sev?.customIri,
        )
    }

    /**
     * `sh:prefixes` → `sh:declare [ sh:prefix; sh:namespace ]`, following `owl:imports` of the prefix holders. The
     * prefix declarations of `sh:ShapesGraph` nodes (SHACL 1.2) apply to every query for prefixes not declared
     * through `sh:prefixes`; a `PREFIX` written in the query text itself still takes precedence.
     */
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
        for (shapesGraph in index.subjects(RDF.type, shShapesGraph)) {
            for (decl in index.objects(shapesGraph, SHACL.declare)) {
                val d = decl as? RdfResource ?: throw ShapeCompileException("sh:declare value must be a resource: $decl")
                val prefix = (index.objectSingle(d, SHACL.prefixProperty) as? Literal)?.lexical
                    ?: throw ShapeCompileException("sh:declare $d must have exactly one sh:prefix literal")
                val namespace = (index.objectSingle(d, SHACL.namespaceProperty) as? Literal)?.lexical
                    ?: throw ShapeCompileException("sh:declare $d must have exactly one sh:namespace literal")
                out.putIfAbsent(prefix, namespace)
            }
        }
        return out
    }

    /** Renders a SHACL property path as a SPARQL 1.1 property path (for `$PATH` substitution). */
    internal fun renderSparqlPath(path: ShaclPath): String =
        when (path) {
            is ShaclPath.Predicate ->
                SparqlQueryTemplate.renderIri(path.iri.value)
                    ?: throw ShapeCompileException("IRI <${path.iri.value}> cannot be written as a SPARQL IRI reference in \$PATH")
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
