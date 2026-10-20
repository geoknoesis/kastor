package com.geoknoesis.kastor.ontoquality.cli

import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfProvider
import com.geoknoesis.kastor.rdf.RdfProviderRegistry
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.types.path
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path

/** `urn:onto-qa:input/` plus the percent-encoded file name of [path]. */
internal fun defaultBaseIri(path: Path): String =
    buildString {
        append(DEFAULT_BASE_IRI_PREFIX)
        for (byte in path.fileName?.toString().orEmpty().toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xff
            val ch = code.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                append(ch)
            } else {
                append('%').append("%02X".format(code))
            }
        }
    }

/** Returns [value] when it is an absolute IRI; a usage error otherwise. */
internal fun validateBaseIri(value: String?): String? {
    if (value == null) return null
    val absolute =
        try {
            URI(value).isAbsolute
        } catch (_: URISyntaxException) {
            false
        }
    if (!absolute || value.any { it.isWhitespace() || it.isISOControl() }) {
        throw usageError("--base-iri must be an absolute IRI such as https://example.org/onto (got '${sanitize(value)}')")
    }
    return value
}


/** Extensions of compressed or quad / N3 files, which [resolveInputFormat] must not take for Turtle. */
private val UNSUPPORTED_INPUT_EXTENSIONS = listOf(".trig", ".nq", ".nquads", ".n3", ".trix", ".gz", ".bz2", ".xz", ".zip", ".zst")

/** Picks the RDF syntax from `--input-format` or the file extension. */
internal fun resolveInputFormat(path: Path, override: String?): RdfFormat {
    if (override != null) {
        val normalized =
            when (override.trim().lowercase()) {
                "rdfxml", "rdf-xml", "rdf/xml", "xml", "owl" -> RdfFormat.RDF_XML
                "ntriples", "n-triples", "nt" -> RdfFormat.N_TRIPLES
                "jsonld", "json-ld" -> RdfFormat.JSON_LD
                "turtle", "ttl" -> RdfFormat.TURTLE
                else -> null
            }
        return normalized ?: throw usageError("Unsupported --input-format $override (expected $INPUT_FORMAT_HELP)")
    }
    val name = path.fileName?.toString()?.lowercase().orEmpty()
    return when {
        name.endsWith(".owl") || name.endsWith(".rdf") || name.endsWith(".xml") -> RdfFormat.RDF_XML
        name.endsWith(".nt") -> RdfFormat.N_TRIPLES
        name.endsWith(".jsonld") || name.endsWith(".json") -> RdfFormat.JSON_LD
        name.endsWith(".ttl") || name.endsWith(".turtle") -> RdfFormat.TURTLE
        // Known formats this tool cannot read as an ontology graph: Turtle would fail with a parse error that blames
        // the content instead of the extension.
        UNSUPPORTED_INPUT_EXTENSIONS.any { name.endsWith(it) } ->
            throw usageError(
                "Unsupported input extension '${sanitize(name.substringAfterLast('.', ""))}' of ${sanitize(name)}: " +
                    "onto-qa reads $INPUT_FORMAT_HELP; decompress or convert the file, or force a syntax with --input-format",
            )
        else -> RdfFormat.TURTLE
    }
}

/**
 * Parses [path] with [baseIri] (default [defaultBaseIri], `urn:onto-qa:input/<file name>`), so relative references
 * (`<>`, `<#Foo>`, `rdf:about="#Foo"`) resolve instead of being rejected, independently of where the file lives. Uses
 * the provider-level `RdfProvider.parseGraph(stream, format, baseIri)` overload, trying providers in registry priority
 * order.
 */
internal fun parseOntology(
    path: Path,
    format: RdfFormat,
    baseIri: String? = null,
    providers: List<RdfProvider> = RdfProviderRegistry.discoverProviders().filter { it.supportsInputFormat(format.formatName) },
): RdfGraph {
    val base = baseIri ?: defaultBaseIri(path)
    fun readError(e: IOException) = CliktError("Failed to read ${sanitize(path.toString())}: ${describeFailure(e)}", e, EXIT_RUNTIME_ERROR)
    for (provider in providers) {
        val stream =
            try {
                IoTrackingInputStream(Files.newInputStream(path))
            } catch (e: IOException) {
                throw readError(e)
            }
        try {
            return stream.use { provider.parseGraph(it, format.formatName, base) }
        } catch (_: UnsupportedOperationException) {
            continue
        } catch (e: Exception) {
            // The file could not be read (status 5), as opposed to read but not understood (status 2).
            stream.failure?.let { throw readError(it) }
            throw CliktError("Failed to parse ${sanitize(path.toString())} as ${format.formatName}: ${sanitize(e.message)}", e, EXIT_INPUT_ERROR)
        }
    }
    throw CliktError(
        "Failed to parse ${sanitize(path.toString())} as ${format.formatName}: no RDF provider can parse ${format.formatName} " +
            "(is an RDF provider such as rdf-jena on the class path?)",
        null,
        EXIT_RUNTIME_ERROR,
    )
}

/** Remembers an [IOException] raised while reading, so a read failure is not reported as a syntax error. */
private class IoTrackingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
    var failure: IOException? = null
        private set

    override fun read(): Int =
        try {
            super.read()
        } catch (e: IOException) {
            failure = e
            throw e
        }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        try {
            super.read(b, off, len)
        } catch (e: IOException) {
            failure = e
            throw e
        }
}
