package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.native.ClosedMode
import com.geoknoesis.kastor.rdf.shacl.native.CompiledNodeShape
import com.geoknoesis.kastor.rdf.shacl.native.constraintStub
import com.geoknoesis.kastor.rdf.shacl.native.isLexicallyTrue
import com.geoknoesis.kastor.rdf.shacl.native.shaclRdfTermFingerprint

/**
 * The `sh:closed` mode and the ignored properties of [shape] at [focus]: the constants, or the values its computed
 * `sh:closed` and `sh:ignoredProperties` produce (SHACL 1.2 Node Expressions §8), evaluated with the focus node at the
 * shape nesting [depth]. A computed `sh:closed` must produce exactly one node, `true` (TRUE), another `xsd:boolean`
 * (NONE) or `sh:ByTypes` (BY_TYPES); the outputs of a computed `sh:ignoredProperties`, evaluated only when the shape is
 * closed, must be IRIs, and are ignored with the constant ones. Any other output is a validation failure.
 */
internal fun NativeShaclValidator.resolveClosed(
    focus: RdfResource,
    shape: CompiledNodeShape,
    ctx: NativeValidationContext,
    depth: Int,
): Pair<ClosedMode, Set<Iri>> {
    val mode = shape.closedParameter?.let { parameter ->
        val node = checkedOutputs(SHACL.closed, evaluateComputed(parameter.expr, focus, ctx, depth), focus, shape.shapeNode).single()
        when {
            isLexicallyTrue(node) -> ClosedMode.TRUE
            node == SHACL.ByTypes -> ClosedMode.BY_TYPES
            // R5: only "true"^^xsd:boolean is true; "false", "0" and "1" are well-typed and not true.
            node is Literal && node.datatype == XSD.boolean && node.lexical in BOOLEAN_LEXICALS -> ClosedMode.NONE
            else -> dynamicParameterFailure(
                SHACL.closed, shape.shapeNode, focus, "produced the invalid value $node: sh:closed expects true, false or sh:ByTypes",
            )
        }
    } ?: shape.closed
    val computed = shape.ignoredParameter
    if (mode == ClosedMode.NONE || computed == null) return mode to shape.ignoredProperties
    val outputs = checkedOutputs(SHACL.ignoredProperties, evaluateComputed(computed.expr, focus, ctx, depth), focus, shape.shapeNode)
    return mode to (shape.ignoredProperties + outputs.map { it as Iri })
}

private val BOOLEAN_LEXICALS = setOf("true", "false", "1", "0")

/**
 * The results of a closed shape, of the [mode] and the [ignored] properties [resolveClosed] gives; with [firstOnly]
 * (the answer is all that is needed) only the first one.
 */
