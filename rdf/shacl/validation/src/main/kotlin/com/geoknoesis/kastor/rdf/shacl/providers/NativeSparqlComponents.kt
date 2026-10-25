package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.native.PropertyConstraint
import com.geoknoesis.kastor.rdf.shacl.native.isLexicallyTrue

/**
 * SHACL 1.2 SPARQL 4.3: validation with one use of a SPARQL-based constraint component.
 *
 * `$this` and the component's parameter values are pre-bound by the engine (textually, so results do not depend on
 * the SPARQL provider); `$currentShape` and `$shapesGraph` are not (they were removed from the specification). The
 * [computed] parameter values of the focus node (`resolveComponentParameters`) are pre-bound with the constant ones.
 *
 * - An ASK validator is evaluated once per value node `v` with `$value` pre-bound to `v`; `false` is a result for `v`.
 * - A SELECT validator is evaluated once (its query reads the value nodes itself); every solution is a result, and a
 *   `?failure` solution makes the validation fail.
 *
 * Results are mapped by [solutionViolation] with the component as source and the shape's severity. The focus node
 * is always [focus] (never read from a solution). With [firstOnly] (a conformance check) only the first result is
 * built, and a SELECT validator stops at its first solution unless it can bind `?failure`.
 */
internal fun evaluateSparqlComponent(
    focus: RdfTerm,
    tpl: ResultTemplate,
    values: List<RdfTerm>,
    c: PropertyConstraint.SparqlComponent,
    ctx: NativeValidationContext,
    hasPath: Boolean,
    firstOnly: Boolean,
    computed: Map<String, RdfTerm> = emptyMap(),
): List<ValidationViolation> {
    val preBound = LinkedHashMap<String, RdfTerm>()
    preBound["this"] = focus
    preBound.putAll(c.parameters)
    // The computed parameters (SHACL 1.2 Node Expressions §8), evaluated once for the focus node: pre-bound, and shown
    // by {$param} in messages, like constant values.
    preBound.putAll(computed)
    val source = SparqlResultSource(
        constraintNode = null,
        component = c.component,
        messages = c.messages,
        severity = null,
        severityCustomIri = null,
        annotations = c.annotations,
    )
    val out = mutableListOf<ValidationViolation>()
    if (c.ask) {
        for (v in values) {
            val bound = LinkedHashMap(preBound)
            bound["value"] = v
            val (query, providerBindings) = c.template.bind(bound)
            // The query was rewritten to a SELECT: the ASK answer is whether any solution exists.
            if (ctx.select(query, providerBindings) { it.any() }) continue
            val row = mapOf<String, RdfTerm>("this" to focus, "value" to v)
            out.add(solutionViolation(focus, row::get, bound, tpl, source, hasPath))
            if (firstOnly) break
        }
        return out
    }
    val (query, providerBindings) = c.template.bind(preBound)
    // The solutions are streamed, never collected (see evaluateSparql for the ?failure and firstOnly rules).
    ctx.select(query, providerBindings) { rows ->
        for (row in rows) {
            ctx.budget.tick("SPARQL results")
            val failure = row.get("failure")
            if (failure != null && isLexicallyTrue(failure)) {
                throw ShaclValidationException(
                    "SPARQL constraint component ${c.component.value} reported ?failure for focus node $focus",
                )
            }
            if ((firstOnly && out.isNotEmpty()) || out.size >= ctx.resultCap) {
                if (c.template.mayBindFailure) continue else break
            }
            out.add(solutionViolation(focus, row::get, preBound, tpl, source, hasPath))
            if (firstOnly && !c.template.mayBindFailure) break
        }
    }
    return out
}
