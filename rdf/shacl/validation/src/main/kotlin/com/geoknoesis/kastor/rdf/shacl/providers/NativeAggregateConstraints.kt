package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.native.ClosedMode
import com.geoknoesis.kastor.rdf.shacl.native.CompiledNodeShape
import com.geoknoesis.kastor.rdf.shacl.native.constraintStub
import com.geoknoesis.kastor.rdf.shacl.native.shaclRdfTermFingerprint

/** The results of a closed shape; with [firstOnly] (the answer is all that is needed) only the first one. */
internal fun validateClosed(
    focus: RdfResource,
    shape: CompiledNodeShape,
    tpl: ResultTemplate,
    ctx: NativeValidationContext,
    firstOnly: Boolean,
): List<ValidationViolation> {
    val allowed = mutableSetOf<Iri>()
    allowed.addAll(shape.ignoredProperties)
    if (shape.closed == ClosedMode.BY_TYPES || RDF.type in shape.ignoredProperties) {
        allowed.add(RDF.type)
    }
    when (shape.closed) {
        // SHACL §4.8.1: the IRIs used as sh:path of every sh:property value (deactivated ones included).
        ClosedMode.TRUE -> allowed.addAll(shape.closedAllowedPredicates)
        ClosedMode.BY_TYPES -> allowed.addAll(collectClosedByTypesProperties(focus, ctx))
        ClosedMode.NONE -> Unit
    }
    val violations = mutableListOf<ValidationViolation>()
    val data = ctx.data
    for (p in data.predicatesFor(focus)) {
        if (p in allowed) continue
        val path = ReportPath(listOf(p), null, emptyList(), p)
        for (o in data.objects(focus, p)) {
            violations.add(
                violation(focus, tpl, constraintStub(ConstraintType.CLOSED, p), "Closed shape disallows predicate $p", value = o, path = path),
            )
            if (firstOnly) return violations
        }
    }
    return violations
}

/**
 * SHACL 1.2 `sh:closed sh:ByTypes` property collection: the predicates declared by the shapes that apply to the
 * types of [focus] — the types themselves when they are shapes (implicit class targets), the shapes that target
 * them with `sh:targetClass`, their `sh:node` references, and the same for every superclass. The walk over the
 * shapes graph uses an explicit worklist and a visited set, so an `rdfs:subClassOf` or `sh:node` chain of any
 * length, or a cycle, cannot exhaust the stack.
 */
private fun collectClosedByTypesProperties(focus: RdfResource, ctx: NativeValidationContext): Set<Iri> {
    val out = mutableSetOf<Iri>()
    val compiled = ctx.compiled
    val shapesIdx = compiled.index
    val visited = HashSet<RdfResource>()
    val pending = ArrayDeque<RdfResource>()
    fun visit(node: RdfResource) {
        if (visited.add(node)) pending.addLast(node)
    }
    ctx.data.typesOf(focus).forEach(::visit)
    // Shapes by the classes they target, built once per walk (a scan of every shape per class would be quadratic).
    val shapesByTargetClass: Map<Iri, List<RdfResource>> by lazy {
        val byClass = HashMap<Iri, MutableList<RdfResource>>()
        for (cn in compiled.orderedNodeShapes) for (c in cn.targets.targetClasses) byClass.getOrPut(c) { ArrayList() }.add(cn.shapeNode)
        byClass
    }
    while (pending.isNotEmpty()) {
        ctx.budget.tick("closed by types")
        val s = pending.removeFirst()
        compiled.shapesByNode[s]?.let { out.addAll(it.closedAllowedPredicates) }
        val types = shapesIdx.objects(s, RDF.type)
        if (s is Iri && types.contains(RDFS.Class)) {
            for (sup in shapesIdx.objects(s, RDFS.subClassOf)) if (sup is Iri) visit(sup)
            shapesByTargetClass[s]?.forEach(::visit)
        }
        if (types.contains(SHACL.NodeShape)) {
            for (nr in shapesIdx.objects(s, SHACL.node)) if (nr is RdfResource) visit(nr)
        }
    }
    return out
}

/**
 * `sh:uniqueValuesFor`: hash-groups targets by their composite key (for each listed property, the set of its values
 * compared as RDF terms; value nodes are sets, so value order is irrelevant). Every
 * target whose key is shared with at least one other target yields exactly one result, without `sh:value`
 * (W3C `uniqueValuesFor-001` … `-005`). The total is counted per group and at most [capacity] result rows are
 * materialized, so large duplicate groups stay linear.
 *
 * A focus node for which the shape, or the constraint, is deactivated by a non-constant `sh:deactivated` of the shape
 * or of a reifier of its `sh:uniqueValuesFor` triple has no result (SHACL 1.2 Core §3.1.6: deactivated shapes and
 * constraints are ignored), but it stays one of the targets the keys are compared with: the
 * constraint is about the target set, which deactivation does not change.
 */
internal fun NativeShaclValidator.validateUniqueValuesForShape(
    shape: CompiledNodeShape,
    focusNodes: List<RdfTerm>,
    focusOnly: RdfResource?,
    ctx: NativeValidationContext,
    capacity: Int,
): Pair<List<ValidationViolation>, Long> {
    val props = shape.uniqueValuesForProps
    val targets = focusNodes.filterIsInstance<RdfResource>()
    if (targets.size < 2) return emptyList<ValidationViolation>() to 0L
    val groups = LinkedHashMap<List<List<String>>, MutableList<RdfResource>>()
    for (t in targets) {
        ctx.budget.tick("uniqueValuesFor")
        val key = props.map { p -> ctx.data.objects(t, p).map { shaclRdfTermFingerprint(it) }.sorted() }
        if (key.all { it.isEmpty() }) continue
        groups.getOrPut(key) { mutableListOf() }.add(t)
    }
    val tpl = ResultTemplate(shape.shapeNode, shape.severity, shape.severityCustomIri, shape.messages, null)
    val rows = mutableListOf<ValidationViolation>()
    var count = 0L
    for (nodes in groups.values) {
        if (nodes.size < 2) continue
        val candidates = if (focusOnly == null) nodes else nodes.filter { it == focusOnly }
        // The shape's own deactivation, and that of a reifier of its sh:uniqueValuesFor triple (Core §3.1.6).
        val conditions = listOfNotNull(shape.deactivatedWhen) + shape.reifierConditions.uniqueValuesFor
        val duplicates =
            if (conditions.isEmpty()) candidates else candidates.filterNot { node -> conditions.any { deactivates(it, node, ctx, 0) } }
        count += duplicates.size
        for (node in duplicates) {
            ctx.budget.tick("uniqueValuesFor")
            if (rows.size >= capacity) break
            rows.add(
                violation(
                    node, tpl, constraintStub(ConstraintType.UNIQUE_VALUES_FOR),
                    "sh:uniqueValuesFor: composite key shared with ${nodes.size - 1} other target(s)",
                ),
            )
        }
    }
    return rows to count
}
