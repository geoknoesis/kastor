package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.VersionedRdfGraph
import java.lang.ref.Reference
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.locks.ReentrantLock

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

/**
 * Bounded cache of a state derived from the content of data graphs (for example a SHACL engine's own copy of the
 * data), shared by the validation adapters. The state of a graph is rebuilt only when the graph is new to the cache
 * or its content changed.
 *
 * ## What a call costs
 * - **Graphs with a modification stamp** ([VersionedRdfGraph], e.g. `MemoryGraph`, the named graphs of the memory
 *   repository, and the graphs of RDF4J / Jena repositories that Kastor created): O(1) while the stamp the caller
 *   reads is one the state is known for; the triples are not read. When the stamp moved, the graph is read once and
 *   its content digest is compared with the one of the state: a stamp that moved because **another** graph of the
 *   same repository was written (stamps are usually repository-wide) only costs that read - the state is kept and
 *   the new stamp is recorded. The digest is computed on such a (re)load only, never on a hit.
 * - **Graphs without a stamp** (wrapped external stores, SPARQL-endpoint graphs, user-defined graphs): **every call
 *   reads the whole graph** (`getTriples()`, i.e. one full download of a remote graph) and computes a digest of every
 *   triple (one SHA-256 per triple), because nothing cheaper proves that the content is unchanged. Only the rebuild of
 *   the state (conversion, store writes, shape parsing) is saved when the digest is the one the state was built for.
 *   Validating N nodes of such a graph one call at a time therefore costs N full reads. Two ways to avoid that:
 *   - do the N validations inside **one** [use] block (the adapters' `validateAll(data, focuses)`): one read;
 *   - create the cache with [Settings.assumeImmutable]: a graph that is found again by its handle (the same instance,
 *     or an equal handle, see below) is **not read again**, its state is used as it is. The caller thereby promises
 *     that such graphs do not change while the cache lives; a change is not detected.
 *
 * ## Which cached state belongs to a graph
 * 1. **By handle.** The state last used by the same graph instance, or by an *equal handle*. Handle equality is an
 *    explicit contract, not something inferred from a foreign class: only a graph that overrides `equals` and
 *    `hashCode` **and** implements [VersionedRdfGraph] or [HandleEqualGraph], or is one of Kastor's own graph classes
 *    (package `com.geoknoesis.kastor.rdf`, whose `equals` is handle equality by convention), is compared with
 *    `equals`. Every other class (data classes, Java records, collections, ...) is matched by instance only. A state
 *    found by handle is current when the stamp (or the digest) is one it is known for, and is otherwise
 *    **replaced in place**: a changing graph keeps one state, not one per version.
 * 2. **By content** (graphs without a stamp only). A state built for the same digest is reused whichever handle it
 *    was built for, so fresh handles without handle equality of an unchanged graph hit the cache. Under
 *    [Settings.assumeImmutable] the handle is then remembered too, so that the next call finds it without a read.
 *
 * `equals` and `hashCode` of a graph are never called while a lock of this cache is held.
 *
 * A state is never used for content it was not built from (except under [Settings.assumeImmutable]): the stamp (or
 * the triples the digest is computed from) is read before the snapshot the state is built from, so a concurrent
 * change makes the next call rebuild.
 *
 * ## Transactions
 * The stamp, and the content when it is needed, are read **on the calling thread**, and a state is only ever served
 * to a caller whose own stamp it is known for. With stamps that follow the contract of [VersionedRdfGraph] - a stamp
 * identifies the content the calling thread would read now; a thread with uncommitted writes gets stamps that no
 * other thread sees and that are never handed out again; every other thread keeps the committed stamp until the
 * commit - this means:
 * - a writer inside its transaction is never served a state built from the committed content it has changed, and no
 *   other thread is ever served a state built from uncommitted content;
 * - when the provider tells that a stamp is transaction-private ([TransactionScopedGraph] or
 *   [Settings.transactionPrivate]), the state built for it is not cached: the writer uses the cached state when the
 *   content of this graph is the committed one (it wrote other graphs), else a private state that is released when
 *   its use ends. The cached state of the committed content is not touched, so the other threads keep hitting it;
 * - when the provider does not tell, the state of the graph is rebuilt in place for the writer and again for the
 *   next reader (one entry at all times, never one per stamp); a state left behind by a finished transaction is
 *   replaced by the next call;
 * - a failing stamp read (a closed repository) releases the state cached for that handle at once and is rethrown;
 *   a failing [Settings.transactionPrivate] predicate does not: the stamp is treated as private to the transaction.
 *
 * ## Locking and single-flight loads
 * The data graph is read (stamp, triples) only while this cache holds **no** lock, so [use] may be called while the
 * caller holds the graph's repository lock (inside `repository.transaction { }`) without risk of a lock-order
 * deadlock. Callers of the same graph are serialized on its entry while its state is (re)built - and for the whole
 * [use] block when the cache is [exclusive]; callers of different graphs do not wait for each other unless the cache
 * is saturated (see below).
 *
 * Reloads of a stamped graph are **single-flight**: the first caller that finds the state stale reads the graph; the
 * callers that arrive with the **same stamp** meanwhile wait for it and use its result instead of each reading the
 * graph. A caller with another stamp (it reads other content, e.g. inside its own transaction) does not wait: the
 * load in flight is of no use to it, and its owner may be waiting for a repository lock that this very caller holds.
 * For that same reason the wait is bounded by [Settings.loadWaitMillis], after which the caller reads the graph
 * itself: waiting for a load never deadlocks and never fails a caller that holds a repository lock (waiting for a
 * state slot under a strict limit is different, see Bound).
 *
 * ## Bound
 * At most [maxEntries] states are cached, plus at most [Settings.maxTemporaryStates] temporary ones:
 * - When a new graph arrives and the cache is full, an entry that no caller is using is released: first one whose
 *   handle was garbage collected, then the least recently used one.
 * - When every entry is in use, the new graph gets a private temporary state that is released when its [use] block
 *   ends. The number of temporary states is bounded too. When they are all in use, the call waits for a state to
 *   become free, for at most [Settings.overflowWaitMillis] (a short time: the users of those states may themselves
 *   be waiting for a repository lock that this caller holds), and then builds a private state **beyond** the limit,
 *   in the caller's thread, which is released when its use ends. With `overflowWaitMillis = null` the limit is
 *   strict: the call waits up to [Settings.temporaryWaitMillis] and then fails with
 *   [GraphStateCacheSaturatedException], and at most `maxEntries + maxTemporaryStates` states exist at any time.
 *   That wait is the same for a caller inside a transaction, which may hold its repository lock for all of it: it is
 *   long (10 s by default) and ends in a failure, so choose a small [Settings.temporaryWaitMillis] when callers
 *   hold repository locks and the limit is strict.
 *   Temporary states keep their slot until they are released. Evicting an idle entry is best-effort: the victim is
 *   released outside the lock (a release can be slow), so for that short time its replacement may coexist with it.
 * - Entries that can no longer be found by their handle are released without waiting for a cache miss, on the next
 *   call of [use] (or [size]): an entry whose handle was garbage collected and that was found by handle only (stamped
 *   graphs; handles with handle equality, which are softly referenced and so are dropped under memory pressure), and
 *   an entry identified by content whose handle was garbage collected and that has not been used for
 *   [Settings.orphanIdleMillis]. The latter rule bounds, by age, the obsolete versions that a changing graph without
 *   stamp and without handle equality leaves behind (each change creates a new entry: nothing links the new content
 *   to the old entry); until then they count towards [maxEntries] and are the first to be evicted.
 *
 * @param S the cached state.
 * @param maxEntries maximum number of cached states (at least 1).
 * @param exclusive true when a state supports one user at a time: the [use] block then runs under the entry lock and
 *   a rebuild may update the previous state in place. With false, blocks of the same graph run concurrently, a
 *   rebuild always creates a new state, and [release] of the replaced state may be called while a block still reads
 *   it.
 * @param load builds the state for a snapshot of a graph's triples; `previous` is the state to update in place
 *   (only when [exclusive]) or null. Must leave `previous` usable when it throws. Never called concurrently for one
 *   entry; it must not read the data graph.
 * @param release releases a state.
 * @param owner name used in error messages.
 * @param settings optional behaviour, see [Settings].
 * @param native optional loader for graphs that are snapshotted in their provider's own representation.
 */
