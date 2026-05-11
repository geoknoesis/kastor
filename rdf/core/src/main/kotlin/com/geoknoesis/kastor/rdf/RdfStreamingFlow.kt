package com.geoknoesis.kastor.rdf

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.InputStream

/**
 * Lazily emits triples from [Rdf.parseStreaming] inside a [Flow], so downstream operators
 * (for example `buffer` / `map` on the Flow) can apply backpressure.
 *
 * The caller remains responsible for closing [inputStream] when the flow is no longer consumed
 * (typically `inputStream.use { collect … }` at the call site).
 */
fun Rdf.parseStreamingFlow(inputStream: InputStream, format: RdfFormat): Flow<RdfTriple> = flow {
    parseStreaming(inputStream, format.formatName).forEach { emit(it) }
}

fun Rdf.parseStreamingFlow(inputStream: InputStream, format: String = "TURTLE"): Flow<RdfTriple> =
    parseStreamingFlow(inputStream, RdfFormat.fromStringOrThrow(format))

fun Sequence<RdfTriple>.asRdfTriplesFlow(): Flow<RdfTriple> = flow {
    forEach { emit(it) }
}

fun Iterable<RdfTriple>.asRdfTriplesFlow(): Flow<RdfTriple> = asSequence().asRdfTriplesFlow()
