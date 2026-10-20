package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.VersionedRdfGraph

/**
 * Marker for graphs whose `equals` / `hashCode` identify the live graph they denote ("handle equality"): two handles
 * are equal exactly when they denote the same graph of the same store (for example the same named graph of the same
 * repository instance), whatever the content of the graph is at the time. Both functions must be cheap, must not
 * read the graph, and must not block.
 *
 * [GraphStateCache] finds the state built for an equal handle when a handle is obtained anew for every call
 * (`repository.getGraph(name)`), and replaces that state in place when the content of the graph changed.
 */
@KastorGenInternalApi
interface HandleEqualGraph : RdfGraph

/**
 * A stamped graph that can tell whether its current [modificationStamp] is private to the calling thread's open
 * transaction, i.e. identifies content with **uncommitted** writes of that thread, which no other thread reads and
 * which stops existing when the transaction ends.
 *
 * [GraphStateCache] does not keep a state built for such a stamp (it could never be hit again once the transaction
 * ends, and no other thread may be served it): the caller gets a private state that is released when its use ends,
 * and the state cached for the committed content stays in place for the other threads.
 *
 * Implementing this interface is optional. A graph that does not implement it is still handled correctly as long as
 * its stamps follow the contract of [VersionedRdfGraph] (a stamp identifies the content the calling thread would read
 * now, and is never reused): the state of the graph is then rebuilt in place whenever the stamp a caller reads is not
 * one the cached state is known for. Providers that cannot implement this interface can be described with
 * [GraphStateCache.Settings.transactionPrivate] instead.
 */
@KastorGenInternalApi
interface TransactionScopedGraph : VersionedRdfGraph {
    /**
     * True when the value [modificationStamp] returns to the calling thread right now is private to its open
     * transaction. Must be cheap and must not block.
     */
    val isStampTransactionPrivate: Boolean
}

/**
 * Thrown by a validation when every cached and every temporary copy of the data graphs stayed in use for the whole
 * wait and the validator is configured not to build copies beyond its limit.
 */
class GraphStateCacheSaturatedException(message: String) : IllegalStateException(message)

/**
 * Thrown by a validation whose thread was interrupted while it waited for another caller (for the copy of the data
 * graph that another caller is building, or for a free copy). The interrupt status of the thread is set again before
 * this exception is thrown.
 */
class GraphStateCacheInterruptedException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

