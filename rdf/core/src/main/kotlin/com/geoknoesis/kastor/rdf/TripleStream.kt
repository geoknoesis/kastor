package com.geoknoesis.kastor.rdf

/** A single-use triple sequence. Always close with `use`, including after take/first or an exception. */
interface TripleStream : Sequence<RdfTriple>, java.io.Closeable

/**
 * Opens a scoped parser that owns [input]. Closing the stream closes both input and parser; [input] is
 * also closed if no parser can be opened.
 *
 * @throws RdfFormatException.UnsupportedFormat if no registered provider can parse [format]
 */
fun Rdf.openTripleStream(input: java.io.InputStream, format: String = "TURTLE"): TripleStream {
    return try {
        val normalized = RdfFormat.fromStringOrThrow(format).formatName
        val providers = RdfProviderRegistry.discoverProviders()
        val provider = providers.firstOrNull { it.supportsInputFormat(normalized) }
            ?: throw RdfFormatException.UnsupportedFormat(
                normalized,
                providers.flatMap { it.getCapabilities().supportedInputFormats }.distinct(),
            )
        provider.openTripleStream(input, normalized)
    } catch (e: Throwable) {
        runCatching { input.close() }
        throw e
    }
}
fun Rdf.openTripleStream(input: java.io.InputStream, format: RdfFormat): TripleStream = openTripleStream(input, format.formatName)
