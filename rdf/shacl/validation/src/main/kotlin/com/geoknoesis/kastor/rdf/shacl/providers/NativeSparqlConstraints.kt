package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import com.geoknoesis.kastor.rdf.shacl.native.PropertyConstraint
import com.geoknoesis.kastor.rdf.shacl.native.ShaclPath
import com.geoknoesis.kastor.rdf.shacl.native.SparqlConstraintEvaluator
import com.geoknoesis.kastor.rdf.shacl.native.SparqlResultAnnotation
import com.geoknoesis.kastor.rdf.shacl.native.isLexicallyTrue
import com.geoknoesis.kastor.rdf.shacl.native.constraintStub

private val messagePlaceholder = Regex("\\{[?$]([A-Za-z_][A-Za-z0-9_]*)\\}")

/**
 * The most characters the substituted values of one result message may add. A message of many placeholders times a
 * long value would otherwise cost their product per result: past the limit the remaining placeholders are kept as
 * written and the message ends with a marker, never silently cut.
 */
internal const val MAX_MESSAGE_SUBSTITUTION = 65_536

/**
 * [template] with its `{?v}` / `{$v}` placeholders replaced by the [value] of `v` (as [displayTerm]); a placeholder
 * without a value is kept. Bounded by [MAX_MESSAGE_SUBSTITUTION].
 */
internal fun substituteMessage(template: String, value: (String) -> RdfTerm?): String {
    var added = 0
    var truncated = false
    val text = messagePlaceholder.replace(template) { m ->
        val term = if (truncated) null else value(m.groupValues[1])
        val shown = term?.let { displayTerm(it) } ?: return@replace m.value
        if (added + shown.length > MAX_MESSAGE_SUBSTITUTION) {
            truncated = true
            return@replace m.value
        }
        added += shown.length
        shown
    }
    return if (truncated) "$text [message truncated: substituted values exceed $MAX_MESSAGE_SUBSTITUTION characters]" else text
}

/** What a solution is mapped against: the declaring node (`sh:sparql` node or constraint component) and its settings. */
internal class SparqlResultSource(
    val constraintNode: RdfResource?,
    /** `sh:sourceConstraintComponent` to report, or null for the one implied by the constraint type. */
    val component: Iri?,
    val messages: List<Literal>,
    val severity: ViolationSeverity?,
    val severityCustomIri: Iri?,
    val annotations: List<SparqlResultAnnotation>,
)

/**
 * Maps one solution to a result (SHACL 1.2 SPARQL 3.3.2): `?value` (the focus node for a node shape when unbound),
 * `?path` when an IRI (otherwise the shape's path), `?message` before the source's and then the shape's `sh:message`
 * (with `{?v}` / `{$v}` substituted from the solution, then from [preBound]), the source's severity, and the
 * annotations of [source]. [hasPath] is true for property shapes.
 */
internal fun solutionViolation(
    focus: RdfTerm,
    row: (String) -> RdfTerm?,
    preBound: Map<String, RdfTerm>,
    tpl: ResultTemplate,
    source: SparqlResultSource,
    hasPath: Boolean,
): ValidationViolation {
    val value = row("value") ?: if (!hasPath) focus else null
    val rowPath = (row("path") as? Iri)?.let { ReportPath(listOf(it), null, emptyList(), it) } ?: tpl.path
    fun substitute(lit: Literal): Literal {
        val text = substituteMessage(lit.lexical) { name -> row(name) ?: preBound[name] }
        return if (lit is LangString) lit.copy(lexical = text) else TypedLiteral(text, lit.datatype)
    }
    val messages =
        listOfNotNull(row("message") as? Literal).ifEmpty { source.messages }.ifEmpty { tpl.messages }.map { substitute(it) }
    val annotations = LinkedHashMap<Iri, List<RdfTerm>>()
    for (a in source.annotations) {
        val values = row(a.variable)?.let { listOf(it) } ?: a.defaults
        if (values.isNotEmpty()) annotations[a.property] = (annotations[a.property].orEmpty() + values).distinct()
    }
    return violation(
        focus = focus,
        tpl = tpl,
        constraint = constraintStub(ConstraintType.SPARQL_CONSTRAINT, rowPath?.predicate),
        message = source.constraintNode?.let { "SPARQL constraint ${it.displayId()} returned a solution" }
            ?: "SPARQL constraint component ${source.component?.value} returned a solution",
        value = value,
        // SHACL-SPARQL: a sh:severity on the SPARQL-based constraint is the result severity.
        severity = source.severity ?: tpl.severity,
        severityIri = if (source.severity != null) source.severityCustomIri else tpl.severityCustomIri,
        messages = messages,
        sourceConstraint = source.constraintNode,
        path = rowPath,
        sourceConstraintComponent = source.component,
        resultAnnotations = annotations,
    )
}

/**
 * SHACL-SPARQL: one result per solution, mapped by [solutionViolation]. Pre-bound
 * variables are substituted by the engine ([com.geoknoesis.kastor.rdf.shacl.native.SparqlQueryTemplate]), so
 * results do not depend on the SPARQL provider. `?failure` solutions fail the check. With [firstOnly] (a
 * conformance check, which only needs to know whether there is a solution) only the first result is built, and
 * only the first solution is read unless the query can bind `?failure`.
 */
internal fun evaluateSparql(
    focus: RdfTerm,
    tpl: ResultTemplate,
    c: PropertyConstraint.Sparql,
    ctx: NativeValidationContext,
    path: ShaclPath?,
    firstOnly: Boolean,
): List<ValidationViolation> {
    val preBound = LinkedHashMap<String, RdfTerm>()
    preBound["this"] = focus
    val (query, providerBindings) = c.template.bind(preBound)
    val source = SparqlResultSource(
        constraintNode = c.constraintNode,
        component = null,
        messages = c.messages,
        severity = c.severity,
        severityCustomIri = c.severityCustomIri,
        annotations = c.annotations,
    )
    val out = mutableListOf<ValidationViolation>()
    // The solutions are streamed, never collected. When only the answer is needed, the first solution gives it;
    // a query that can bind ?failure is still read to the end (a ?failure solution anywhere makes the check a
    // failure, nested or not), without building results; one that cannot stops at the first solution.
    ctx.select(query, providerBindings) { rows ->
        for (row in rows) {
            val failure = row.get("failure")
            if (failure != null && isLexicallyTrue(failure)) {
                throw ShaclValidationException("SPARQL constraint ${c.constraintNode.displayId()} reported ?failure for focus node $focus")
            }
            // Only a query that can bind ?failure gets here after its first result: it is scanned, not built.
            if (firstOnly && out.isNotEmpty()) continue
            out.add(solutionViolation(focus, row::get, preBound, tpl, source, hasPath = path != null))
            if (firstOnly && !c.template.mayBindFailure) break
        }
    }
    return out
}
