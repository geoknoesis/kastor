package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.InternalKastorApi
import com.geoknoesis.kastor.rdf.JsonLdExpandedCheck
import no.hasmac.jsonld.JsonLd
import no.hasmac.jsonld.JsonLdError
import no.hasmac.jsonld.JsonLdOptions
import no.hasmac.jsonld.document.JsonDocument
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.RDFParseException
import java.io.InputStream

/**
 * How Kastor's RDF4J parses read JSON-LD: the document loader of the JSON-LD processor RDF4J uses (the hasmac fork
 * of Titanium), and the check that no statement is lost in the conversion to RDF.
 *
 * JSON-LD's toRdf step **skips** every statement with a term that is not an absolute IRI or a blank node, and every
 * value with an ill-formed language tag, logging a warning only (see [JsonLdExpandedCheck]). Whether an IRI ends up
 * relative depends on the document as much as on the parse: `"@base": null` in any context leaves relative references
 * unresolved even when the parse has a base IRI. So [checked] expands the document first, with the options of Rio's
 * conversion, and refuses it when its expanded form has a term the conversion would drop. The Jena provider does the
 * same.
 */
@OptIn(InternalKastorApi::class)
internal object Rdf4jJsonLd {
    /** The processor's own tests, as Rio's JSON-LD parser configures it (no URI validation). */
    private object Hasmac : JsonLdExpandedCheck.Processor {
        override fun isAbsoluteIri(iri: String) = no.hasmac.jsonld.uri.UriUtils.isAbsoluteUri(iri, false)
        override fun isWellFormedLanguageTag(tag: String) = no.hasmac.jsonld.lang.LanguageTag.isWellFormed(tag)
    }

    /** Refuses every document the JSON-LD processor would load (see [Rdf4jFormatSupport]). */
    val REFUSING_DOCUMENT_LOADER = no.hasmac.jsonld.loader.DocumentLoader { url, _ ->
        throw JsonLdError(
            no.hasmac.jsonld.JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED,
            "JSON-LD context <$url> was not loaded: Kastor never loads remote or local JSON-LD documents; " +
                "put the context inline",
        )
    }

    /**
     * [input] for Rio, unchanged unless [format] is JSON-LD: then the document is read whole (the processor reads it
     * whole anyway), expanded as Rio's parser does (this loader, no URI validation, [baseIri] unless empty), and
     * refused with [RDFParseException] when [JsonLdExpandedCheck] finds a term that the conversion would drop.
     */
    fun checked(format: RDFFormat, input: InputStream, baseIri: String): InputStream {
        if (format != RDFFormat.JSONLD) return input
        val bytes = input.readAllBytes()
        val options = JsonLdOptions(REFUSING_DOCUMENT_LOADER).also { options ->
            options.isUriValidation = false
            if (baseIri.isNotEmpty()) options.base = java.net.URI(baseIri)
        }
        val expanded = try {
            JsonLd.expand(JsonDocument.of(java.io.ByteArrayInputStream(bytes))).options(options).get()
        } catch (e: JsonLdError) {
            throw RDFParseException("Could not parse JSONLD: ${e.message}", e)
        }
        JsonLdExpandedCheck.problem(expanded, Hasmac)?.let { throw RDFParseException(it) }
        requireNoUndefinedTerms(bytes, baseIri)
        return java.io.ByteArrayInputStream(bytes)
    }

    /**
     * Expansion removes a key that expands to no IRI, and the hasmac fork has no option to report it; Titanium (the
     * processor it forked from, the Jena provider's) does. So the document is also expanded by Titanium with undefined
     * terms as errors, and with the same document loader rule and base. Any failure of that expansion refuses the
     * document, so both providers accept the same JSON-LD.
     */
    private fun requireNoUndefinedTerms(bytes: ByteArray, baseIri: String) {
        val options = com.apicatalog.jsonld.JsonLdOptions(TITANIUM_REFUSING_LOADER).also { options ->
            options.undefinedTermsPolicy = com.apicatalog.jsonld.JsonLdOptions.ProcessingPolicy.Fail
            if (baseIri.isNotEmpty()) options.base = java.net.URI(baseIri)
        }
        try {
            com.apicatalog.jsonld.JsonLd.expand(com.apicatalog.jsonld.document.JsonDocument.of(java.io.ByteArrayInputStream(bytes)))
                .options(options).get()
        } catch (e: com.apicatalog.jsonld.JsonLdError) {
            val undefined = e.code == com.apicatalog.jsonld.JsonLdErrorCode.UNDEFINED_TERM
            throw RDFParseException(if (undefined) JsonLdExpandedCheck.undefinedTermProblem(e.message) else "Could not parse JSONLD: ${e.message}", e)
        }
    }

    private val TITANIUM_REFUSING_LOADER = com.apicatalog.jsonld.loader.DocumentLoader { url, _ ->
        throw com.apicatalog.jsonld.JsonLdError(
            com.apicatalog.jsonld.JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED,
            "JSON-LD context <$url> was not loaded: Kastor never loads remote or local JSON-LD documents; " +
                "put the context inline",
        )
    }
}
