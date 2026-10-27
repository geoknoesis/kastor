package com.geoknoesis.kastor.rdf.jena

import com.apicatalog.jsonld.JsonLd
import com.apicatalog.jsonld.JsonLdError
import com.apicatalog.jsonld.JsonLdOptions
import com.apicatalog.jsonld.document.JsonDocument
import com.geoknoesis.kastor.rdf.InternalKastorApi
import com.geoknoesis.kastor.rdf.JsonLdExpandedCheck
import org.apache.jena.riot.RiotException

/**
 * How Kastor's Jena parses read JSON-LD: the options of Titanium, the JSON-LD processor of Jena's reader, and the
 * check that no statement is lost in the conversion to RDF.
 *
 * JSON-LD's toRdf step **skips** every statement with a term that is not an absolute IRI or a blank node, and every
 * value with an ill-formed language tag, logging a warning only (see [JsonLdExpandedCheck]). Whether an IRI ends up
 * relative depends on the document as much as on the parse: `"@base": null` in any context leaves relative references
 * unresolved even when the parse has a base IRI. So [checked] expands the document first, with the very options of
 * the conversion, and refuses it when its expanded form has a term the conversion would drop.
 */
@OptIn(InternalKastorApi::class)
internal object JenaJsonLd {
    /** Titanium's own tests: toRdf keeps a term only when they pass (with the options of [options]). */
    private object Titanium : JsonLdExpandedCheck.Processor {
        private val validation = JsonLdOptions().uriValidation
        override fun isAbsoluteIri(iri: String) = com.apicatalog.jsonld.uri.UriUtils.isAbsoluteUri(iri, validation)
        override fun isWellFormedLanguageTag(tag: String) = com.apicatalog.jsonld.lang.LanguageTag.isWellFormed(tag)
    }

    /**
     * Titanium's options for one parse: a document loader that refuses everything (see [JenaParsing.noRemoteDocuments]),
     * undefined terms as errors and, when [baseIri] is given, that base. A new object per parse: Jena also sets the
     * base on the one it reads with.
     */
    fun options(baseIri: String?): JsonLdOptions = JsonLdOptions(REFUSING_DOCUMENT_LOADER).also { options ->
        // A key that expands to no IRI fails the expansion instead of being dropped (JsonLdExpandedCheck).
        options.undefinedTermsPolicy = JsonLdOptions.ProcessingPolicy.Fail
        if (baseIri != null) options.base = java.net.URI.create(baseIri)
    }

    private val REFUSING_DOCUMENT_LOADER = com.apicatalog.jsonld.loader.DocumentLoader { url, _ ->
        throw JsonLdError(
            com.apicatalog.jsonld.JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED,
            "JSON-LD context <$url> was not loaded: Kastor never loads remote or local JSON-LD documents; " +
                "put the context inline",
        )
    }

    /**
     * The JSON-LD document of [input], to be parsed with [baseIri], after checking that its conversion to RDF keeps
     * every statement: it is read whole (Titanium reads it whole anyway), expanded with [options], and refused with
     * [RiotException] when [JsonLdExpandedCheck] finds a term that the conversion would drop.
     */
    fun checked(input: java.io.InputStream, baseIri: String?): java.io.InputStream {
        val bytes = try {
            input.readAllBytes()
        } catch (e: java.io.IOException) {
            throw org.apache.jena.atlas.RuntimeIOException(e)
        }
        val expanded = try {
            JsonLd.expand(JsonDocument.of(java.io.ByteArrayInputStream(bytes))).options(options(baseIri)).get()
        } catch (e: JsonLdError) {
            val undefined = e.code == com.apicatalog.jsonld.JsonLdErrorCode.UNDEFINED_TERM
            throw RiotException(if (undefined) JsonLdExpandedCheck.undefinedTermProblem(e.message) else e.message, e)
        }
        JsonLdExpandedCheck.problem(expanded, Titanium)?.let { throw RiotException(it) }
        return java.io.ByteArrayInputStream(bytes)
    }
}
