package com.geoknoesis.kastor.rdf

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.channels.trySendBlocking
import java.io.InputStream

/**
 * Lazily emits triples from a scoped parser inside a [Flow], so downstream operators
 * (for example `buffer` / `map` on the Flow) can apply backpressure.
 *
 * Collection owns and closes [inputStream]. If collection never starts, the caller still owns it.
 */
fun Rdf.parseStreamingFlow(inputStream: InputStream, format: RdfFormat): Flow<RdfTriple> = channelFlow {
    runInterruptible(Dispatchers.IO) {
        openTripleStream(inputStream, format).use { rows ->
            rows.forEach { trySendBlocking(it).getOrThrow() }
        }
    }
}

fun Rdf.parseStreamingFlow(inputStream: InputStream, format: String = "TURTLE"): Flow<RdfTriple> =
    parseStreamingFlow(inputStream, RdfFormat.fromStringOrThrow(format))

fun Sequence<RdfTriple>.asRdfTriplesFlow(): Flow<RdfTriple> = flow {
    forEach { emit(it) }
}

fun Iterable<RdfTriple>.asRdfTriplesFlow(): Flow<RdfTriple> = asSequence().asRdfTriplesFlow()
