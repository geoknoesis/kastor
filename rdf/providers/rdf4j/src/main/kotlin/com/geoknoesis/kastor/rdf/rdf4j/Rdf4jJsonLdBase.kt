package com.geoknoesis.kastor.rdf.rdf4j

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.RDFParseException

/**
 * Makes a relative IRI reference in a JSON-LD document parsed **without a base IRI** a parse error, as it is in every
 * other format. JSON-LD is expanded by the JSON-LD processor, whose toRdf step (as the JSON-LD 1.1 algorithm says)
 * **skips** every statement with an IRI that is not absolute, logging a warning only: `{"@id": "s", ...}` parsed to
 * nothing, and a relative `@id` of a named graph dropped the whole graph.
 *
 * Such a parse resolves against [baseFor]'s sentinel base instead, so every relative reference (`s`, `/s`, `//host/s`,
 * `#s`, `""`, a relative `@base`, `@vocab`, `@type` or datatype) becomes an IRI of the scheme [SCHEME], which
 * [check] refuses. The scheme holds 128 random bits drawn once per JVM, so no IRI of a document has it by chance.
 * The Jena provider does the same.
 */
internal object Rdf4jJsonLdBase {
    private val SCHEME = "x-kastor-no-base-" + java.util.UUID.randomUUID().toString().replace("-", "")
    private val BASE = "$SCHEME:/"

    /** The base IRI to hand Rio: [baseIri], or the sentinel for a JSON-LD parse without a base (empty [baseIri]). */
    fun baseFor(format: RDFFormat, baseIri: String): String =
        if (baseIri.isEmpty() && format == RDFFormat.JSONLD) BASE else baseIri

    /**
     * [statement], or [RDFParseException] when one of its terms (graph name included) is an unresolved relative
     * reference. Only a JSON-LD parse resolves against the sentinel; JSON-LD has no triple-term syntax in Rio 5.
     */
    fun checked(format: RDFFormat, statement: Statement): Statement {
        if (format != RDFFormat.JSONLD) return statement
        check(statement.subject)
        check(statement.predicate)
        check(statement.`object`)
        statement.context?.let(::check)
        return statement
    }

    private fun check(value: Value) {
        when (value) {
            is IRI -> problem(value.stringValue())?.let { throw RDFParseException(it) }
            is Literal -> check(value.datatype)
        }
    }

    private fun problem(iri: String): String? {
        if (!iri.startsWith(SCHEME) || iri.getOrNull(SCHEME.length) != ':') return null
        val rest = iri.substring(SCHEME.length + 1)
        val reference = if (rest.startsWith("/") && !rest.startsWith("//")) rest.substring(1) else rest
        return "the JSON-LD document uses the relative IRI reference '$reference' and no base IRI was given; " +
            "relative IRIs are an error unless a base IRI is set (kastor-rdf: --base <iri>)"
    }
}
