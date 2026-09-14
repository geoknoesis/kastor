package com.geoknoesis.kastor.rdf

/** A single-use triple sequence. Always close with `use`, including after take/first or an exception. */
interface TripleStream : Sequence<RdfTriple>, java.io.Closeable

/**
 * Opens a scoped parser that owns [input]. Closing the stream closes both input and parser; [input] is
 * also closed if no parser can be opened.
 *
 * @throws RdfFormatException.UnsupportedFormat if no registered provider can parse [format]
 */
fun Rdf.openTripleStream(input: java.io.InputStream, format: String = "TURTLE"): TripleStream =
    openTripleStream(input, format, null)

/**
 * [openTripleStream] resolving relative IRIs against [baseIri] (see [RdfProvider.openTripleStream]); null keeps
 * the provider's default (relative IRIs are then errors with the bundled providers).
 */
fun Rdf.openTripleStream(input: java.io.InputStream, format: String, baseIri: String?): TripleStream {
    return try {
        val normalized = RdfFormat.fromStringOrThrow(format).formatName
        val providers = RdfProviderRegistry.discoverProviders()
        val provider = providers.firstOrNull { it.supportsInputFormat(normalized) }
            ?: throw RdfFormatException.UnsupportedFormat(
                normalized,
                providers.flatMap { it.getCapabilities().supportedInputFormats }.distinct(),
            )
        provider.openTripleStream(input, normalized, baseIri)
    } catch (e: Throwable) {
        runCatching { input.close() }
        throw e
    }
}
fun Rdf.openTripleStream(input: java.io.InputStream, format: RdfFormat): TripleStream = openTripleStream(input, format.formatName, null)
fun Rdf.openTripleStream(input: java.io.InputStream, format: RdfFormat, baseIri: String?): TripleStream =
    openTripleStream(input, format.formatName, baseIri)
