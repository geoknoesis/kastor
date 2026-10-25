package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler.parseSeverityValues
import com.geoknoesis.kastor.rdf.shacl.native.ShapeDeactivation.isDeactivated
import com.geoknoesis.kastor.rdf.shacl.native.ShapeDeactivation.deactivationExpression

/** SPARQL-based constraints (`sh:sparql`, SHACL 1.2 SPARQL) of a shape. */
internal object SparqlConstraintCompilation {
    internal fun compileSparqlConstraint(node: RdfResource, index: ShapeGraphIndex, path: ShaclPath?): PropertyConstraint.Sparql? {
        if (isDeactivated(node, index)) return null
        val select = index.objects(node, SHACL.select).singleOrNull() as? Literal
        if (select == null || select is LangString || select.datatype != XSD.string) {
            throw ShapeCompileException("SPARQL constraint $node must have exactly one xsd:string sh:select")
        }
        val text = select.lexical
        // $PATH is replaced only as a variable token, never inside strings, IRIs or comments.
        // On a node shape $PATH stays unbound.
        val body = SparqlQueryTemplate.substitutePath(text, path?.let { { renderSparqlPath(it) } })
        val template = SparqlQueryTemplate.compileRestricted(
            SparqlPrefixes.prologue(SparqlPrefixes.collect(node, index)), body,
            SparqlQueryTemplate.SPARQL_CONSTRAINT_PRE_BOUND, "SPARQL constraint $node", index.budget,
        )
        val severityIris = index.objects(node, SHACL.severity).filterIsInstance<Iri>()
        val sev = if (severityIris.isEmpty()) null else parseSeverityValues(severityIris, ViolationSeverity.VIOLATION)
        return PropertyConstraint.Sparql(
            constraintNode = node,
            template = template,
            messages = index.objects(node, SHACL.message).filterIsInstance<Literal>(),
            severity = sev?.level,
            severityCustomIri = sev?.customIri,
            annotations = compileResultAnnotations(node, index),
            deactivatedWhen = deactivationExpression(node, index),
        )
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
}
