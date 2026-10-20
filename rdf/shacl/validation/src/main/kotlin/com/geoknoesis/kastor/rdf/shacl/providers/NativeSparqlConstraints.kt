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
import com.geoknoesis.kastor.rdf.shacl.native.PropertyConstraint
import com.geoknoesis.kastor.rdf.shacl.native.ShaclPath
import com.geoknoesis.kastor.rdf.shacl.native.SparqlConstraintEvaluator
import com.geoknoesis.kastor.rdf.shacl.native.isLexicallyTrue
import com.geoknoesis.kastor.rdf.shacl.native.constraintStub

private val messagePlaceholder = Regex("\\{[?$]([A-Za-z_][A-Za-z0-9_]*)\\}")

/**
 * SHACL-SPARQL: one result per solution; `?value`, `?path`, `?message` and `?failure` are honoured. Pre-bound
 * variables are substituted by the engine ([com.geoknoesis.kastor.rdf.shacl.native.SparqlQueryTemplate]), so
 * results do not depend on the SPARQL provider. A `?message` binding takes precedence over the constraint's
 * `sh:message`, which takes precedence over the shape's (SHACL-SPARQL result mapping). With [firstOnly] (a
 * conformance check, which only needs to know whether there is a solution) only the first result is built, and
 * only the first solution is read unless the query can bind `?failure`.
 */
internal fun evaluateSparql(
    focus: RdfTerm,
    tpl: ResultTemplate,
    c: PropertyConstraint.Sparql,
    ctx: NativeValidationContext,
    currentShape: RdfResource,
    path: ShaclPath?,
    firstOnly: Boolean,
): List<ValidationViolation> {
    val preBound = LinkedHashMap<String, RdfTerm>()
    preBound["this"] = focus
    if ("currentShape" in c.preBound) preBound["currentShape"] = currentShape
    if ("shapesGraph" in c.preBound) preBound["shapesGraph"] = SparqlConstraintEvaluator.SHAPES_GRAPH_IRI
    val (query, providerBindings) = c.template.bind(preBound)
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
            val value = row.get("value") ?: if (path == null) focus else null
            val rowPath = (row.get("path") as? Iri)?.let { ReportPath(listOf(it), null, emptyList(), it) } ?: tpl.path
            fun substitute(lit: Literal): Literal {
                val text = messagePlaceholder.replace(lit.lexical) { m ->
                    val term = row.get(m.groupValues[1]) ?: preBound[m.groupValues[1]]
                    term?.let { displayTerm(it) } ?: m.value
                }
                return if (lit is LangString) lit.copy(lexical = text) else TypedLiteral(text, lit.datatype)
            }
            val messages =
                listOfNotNull(row.get("message") as? Literal).ifEmpty { c.messages }.ifEmpty { tpl.messages }.map { substitute(it) }
            out.add(
                violation(
                    focus = focus,
                    tpl = tpl,
                    constraint = constraintStub(ConstraintType.SPARQL_CONSTRAINT, rowPath?.predicate),
                    message = "SPARQL constraint ${c.constraintNode.displayId()} returned a solution",
                    value = value,
                    // SHACL-SPARQL: a sh:severity on the SPARQL-based constraint is the result severity.
                    severity = c.severity ?: tpl.severity,
                    severityIri = if (c.severity != null) c.severityCustomIri else tpl.severityCustomIri,
                    messages = messages,
                    sourceConstraint = c.constraintNode,
                    path = rowPath,
                ),
            )
            if (firstOnly && !c.template.mayBindFailure) break
        }
    }
    return out
}