@KastorGenInternalApi
class GraphStateCache<S : Any>(
    val maxEntries: Int,
    private val exclusive: Boolean,
    private val load: (triples: List<RdfTriple>, previous: S?) -> S,
    private val release: (S) -> Unit,
    private val owner: String,
    private val settings: Settings,
    private val native: NativeLoader<S>?,
) : AutoCloseable {

    /** A cache with the given [Settings] and no native loader. */
    constructor(
        maxEntries: Int,
        exclusive: Boolean,
        load: (triples: List<RdfTriple>, previous: S?) -> S,
        release: (S) -> Unit,
        owner: String = "GraphStateCache",
        settings: Settings = Settings(),
    ) : this(maxEntries, exclusive, load, release, owner, settings, null)

    /**
     * Optional behaviour of a [GraphStateCache].
     *
     * @param assumeImmutable the caller guarantees that graphs without a modification stamp do not change while the
     *   cache lives: a graph found again by its handle (same instance or equal handle) is not read again. Changes of
     *   such a graph are **not detected**. Graphs with a stamp are still checked (that is O(1)).
     * @param maxTemporaryStates maximum number of temporary states in use at a time when every cached entry is in
     *   use; null (the default) means [GraphStateCache.maxEntries].
     * @param temporaryWaitMillis with `overflowWaitMillis = null`: how long a call waits for a free state when the
     *   cache is saturated before it fails with [GraphStateCacheSaturatedException]; 0 fails at once.
     * @param orphanIdleMillis idle time after which an entry identified by content only, whose handle was garbage
     *   collected, is released.
     * @param overflowWaitMillis how long a call waits for a free state when the cache is saturated before it builds
     *   a private state beyond the limit (0: at once); null never builds one (see [temporaryWaitMillis]).
     * @param loadWaitMillis how long a call waits for the load that another caller is running for the same graph and
     *   the same stamp before it reads the graph itself.
     * @param transactionPrivate tells whether the stamp that a graph returns to the calling thread right now is
     *   private to its open transaction, for providers whose graphs cannot implement [TransactionScopedGraph]; null
     *   (the default) asks the graph when it implements that interface. Must be cheap and must not block.
     * @param probe observer of the cache's events, for tests and diagnostics.
     */
    @KastorGenInternalApi
    class Settings @JvmOverloads constructor(
        val assumeImmutable: Boolean = false,
        val maxTemporaryStates: Int? = null,
        val temporaryWaitMillis: Long = DEFAULT_TEMPORARY_WAIT_MILLIS,
        val orphanIdleMillis: Long = DEFAULT_ORPHAN_IDLE_MILLIS,
        val overflowWaitMillis: Long? = DEFAULT_OVERFLOW_WAIT_MILLIS,
        val loadWaitMillis: Long = DEFAULT_LOAD_WAIT_MILLIS,
        val transactionPrivate: ((VersionedRdfGraph) -> Boolean)? = null,
        val probe: Probe? = null,
    ) {
        init {
            require(maxTemporaryStates == null || maxTemporaryStates >= 0) { "maxTemporaryStates must not be negative, got $maxTemporaryStates" }
            require(temporaryWaitMillis >= 0) { "temporaryWaitMillis must not be negative, got $temporaryWaitMillis" }
            require(orphanIdleMillis >= 0) { "orphanIdleMillis must not be negative, got $orphanIdleMillis" }
            require(overflowWaitMillis == null || overflowWaitMillis >= 0) { "overflowWaitMillis must not be negative, got $overflowWaitMillis" }
            require(loadWaitMillis >= 0) { "loadWaitMillis must not be negative, got $loadWaitMillis" }
        }

        companion object {
            const val DEFAULT_TEMPORARY_WAIT_MILLIS: Long = 10_000
            const val DEFAULT_ORPHAN_IDLE_MILLIS: Long = 60_000
            const val DEFAULT_OVERFLOW_WAIT_MILLIS: Long = 250
            const val DEFAULT_LOAD_WAIT_MILLIS: Long = 2_000
        }
    }

    /** What a [Probe] is told. */
    @KastorGenInternalApi
    enum class Event {
        /** The content of a graph is about to be read (`getTriples()` or a native snapshot). */
        GRAPH_READ,

        /** A content digest was computed. */
        DIGEST,

        /** A state was built (including rebuilds). */
        LOAD,

        /** A reload found the content unchanged: the state was kept and the new stamp recorded. */
        STAMP_REFRESH,

        /** A call is served by a temporary state (within [Settings.maxTemporaryStates]). */
        TEMPORARY,

        /** A call is served by a private state beyond the limit of temporary states. */
        OVERFLOW,

        /** A call is about to wait for the load that another caller is running. */
        LOAD_WAIT,

        /** A call is about to wait for a free state. Delivered while a cache lock is held. */
        SLOT_WAIT,
    }

    /**
     * Observer of the events of a cache. It is called on the thread of the [use] call, for [Event.LOAD],
     * [Event.STAMP_REFRESH] and [Event.SLOT_WAIT] while a lock of the cache is held: it must not call the cache, and
     * should only block for [Event.GRAPH_READ] and [Event.LOAD_WAIT] (tests do, to order threads without polling).
     */
    @KastorGenInternalApi
    fun interface Probe {
        fun on(event: Event)
    }

    /**
     * Loads graphs from a snapshot in their provider's own representation, for graphs whose content cannot (or need
     * not) go through Kastor's term model - for example the graphs of a store that holds statements Kastor rejects.
     */
    @KastorGenInternalApi
    interface NativeLoader<S : Any> {
        /**
         * A snapshot of the content of [graph] that the calling thread reads now, or null when the graph is to be
         * read with `getTriples()`. Called while no lock of the cache is held.
         */
        fun snapshot(graph: RdfGraph): Any?

        /**
         * One string per triple of [snapshot], such that two snapshots have the same content exactly when they yield
         * the same strings (in any order). Used to find out whether a reload changed anything.
         */
        fun encode(snapshot: Any): Iterable<String>

        /** Builds the state for [snapshot]; see the `load` parameter of [GraphStateCache]. */
        fun load(snapshot: Any, previous: S?): S
    }

    init {
        require(maxEntries >= 1) { "maxEntries must be at least 1, got $maxEntries" }
    }

    private val maxTemporaries = settings.maxTemporaryStates ?: maxEntries
    private val orphanIdleNanos = TimeUnit.MILLISECONDS.toNanos(settings.orphanIdleMillis)

    /**
     * Guards [entries], [closed], [useCounter], [temporariesInUse] and the fields of entries other than `loaded`. No
     * code of a graph (`equals`, `hashCode`, reads) runs while it is held.
     */
    private val lock = ReentrantLock()

    /** Signalled when an entry becomes idle (evictable), a temporary state is released, or the cache is closed. */
    private val stateFreed = lock.newCondition()
    private val entries = ArrayList<Entry<S>>()
    private var closed = false
    private var useCounter = 0L
    private var temporariesInUse = 0

    private val digester = GraphDigester()
    private val counts = AtomicIntegerArray(Event.entries.size)

    /** Test hook: clock of the idle time of orphaned entries (nanoseconds). */
    @Volatile internal var clock: () -> Long = System::nanoTime

    /**
     * Test hook: the reference kept to a handle (`equal` handles are softly referenced so that they survive minor
     * collections, the others weakly). A cleared reference is how the cache learns that a handle was collected.
     */
    @Volatile internal var referenceFactory: (graph: RdfGraph, equal: Boolean) -> Reference<RdfGraph> =
        { graph, equal -> if (equal) SoftReference(graph) else WeakReference(graph) }

    private fun signal(event: Event) {
        counts.incrementAndGet(event.ordinal)
        settings.probe?.on(event)
    }

    /** Test hook: how often [event] occurred. */
    internal fun count(event: Event): Int = counts.get(event.ordinal)

    /** Test hook: number of states built so far (including rebuilds). */
    internal val loadCount: Int get() = count(Event.LOAD)

    /** Test hook: number of content digests computed so far. */
    internal val digestCount: Int get() = count(Event.DIGEST)

    /** Test hook: number of [use] calls served by a temporary state because every cached entry was in use. */
    internal val temporaryCount: Int get() = count(Event.TEMPORARY)

    /** Number of cached states. */
    val size: Int
        get() {
            val dead = ArrayList<Entry<S>>()
            val count = locked {
                sweep(dead)
                entries.size
            }
            releaseAll(dead, rethrow = false)
            return count
        }

    private inline fun <T> locked(action: () -> T): T {
        lock.lock()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }

    /**
     * What a state was built from: the content [digest], and for a stamped graph the modification [stamps] that are
     * known to identify that content (the newest last; a few are kept, because the stamp of a store moves when any
     * of its graphs changes and threads read it at different times).
     */
    private class Loaded<S>(val state: S, val stamps: LongArray?, val digest: GraphDigest) {
        fun isFor(stamp: Long?, digest: GraphDigest?, byContent: Boolean): Boolean =
            if (stamp != null && !byContent) stamps != null && stamp in stamps else this.digest == digest

        fun alsoFor(stamp: Long): Loaded<S> {
            val known = stamps ?: LongArray(0)
            val kept = if (known.size >= MAX_STAMPS) known.copyOfRange(known.size - MAX_STAMPS + 1, known.size) else known
            return Loaded(state, kept + stamp, digest)
        }
    }

    /** A handle an entry is found by. */
    private class HandleRef(val reference: Reference<RdfGraph>, val equal: Boolean, val hash: Int)

    /** A load of a stamped graph that a caller is running; the callers with the same [stamp] wait for [done]. */
    private class Flight(val stamp: Long) {
        val done = CountDownLatch(1)
    }

    private class Entry<S>(val temporary: Boolean, val counted: Boolean) {
        // Guarded by the cache lock.

        /** The handles this entry is found by; more than one only under [Settings.assumeImmutable]. */
        var handles: List<HandleRef> = emptyList()

        /** Whether the graph has a modification stamp (the entry is then found by handle only). */
        var stamped = false
        var lastUsed = 0L
        var lastUsedNanos = 0L
        var users = 0
        var retired = false
        var flight: Flight? = null

        /** Written under this entry's monitor; read without it for the content lookup and the staleness peek. */
        @Volatile var loaded: Loaded<S>? = null

        fun hasLiveHandle(): Boolean = handles.any { it.reference.get() != null }
    }

    /** The content of a graph as read by one caller: the triples, or a provider-native snapshot. */
    private class Snapshot(val triples: List<RdfTriple>?, val native: Any?)

    private fun read(data: RdfGraph): Snapshot {
        signal(Event.GRAPH_READ)
        val own = native?.snapshot(data)
        return if (own != null) Snapshot(null, own) else Snapshot(data.getTriples(), null)
    }

    private fun digestOf(snapshot: Snapshot): GraphDigest {
        val digest = if (snapshot.triples != null) {
            digester.digest(snapshot.triples)
        } else {
            digester.digestEncoded(checkNotNull(native).encode(checkNotNull(snapshot.native)))
        }
        signal(Event.DIGEST)
        return digest
    }

    private fun build(snapshot: Snapshot, previous: S?): S =
        if (snapshot.triples != null) {
            load(snapshot.triples, previous)
        } else {
            checkNotNull(native).load(checkNotNull(snapshot.native), previous)
        }

    private fun isTransactionPrivate(graph: VersionedRdfGraph): Boolean {
        val custom = settings.transactionPrivate
        return if (custom != null) custom(graph) else graph is TransactionScopedGraph && graph.isStampTransactionPrivate
    }

    /**
     * The answer of the provider's predicate. A predicate that throws says nothing about the store (it is not a stamp
     * read), so the cached state is kept; the stamp is then treated as private, which never serves or stores a state
     * for content that may be uncommitted.
     */
    private fun transactionPrivate(graph: VersionedRdfGraph): Boolean =
        try {
            isTransactionPrivate(graph)
        } catch (e: Exception) {
            System.getLogger(GraphStateCache::class.java.name)
                .log(System.Logger.Level.WARNING, "The transaction-private predicate failed; treating the stamp as private", e)
            true
        }

    /**
     * Runs [block] with the state of [data], building it first when [data] is new or changed. The state must not be
     * used after [block] returns. A graph without a modification stamp is read once per call (see the class
     * documentation): do all the work that needs one content version of the graph inside one block.
     *
     * @throws IllegalStateException when the cache is closed
     * @throws GraphStateCacheSaturatedException when the limit is strict ([Settings.overflowWaitMillis] is null) and
     *   every cached and temporary state stayed in use for [Settings.temporaryWaitMillis]
     * @throws GraphStateCacheInterruptedException when the calling thread is interrupted while it waits for another
     *   caller; its interrupt status is set again
     */
    fun <R> use(data: RdfGraph, block: (S) -> R): R {
        // 1. Read the graph (and run its hashCode) while holding no lock of this cache, on the calling thread: the
        //    stamp identifies what this thread reads.
        val handleEqual = HandleEquality.of(data)
        val hash = if (handleEqual) data.hashCode() else 0
        var stamp: Long? = null
        var ownTransaction = false
        if (data is VersionedRdfGraph) {
            try {
                stamp = data.modificationStamp
            } catch (failure: Exception) {
                // The store is gone for good (a closed repository): its copy must not stay until it is evicted.
                try {
                    pin(data, handleEqual, hash, null, Mode.DISCARD)
                } catch (secondary: Exception) {
                    failure.addSuppressed(secondary)
                }
                throw failure
            }
            ownTransaction = transactionPrivate(data)
        }
        var snapshot: Snapshot? = null
        var digest: GraphDigest? = null
        var trusted = false
        var pinned: Entry<S>? = null
        var flight: Flight? = null
        try {
            // 2. Pin the entry: an entry in use is never released or handed to another graph.
            if (ownTransaction) {
                // Uncommitted content of this thread's transaction: use the cached state when this graph's content
                // is the committed one, and never leave a state of that content in the cache.
                snapshot = read(data)
                digest = digestOf(snapshot)
                val committed = pin(data, handleEqual, hash, null, Mode.LOADED)
                if (committed != null) {
                    if (committed.loaded?.digest == digest) pinned = committed else unpin(committed)
                }
                if (pinned == null) pinned = pin(data, handleEqual, hash, null, Mode.DETACHED)
            } else {
                if (stamp == null && settings.assumeImmutable) {
                    pinned = pin(data, handleEqual, hash, null, Mode.LOADED)
                    trusted = pinned != null
                }
                if (pinned == null) {
                    if (stamp == null) {
                        snapshot = read(data)
                        digest = digestOf(snapshot)
                    }
                    pinned = pin(data, handleEqual, hash, digest, Mode.SHARED)
                }
            }
            var solo = false
            while (true) {
                val entry: Entry<S> = pinned ?: error("no entry was pinned")
                // 3. A stale entry needs a snapshot, which is also taken without holding a lock - by one caller: the
                //    others that read the same stamp wait for it.
                val peek = entry.loaded
                if (snapshot == null && (peek == null || !(trusted || peek.isFor(stamp, digest, ownTransaction)))) {
                    if (stamp != null && !solo && !entry.temporary) {
                        val ownStamp: Long = stamp
                        val inFlight = locked {
                            entry.flight ?: Flight(ownStamp).also {
                                entry.flight = it
                                flight = it
                            }
                        }
                        if (inFlight !== flight) {
                            if (inFlight.stamp == ownStamp && awaitFlight(inFlight)) continue
                            // A load for other content, or one that takes too long: read the graph in this call.
                            solo = true
                        }
                    }
                    snapshot = read(data)
                    digest = digestOf(snapshot)
                    trusted = false
                }
                if (exclusive) {
                    synchronized(entry) {
                        val state = try {
                            ensure(entry, stamp, digest, snapshot, trusted, ownTransaction)
                        } finally {
                            flight?.let { endFlight(entry, it) }
                            flight = null
                        }
                        if (state != null) return block(state)
                    }
                } else {
                    val state = try {
                        synchronized(entry) { ensure(entry, stamp, digest, snapshot, trusted, ownTransaction) }
                    } finally {
                        flight?.let { endFlight(entry, it) }
                        flight = null
                    }
                    if (state != null) return block(state)
                }
                if (ownTransaction && !entry.temporary) {
                    // The committed state was replaced since the peek: a transaction never loads into a shared entry.
                    pinned = null
                    unpin(entry)
                    pinned = pin(data, handleEqual, hash, null, Mode.DETACHED)
                }
                // Otherwise another caller rebuilt the entry for a different stamp since the peek: retry.
            }
        } finally {
            pinned?.let { entry ->
                flight?.let { endFlight(entry, it) }
                unpin(entry)
            }
        }
    }

    /** Waits for [flight]; false when the wait timed out (the caller then reads the graph itself). */
    private fun awaitFlight(flight: Flight): Boolean {
        signal(Event.LOAD_WAIT)
        try {
            return flight.done.await(settings.loadWaitMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            throw interrupted("the load of the same graph by another caller", e)
        }
    }

    private fun endFlight(entry: Entry<S>, flight: Flight) {
        locked {
            if (entry.flight === flight) entry.flight = null
        }
        flight.done.countDown()
    }

    private fun interrupted(what: String, cause: InterruptedException): GraphStateCacheInterruptedException {
        Thread.currentThread().interrupt()
        return GraphStateCacheInterruptedException("$owner: interrupted while waiting for $what", cause)
    }

    /**
     * The current state of [entry], (re)built from [snapshot] when stale; null when stale and there is no snapshot,
     * or when the caller reads transaction-private content ([byContent]) that must not go into a shared entry.
     */
    private fun ensure(
        entry: Entry<S>,
        stamp: Long?,
        digest: GraphDigest?,
        snapshot: Snapshot?,
        trusted: Boolean,
        byContent: Boolean,
    ): S? {
        val current = entry.loaded
        if (current != null && (trusted || current.isFor(stamp, digest, byContent))) return current.state
        if (snapshot == null || digest == null) return null
        if (byContent && !entry.temporary) return null
        if (current != null && stamp != null && current.stamps != null && current.digest == digest) {
            // The stamp moved, the content of this graph did not (another graph of the store was written, or this
            // thread's transaction wrote elsewhere): keep the state and remember that this stamp denotes it too.
            entry.loaded = current.alsoFor(stamp)
            signal(Event.STAMP_REFRESH)
            return current.state
        }
        val previous = if (exclusive) current?.state else null
        val state = build(snapshot, previous)
        entry.loaded = Loaded(state, if (stamp != null && !byContent) longArrayOf(stamp) else null, digest)
        signal(Event.LOAD)
        if (current != null && current.state !== state) releaseQuietly(current.state)
        return state
    }

    private enum class Mode {
        /** The entry of the graph: found by handle, then by content, else a new, a temporary or a private one. */
        SHARED,

        /** The entry found by handle, and only when it holds a state; nothing is created. */
        LOADED,

        /** A private state for the content of a transaction: a temporary one, never a wait for it. */
        DETACHED,

        /** Removes the entry found by handle (its state is released once nobody uses it); nothing is pinned. */
        DISCARD,
    }

    /** Pins the entry of [data] according to [mode]; null when [mode] found (or wanted) none. */
    private fun pin(data: RdfGraph, handleEqual: Boolean, hash: Int, digest: GraphDigest?, mode: Mode): Entry<S>? {
        // The entry whose handle was found equal to [data] (outside the lock), and that handle.
        var verified: Entry<S>? = null
        var verifiedRef: HandleRef? = null
        // Entries whose handles were compared with [data] and are not equal.
        var rejected: HashSet<Entry<S>>? = null
        var deadline = Long.MIN_VALUE
        while (true) {
            val evicted = ArrayList<Entry<S>>()
            var candidates: List<Triple<Entry<S>, HandleRef, RdfGraph>>? = null
            var pinned: Entry<S>? = null
            var miss = false
            var event: Event? = null
            lock.lock()
            try {
                check(!closed) { "$owner has been closed" }
                sweep(evicted)
                var found: Entry<S>? = null
                if (mode != Mode.DETACHED) {
                    found = entries.firstOrNull { e -> e.handles.any { it.reference.get() === data } }
                    if (found == null) found = verified?.takeIf { !it.retired }
                    if (found == null && handleEqual) {
                        // Equal handles are compared outside the lock; an entry is created only once every entry that
                        // could be equal was examined, so two equal handles never get two entries.
                        val unexamined = ArrayList<Triple<Entry<S>, HandleRef, RdfGraph>>()
                        for (e in entries) {
                            if (rejected?.contains(e) == true) continue
                            for (ref in e.handles) {
                                if (!ref.equal || ref.hash != hash) continue
                                val handle = ref.reference.get() ?: continue
                                if (handle.javaClass === data.javaClass) unexamined += Triple(e, ref, handle)
                            }
                        }
                        if (unexamined.isNotEmpty()) candidates = unexamined
                    }
                }
                if (candidates == null) {
                    val byHandle = found != null
                    var created = false
                    when (mode) {
                        Mode.DISCARD -> {
                            if (found != null) {
                                entries.remove(found)
                                found.retired = true
                                if (found.users == 0) evicted += found
                                found = null
                            }
                            miss = true
                        }
                        Mode.LOADED -> {
                            if (found?.loaded == null) {
                                found = null
                                miss = true
                            }
                        }
                        Mode.SHARED, Mode.DETACHED -> {
                            // A state built for the same content is current whichever handle it was built for.
                            if (found == null && digest != null) {
                                found = entries.firstOrNull { !it.stamped && it.loaded?.digest == digest }
                            }
                            if (found == null && mode == Mode.SHARED) {
                                if (entries.size >= maxEntries) {
                                    val idle = entries.filter { it.users == 0 }
                                    // Prefer an entry no handle refers to any more, then the least recently used one.
                                    val victim = idle.filter { !it.hasLiveHandle() }.minByOrNull { it.lastUsed }
                                        ?: idle.minByOrNull { it.lastUsed }
                                    if (victim != null) {
                                        entries.remove(victim)
                                        victim.retired = true
                                        evicted += victim
                                    }
                                }
                                if (entries.size < maxEntries) {
                                    found = Entry<S>(temporary = false, counted = false).also { entries += it }
                                    created = true
                                }
                            }
                            if (found == null) {
                                val overflowWait = settings.overflowWaitMillis
                                if (temporariesInUse < maxTemporaries) {
                                    // Every entry is in use: validate in a private store rather than wait for one.
                                    temporariesInUse++
                                    event = Event.TEMPORARY
                                    found = Entry(temporary = true, counted = true)
                                } else if (mode == Mode.DETACHED && overflowWait != null) {
                                    // Inside a transaction the caller holds its store's lock: never make it wait.
                                    event = Event.OVERFLOW
                                    found = Entry(temporary = true, counted = false)
                                } else {
                                    // Saturated: wait (bounded) for an entry to become idle or a temporary state to
                                    // be released; then build a private state beyond the limit, or fail.
                                    val now = System.nanoTime()
                                    if (deadline == Long.MIN_VALUE) {
                                        deadline = now + TimeUnit.MILLISECONDS.toNanos(overflowWait ?: settings.temporaryWaitMillis)
                                    }
                                    val remaining = deadline - now
                                    if (remaining > 0) {
                                        signal(Event.SLOT_WAIT)
                                        try {
                                            stateFreed.awaitNanos(remaining)
                                        } catch (e: InterruptedException) {
                                            throw interrupted("a free state", e)
                                        }
                                    } else if (overflowWait != null) {
                                        event = Event.OVERFLOW
                                        found = Entry(temporary = true, counted = false)
                                    } else {
                                        throw GraphStateCacheSaturatedException(saturatedMessage())
                                    }
                                }
                            }
                        }
                    }
                    if (found != null) {
                        if (!found.temporary && found.handles.none { it.reference.get() === data }) {
                            val live = found.handles.filter { it.reference.get() != null }
                            if (created || live.isEmpty()) {
                                found.handles = listOf(HandleRef(referenceFactory(data, handleEqual), handleEqual, hash))
                                if (created) found.stamped = data is VersionedRdfGraph
                            } else if (byHandle) {
                                // The newest of the equal handles replaces the one it was found equal to.
                                val fresh = HandleRef(referenceFactory(data, handleEqual), handleEqual, hash)
                                found.handles = (live.filter { it !== verifiedRef } + fresh).takeLast(MAX_HANDLES)
                            } else if (settings.assumeImmutable) {
                                // Found by content: a second graph with the same content. Neither changes, so both
                                // handles denote this state, and the next call finds it without reading the graph.
                                val fresh = HandleRef(referenceFactory(data, handleEqual), handleEqual, hash)
                                found.handles = (live + fresh).takeLast(MAX_HANDLES)
                            }
                        }
                        found.lastUsed = ++useCounter
                        found.lastUsedNanos = clock()
                        found.users++
                        pinned = found
                    }
                }
            } finally {
                lock.unlock()
                // Outside the lock; nobody uses an evicted entry, so this never waits for a validation.
                releaseAll(evicted, rethrow = false)
            }
            event?.let(::signal)
            if (pinned != null) return pinned
            if (miss) return null
            candidates?.let { list ->
                val match = list.firstOrNull { (_, _, handle) -> handle == data }
                if (match != null) {
                    verified = match.first
                    verifiedRef = match.second
                } else {
                    val set = rejected ?: HashSet<Entry<S>>().also { rejected = it }
                    list.forEach { set += it.first }
                }
            }
        }
    }

    private fun saturatedMessage(): String =
        "$owner is saturated: all $maxEntries cached states and all $maxTemporaries temporary states are in use and " +
            "none became free within ${settings.temporaryWaitMillis} ms. More than ${maxEntries + maxTemporaries} " +
            "distinct graphs are being used at the same time: raise the cache size (maxCachedGraphs), or reduce the " +
            "number of concurrent callers"

    private fun unpin(entry: Entry<S>) {
        val holdsTemporarySlot = entry.temporary && entry.counted
        val release = locked {
            entry.users--
            val idle = entry.users == 0
            // A temporary state frees its slot only after it is released (below), so a waiter never builds its
            // replacement while this state is still alive.
            if (idle && !holdsTemporarySlot) stateFreed.signalAll()
            idle && (entry.retired || entry.temporary)
        }
        try {
            if (release) releaseAll(listOf(entry), rethrow = false)
        } finally {
            if (holdsTemporarySlot && release) locked {
                temporariesInUse--
                stateFreed.signalAll()
            }
        }
    }

    /**
     * Removes the entries that can no longer be found by their handle (see "Bound" in the class documentation) and
     * adds those that can be released now to [released]; entries in use are released by their last user. Holds [lock].
     */
    private fun sweep(released: MutableList<Entry<S>>) {
        var now = 0L
        var timed = false
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.handles.isEmpty() || entry.hasLiveHandle()) continue
            // In use: the handle its caller holds may no longer be the one tracked (a newer equal handle replaced it),
            // so "no live handle" proves nothing. Retiring it would stop it counting against the bounds and have the
            // next equal handle rebuild the state; a later sweep drops it once it is idle.
            if (entry.users > 0) continue
            // Found by handle only: no later call can hit it (an equal handle reloads; that reference is soft, so
            // it is cleared under memory pressure, which is when a copy of a store should go).
            var drop = entry.stamped || entry.handles.any { it.equal } || entry.loaded == null
            if (!drop) {
                // Identified by content: a fresh handle with the same content still hits it, so it is kept for a
                // while; an obsolete version of a changing graph is never hit again and expires.
                if (!timed) {
                    now = clock()
                    timed = true
                }
                drop = now - entry.lastUsedNanos >= orphanIdleNanos
            }
            if (drop) {
                iterator.remove()
                entry.retired = true
                released += entry
            }
        }
    }

    /** Releases the states of [released] entries; every entry is attempted even when a release throws. */
    private fun releaseAll(released: List<Entry<S>>, rethrow: Boolean) {
        var failure: Throwable? = null
        for (entry in released) {
            val state = synchronized(entry) { entry.loaded.also { entry.loaded = null } }?.state ?: continue
            try {
                release(state)
            } catch (e: Throwable) {
                if (rethrow) {
                    failure?.addSuppressed(e) ?: run { failure = e }
                } else {
                    logReleaseFailure(e)
                }
            }
        }
        failure?.let { throw it }
    }

    private fun releaseQuietly(state: S) {
        try {
            release(state)
        } catch (e: Throwable) {
            logReleaseFailure(e)
        }
    }

    private fun logReleaseFailure(e: Throwable) {
        // A failure to release an evicted state must not fail the validation of another graph.
        System.getLogger(GraphStateCache::class.java.name)
            .log(System.Logger.Level.WARNING, "Failed to release a cached validation state", e)
    }

    /**
     * Releases every cached state. States in use are released when their last [use] block ends; this call does not
     * wait for them. A failing release does not prevent the other releases; the first failure is thrown afterwards
     * with the others suppressed. Further [use] calls (and calls waiting for a free state) throw
     * [IllegalStateException].
     */
    override fun close() {
        val idle = locked {
            closed = true
            val all = entries.toList()
            entries.clear()
            all.forEach { it.retired = true }
            stateFreed.signalAll()
            all.filter { it.users == 0 }
        }
        releaseAll(idle, rethrow = true)
    }

    companion object {
        /** How many stamps are remembered for one state, and how many handles for one entry. */
        private const val MAX_STAMPS = 4
        private const val MAX_HANDLES = 8

        private val reportedSettings = ConcurrentHashMap.newKeySet<String>()

        /** Test hook: receives the warnings of [configuredMaxEntries] instead of the logger. */
        @Volatile internal var warningSink: ((String) -> Unit)? = null

        /** Test hook: forgets which invalid settings were already reported. */
        internal fun resetReportedSettings() {
            reportedSettings.clear()
        }

        /**
         * The cache size configured with the system property [property]: a positive integer. When the property is
         * not set, [default] is used. Any other value (`0`, `abc`, ...) is **not** silently ignored: it is reported
         * with one WARN entry on the `System.Logger` named after this class (once per property and value) and
         * [default] is used.
         */
        @JvmStatic
        fun configuredMaxEntries(property: String, default: Int): Int {
            val raw = System.getProperty(property) ?: return default
            val value = raw.trim().toIntOrNull()
            if (value != null && value >= 1) return value
            if (reportedSettings.add("$property=$raw")) {
                val message =
                    "Ignoring the system property $property=\"$raw\": it must be a positive integer. Using the default, $default."
                val sink = warningSink
                if (sink != null) {
                    sink(message)
                } else {
                    System.getLogger(GraphStateCache::class.java.name).log(System.Logger.Level.WARNING, message)
                }
            }
            return default
        }
    }
}
