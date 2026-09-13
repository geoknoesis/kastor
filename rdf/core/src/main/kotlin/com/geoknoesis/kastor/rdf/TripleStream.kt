package com.geoknoesis.kastor.rdf

/** A single-use triple sequence. Always close with `use`, including after take/first or an exception. */
interface TripleStream : Sequence<RdfTriple>, java.io.Closeable

/** Opens a scoped parser that owns [input]. Closing the stream closes both input and parser. */
fun Rdf.openTripleStream(input: java.io.InputStream, format: String = "TURTLE"): TripleStream {
    val normalized = RdfFormat.fromStringOrThrow(format).formatName
    val provider = RdfProviderRegistry.discoverProviders().firstOrNull { it.supportsFormat(normalized) }
        ?: throw IllegalArgumentException("No parser for $format")
    return try { provider.openTripleStream(input, normalized) } catch (e: Throwable) {
        runCatching { input.close() }; throw e
    }
}
fun Rdf.openTripleStream(input: java.io.InputStream, format: RdfFormat): TripleStream = openTripleStream(input, format.formatName)