internal fun validateClosed(
    focus: RdfResource,
    shape: CompiledNodeShape,
    mode: ClosedMode,
    ignored: Set<Iri>,
    tpl: ResultTemplate,
    ctx: NativeValidationContext,
    firstOnly: Boolean,
): List<ValidationViolation> {
    val allowed = mutableSetOf<Iri>()
    allowed.addAll(ignored)
    if (mode == ClosedMode.BY_TYPES || RDF.type in ignored) {
        allowed.add(RDF.type)
    }
    when (mode) {
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
 *
 * A computed `sh:uniqueValuesFor` (SHACL 1.2 Node Expressions §8) gives each focus node V its own properties
 * ([resolveUniqueValuesFor]): V has a result when another target has the same values as V for all of them. The keys
 * are built in one pass over the targets per distinct property list. Deactivation is checked before the properties
 * are resolved.
 */
internal fun NativeShaclValidator.validateUniqueValuesForShape(
    shape: CompiledNodeShape,
    focusNodes: List<RdfTerm>,
    focusOnly: RdfResource?,
    ctx: NativeValidationContext,
    capacity: Int,
): Pair<List<ValidationViolation>, Long> {
    val targets = focusNodes.filterIsInstance<RdfResource>()
    if (targets.size < 2) return emptyList<ValidationViolation>() to 0L
    if (shape.uniqueValuesForParameter != null) return validateDynamicUniqueValuesFor(shape, targets, focusOnly, ctx, capacity)
    val props = shape.uniqueValuesForProps
    val groups = LinkedHashMap<List<List<String>>, MutableList<RdfResource>>()
    for (t in targets) {
        ctx.budget.tick("uniqueValuesFor")
        val key = uniqueValuesKey(t, props, ctx) ?: continue
        groups.getOrPut(key) { mutableListOf() }.add(t)
    }
    val tpl = ResultTemplate(shape.shapeNode, shape.severity, shape.severityCustomIri, shape.messages, null)
    val rows = mutableListOf<ValidationViolation>()
    var count = 0L
    for (nodes in groups.values) {
        if (nodes.size < 2) continue
        val candidates = if (focusOnly == null) nodes else nodes.filter { it == focusOnly }
        val duplicates = candidates.filterNot { uniqueValuesForDeactivated(shape, it, ctx) }
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

/** The composite key of [target] for [props]: the values of each, compared as RDF terms; null when it has none. */
private fun uniqueValuesKey(target: RdfResource, props: List<Iri>, ctx: NativeValidationContext): List<List<String>>? =
    props.map { p -> ctx.data.objects(target, p).map { shaclRdfTermFingerprint(it) }.sorted() }.takeUnless { key -> key.all { it.isEmpty() } }

/** Whether the shape, or a reifier of its `sh:uniqueValuesFor` triple, is deactivated for [node] (Core §3.1.6). */
private fun NativeShaclValidator.uniqueValuesForDeactivated(shape: CompiledNodeShape, node: RdfResource, ctx: NativeValidationContext): Boolean {
    val conditions = listOfNotNull(shape.deactivatedWhen) + shape.reifierConditions.uniqueValuesFor
    return conditions.any { deactivates(it, node, ctx, 0) }
}

/**
 * The properties a computed `sh:uniqueValuesFor` of [shape] gives [focus] (SHACL 1.2 Node Expressions §8): its outputs,
 * at least one, all IRIs; anything else is a validation failure.
 */
internal fun NativeShaclValidator.resolveUniqueValuesFor(focus: RdfTerm, shape: CompiledNodeShape, ctx: NativeValidationContext): List<Iri> {
    val parameter = checkNotNull(shape.uniqueValuesForParameter) { "sh:uniqueValuesFor of ${shape.shapeNode} is not computed" }
    return checkedOutputs(SHACL.uniqueValuesFor, evaluateComputed(parameter.expr, focus, ctx, 0), focus, shape.shapeNode).map { it as Iri }
}

/** [validateUniqueValuesForShape] for a computed `sh:uniqueValuesFor`: each focus node is compared on its own properties. */
private fun NativeShaclValidator.validateDynamicUniqueValuesFor(
    shape: CompiledNodeShape,
    targets: List<RdfResource>,
    focusOnly: RdfResource?,
    ctx: NativeValidationContext,
    capacity: Int,
): Pair<List<ValidationViolation>, Long> {
    // The focus nodes with a result to find, and their properties; the order of the properties does not matter.
    val byProps = LinkedHashMap<List<Iri>, MutableList<RdfResource>>()
    for (focus in targets) {
        if (focusOnly != null && focus != focusOnly) continue
        if (uniqueValuesForDeactivated(shape, focus, ctx)) continue
        val props = resolveUniqueValuesFor(focus, shape, ctx).distinct().sortedBy { it.value }
        byProps.getOrPut(props) { ArrayList() }.add(focus)
    }
    val tpl = ResultTemplate(shape.shapeNode, shape.severity, shape.severityCustomIri, shape.messages, null)
    val duplicates = ArrayList<Pair<RdfResource, Int>>()
    for ((props, foci) in byProps) {
        // One pass over the targets per property list: how many targets share each key.
        val keys = HashMap<RdfResource, List<List<String>>>()
        val counts = HashMap<List<List<String>>, Int>()
        for (t in targets) {
            ctx.budget.tick("uniqueValuesFor")
            val key = uniqueValuesKey(t, props, ctx) ?: continue
            keys[t] = key
            counts.merge(key, 1, Int::plus)
        }
        for (focus in foci) {
            val shared = keys[focus]?.let { counts.getValue(it) } ?: continue
            if (shared >= 2) duplicates.add(focus to shared)
        }
    }
    // In target order, as for constant properties.
    val order = HashMap<RdfResource, Int>().apply { targets.forEachIndexed { i, t -> putIfAbsent(t, i) } }
    duplicates.sortBy { order.getValue(it.first) }
    val rows = ArrayList<ValidationViolation>()
    for ((node, shared) in duplicates) {
        ctx.budget.tick("uniqueValuesFor")
        if (rows.size >= capacity) break
        rows.add(
            violation(
                node, tpl, constraintStub(ConstraintType.UNIQUE_VALUES_FOR),
                "sh:uniqueValuesFor: composite key shared with ${shared - 1} other target(s)",
            ),
        )
    }
    return rows to duplicates.size.toLong()
}
