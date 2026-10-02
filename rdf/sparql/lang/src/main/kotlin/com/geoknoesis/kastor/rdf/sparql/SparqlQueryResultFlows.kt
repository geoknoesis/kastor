package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.BindingSet
import com.geoknoesis.kastor.rdf.SparqlQueryResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * [Flow] over SELECT result rows, with optional validation that each solution binds the given variables.
 *
 * Reading a result is blocking I/O, so the rows are read on [Dispatchers.IO], never on the
 * collector's dispatcher; use the overload with a dispatcher to choose another one.
 *
 * @param eachRowMustBind SPARQL variable names **without** `?` (e.g. `"s"`, `"p"`, `"o"` for `?s ?p ?o`).
 */
fun SparqlQueryResult.asFlow(vararg eachRowMustBind: String): Flow<BindingSet> = asFlow(Dispatchers.IO, *eachRowMustBind)

/**
 * [Flow] over SELECT result rows that reads the result on [dispatcher], with optional validation
 * that each solution binds the given variables.
 *
 * - The result is read where blocking is allowed: on [dispatcher], whatever the collector runs on.
 *   Rows are handed over through the buffer of [flowOn].
 * - Cancellation reaches a read that blocks. When the collector is cancelled, the reading thread is
 *   interrupted, and a result that is [AutoCloseable] is closed, from another thread when
 *   [dispatcher] has one to spare. Either ends a read that waits for the network; a read that
 *   honours neither only ends when it returns. Whatever the interrupted read throws, the collector
 *   sees the cancellation it asked for.
 * - A result that is [AutoCloseable] is closed when the flow ends, however it ends: completion,
 *   failure or cancellation. A failure to close is not reported.
 * - The flow is cold and the result is consumed by collecting it: collect it once.
 *
 * @param dispatcher where the result is read; it must allow blocking calls.
 * @param eachRowMustBind SPARQL variable names **without** `?`; a row that leaves one unbound fails
 *   the flow with [IllegalStateException].
 */
fun SparqlQueryResult.asFlow(dispatcher: CoroutineDispatcher, vararg eachRowMustBind: String): Flow<BindingSet> {
    val required = eachRowMustBind.toSet()
    val result = this
    return flow {
        coroutineScope {
            // Suspended until this scope ends, by cancellation or because the rows are through. It then
            // closes the result on a thread of its own, which is what frees a reader blocked in the result.
            val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (result is AutoCloseable) runCatching { result.close() }
                }
            }
            val rows = result.asSequence().iterator()
            while (true) {
                val row = try {
                    // Interrupts the reading thread when the collector is cancelled.
                    runInterruptible { if (rows.hasNext()) rows.next() else null }
                } catch (failure: Throwable) {
                    // A read that was cut short by the cancellation fails in its own way (the stream was closed
                    // under it); the collector asked for the cancellation, so that is what it is told.
                    currentCoroutineContext().ensureActive()
                    throw failure
                } ?: break
                if (required.isNotEmpty()) {
                    val missing = required.filter { !row.hasBinding(it) }
                    if (missing.isNotEmpty()) {
                        throw IllegalStateException(
                            "SPARQL result row missing variable(s): $missing (row has: ${row.getVariableNames()})",
                        )
                    }
                }
                emit(row)
            }
            closer.cancel()
        }
    }.flowOn(dispatcher)
}
