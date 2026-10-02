package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.VersionedRdfGraph
import java.lang.ref.Reference
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
interface HandleEqualGraph : RdfGraph

/** Thrown by [GraphStateCache.use] when every cached and every temporary state stays in use for the whole wait. */
class GraphStateCacheSaturatedException(message: String) : IllegalStateException(message)

/**
 * Bounded cache of a state derived from the content of data graphs (for example a SHACL engine's own copy of the
 * data), shared by the validation adapters. The state of a graph is rebuilt only when the graph is new to the cache
 * or its content changed.
 *
 * ## What a call costs
 * - **Graphs with a modification stamp** ([VersionedRdfGraph], e.g. `MemoryGraph`, the named graphs of the memory
 *   repository, and the graphs of RDF4J / Jena repositories that Kastor created): O(1) while the stamp is the one the
 *   state was built for; the triples are not read.
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
 *    found by handle is current when the stamp (or the digest) is the one it was built for, and is otherwise
 *    **replaced in place**: a changing graph keeps one state, not one per version.
 * 2. **By content** (graphs without a stamp only). A state built for the same digest is reused whichever handle it
 *    was built for, so fresh handles without handle equality of an unchanged graph hit the cache.
 *
 * `equals` and `hashCode` of a graph are never called while a lock of this cache is held.
 *
 * A state is never used for content it was not built from (except under [Settings.assumeImmutable]): the stamp (or
 * the triples the digest is computed from) is read before the snapshot the state is built from, so a concurrent
 * change makes the next call rebuild.
 *
 * ## Locking
 * The data graph is read (stamp, triples) only while this cache holds **no** lock, so [use] may be called while the
 * caller holds the graph's repository lock (inside `repository.transaction { }`) without risk of a lock-order
 * deadlock. Callers of the same graph are serialized on its entry while its state is (re)built - and for the whole
 * [use] block when the cache is [exclusive]; callers of different graphs do not wait for each other unless the cache
 * is saturated (see below).
 *
 * ## Bound
 * At most [maxEntries] states are cached, plus at most [Settings.maxTemporaryStates] temporary ones:
 * - When a new graph arrives and the cache is full, an entry that no caller is using is released: first one whose
 *   handle was garbage collected, then the least recently used one.
 * - When every entry is in use, the new graph gets a private temporary state that is released when its [use] block
 *   ends. The number of temporary states is bounded too: when they are all in use, the call waits up to
 *   [Settings.temporaryWaitMillis] for a state to become free and then fails with
 *   [GraphStateCacheSaturatedException]. At most `maxEntries + maxTemporaryStates` states exist at any time.
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
 */
class GraphStateCache<S : Any>(
    val maxEntries: Int,
    private val exclusive: Boolean,
    private val load: (triples: List<RdfTriple>, previous: S?) -> S,
    private val release: (S) -> Unit,
    private val owner: String,
    private val settings: Settings,
) : AutoCloseable {

    /** A cache with the default [Settings]. */
    constructor(
        maxEntries: Int,
        exclusive: Boolean,
        load: (triples: List<RdfTriple>, previous: S?) -> S,
        release: (S) -> Unit,
        owner: String = "GraphStateCache",
    ) : this(maxEntries, exclusive, load, release, owner, Settings())

    /**
     * Optional behaviour of a [GraphStateCache].
     *
     * @param assumeImmutable the caller guarantees that graphs without a modification stamp do not change while the
     *   cache lives: a graph found again by its handle (same instance or equal handle) is not read again. Changes of
     *   such a graph are **not detected**. Graphs with a stamp are still checked (that is O(1)).
     * @param maxTemporaryStates maximum number of temporary states in use at a time when every cached entry is in
     *   use; null (the default) means [GraphStateCache.maxEntries]. 0 disables temporary states.
     * @param temporaryWaitMillis how long a call waits for a free state when the cache is saturated before it fails
     *   with [GraphStateCacheSaturatedException]; 0 fails at once.
     * @param orphanIdleMillis idle time after which an entry identified by content only, whose handle was garbage
     *   collected, is released.
     */
    class Settings @JvmOverloads constructor(
        val assumeImmutable: Boolean = false,
        val maxTemporaryStates: Int? = null,
        val temporaryWaitMillis: Long = DEFAULT_TEMPORARY_WAIT_MILLIS,
        val orphanIdleMillis: Long = DEFAULT_ORPHAN_IDLE_MILLIS,
    ) {
        init {
            require(maxTemporaryStates == null || maxTemporaryStates >= 0) { "maxTemporaryStates must not be negative, got $maxTemporaryStates" }
            require(temporaryWaitMillis >= 0) { "temporaryWaitMillis must not be negative, got $temporaryWaitMillis" }
            require(orphanIdleMillis >= 0) { "orphanIdleMillis must not be negative, got $orphanIdleMillis" }
        }

        companion object {
            const val DEFAULT_TEMPORARY_WAIT_MILLIS: Long = 10_000
            const val DEFAULT_ORPHAN_IDLE_MILLIS: Long = 60_000
        }
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
    private val loads = AtomicInteger()
    private val digests = AtomicInteger()
    private val temporaries = AtomicInteger()

    /** Test hook: clock of the idle time of orphaned entries (nanoseconds). */
    @Volatile internal var clock: () -> Long = System::nanoTime
    /** Test hook: reference to the handle of an entry found by handle equality (soft, so that it survives minor GCs). */
    @Volatile internal var equalHandleReference: (RdfGraph) -> Reference<RdfGraph> = { SoftReference(it) }

    /** Number of states built so far (including rebuilds). */
    val loadCount: Int get() = loads.get()

    /**
     * Number of content digests computed so far: one per [use] of a graph without a modification stamp, each after a
     * full read of the graph (none for a graph found by handle under [Settings.assumeImmutable]).
     */
    val digestCount: Int get() = digests.get()

    /** Number of [use] calls served by a private temporary state because every cached entry was in use. */
    val temporaryCount: Int get() = temporaries.get()

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

    /** What a state was built from: the modification stamp of a stamped graph, else the content digest. */
    private class Loaded<S>(val state: S, val stamp: Long?, val digest: GraphDigest?) {
        fun isFor(stamp: Long?, digest: GraphDigest?): Boolean =
            if (stamp != null) this.stamp == stamp else this.digest != null && this.digest == digest
    }

    private class Entry<S>(val temporary: Boolean) {
        // Guarded by the cache lock.
        var handle: Reference<RdfGraph>? = null
        /** Whether [handle] is compared with `equals` (it is then softly referenced), and its hash code. */
        var handleEqual = false
        var handleHash = 0
        var lastUsed = 0L
        var lastUsedNanos = 0L
        var users = 0
        var retired = false

        /** Written under this entry's monitor; read without it for the content lookup and the staleness peek. */
        @Volatile var loaded: Loaded<S>? = null
    }

    /**
     * Runs [block] with the state of [data], building it first when [data] is new or changed. The state must not be
     * used after [block] returns. A graph without a modification stamp is read once per call (see the class
     * documentation): do all the work that needs one content version of the graph inside one block.
     *
     * @throws IllegalStateException when the cache is closed
     * @throws GraphStateCacheSaturatedException when every cached and temporary state stayed in use for
     *   [Settings.temporaryWaitMillis]
     */
    fun <R> use(data: RdfGraph, block: (S) -> R): R {
        // 1. Read the graph (and run its hashCode) while holding no lock of this cache.
        val handleEqual = HandleEquality.of(data)
        val hash = if (handleEqual) data.hashCode() else 0
        val stamp = (data as? VersionedRdfGraph)?.modificationStamp
        var triples: List<RdfTriple>? = null
        var digest: GraphDigest? = null
        // 2. Pin the entry: an entry in use is never released or handed to another graph.
        var trusted = false
        var found: Entry<S>? = null
        if (stamp == null && settings.assumeImmutable) {
            found = pin(data, handleEqual, hash, digest = null, onlyLoaded = true)
            trusted = found != null
        }
        if (found == null) {
            if (stamp == null) {
                triples = data.getTriples()
                digest = digester.digest(triples)
                digests.incrementAndGet()
            }
            found = pin(data, handleEqual, hash, digest, onlyLoaded = false)
        }
        val entry: Entry<S> = found ?: error("no entry was pinned")
        try {
            while (true) {
                // 3. A stale entry needs a snapshot, which is also taken without holding a lock.
                val peek = entry.loaded
                if (triples == null && (peek == null || !(trusted || peek.isFor(stamp, digest)))) {
                    triples = data.getTriples()
                    if (stamp == null) {
                        digest = digester.digest(triples)
                        digests.incrementAndGet()
                        trusted = false
                    }
                }
                if (exclusive) {
                    synchronized(entry) {
                        val state = ensure(entry, stamp, digest, triples, trusted)
                        if (state != null) return block(state)
                    }
                } else {
                    val state = synchronized(entry) { ensure(entry, stamp, digest, triples, trusted) }
                    if (state != null) return block(state)
                }
                // Another caller rebuilt the entry for a different stamp since the peek: take a snapshot and retry.
            }
        } finally {
            unpin(entry)
        }
    }

    /** The current state of [entry], (re)built from [triples] when stale; null when stale and there is no snapshot. */
    private fun ensure(entry: Entry<S>, stamp: Long?, digest: GraphDigest?, triples: List<RdfTriple>?, trusted: Boolean): S? {
        val current = entry.loaded
        if (current != null && (trusted || current.isFor(stamp, digest))) return current.state
        if (triples == null) return null
        val previous = if (exclusive) current?.state else null
        val state = load(triples, previous)
        entry.loaded = Loaded(state, stamp, digest)
        loads.incrementAndGet()
        if (current != null && current.state !== state) releaseQuietly(current.state)
        return state
    }

    /**
     * Pins the entry of [data] (found by handle, then by content, else a new or temporary one). With [onlyLoaded] the
     * entry must be found by handle and hold a state; null is returned otherwise and nothing is created.
     */
    private fun pin(data: RdfGraph, handleEqual: Boolean, hash: Int, digest: GraphDigest?, onlyLoaded: Boolean): Entry<S>? {
        /** The entry whose handle was found equal to [data] (outside the lock). */
        var verified: Entry<S>? = null
        /** Entries whose handle was compared with [data] and is not equal. */
        var rejected: HashSet<Entry<S>>? = null
        var deadline = Long.MIN_VALUE
        while (true) {
            val evicted = ArrayList<Entry<S>>()
            var candidates: List<Pair<Entry<S>, RdfGraph>>? = null
            var pinned: Entry<S>? = null
            var miss = false
            lock.lock()
            try {
                check(!closed) { "$owner has been closed" }
                sweep(evicted)
                var found = entries.firstOrNull { it.handle?.get() === data }
                if (found == null) found = verified?.takeIf { !it.retired }
                if (found == null && handleEqual) {
                    // Equal handles are compared outside the lock; an entry is created only once every entry that
                    // could be equal was examined, so two equal handles never get two entries.
                    val unexamined = entries.mapNotNull { e ->
                        if (!e.handleEqual || e.handleHash != hash || rejected?.contains(e) == true) return@mapNotNull null
                        e.handle?.get()?.takeIf { it.javaClass === data.javaClass }?.let { e to it }
                    }
                    if (unexamined.isNotEmpty()) candidates = unexamined
                }
                if (candidates == null) {
                    val byHandle = found != null
                    // A state built for the same content is current whichever handle it was built for.
                    if (found == null && digest != null) found = entries.firstOrNull { it.loaded?.digest == digest }
                    var created = false
                    if (onlyLoaded) {
                        if (!byHandle || found?.loaded == null) {
                            found = null
                            miss = true
                        }
                    } else if (found == null) {
                        if (entries.size >= maxEntries) {
                            val idle = entries.filter { it.users == 0 }
                            // Prefer an entry no handle refers to any more, then the least recently used one.
                            val victim = idle.filter { it.handle?.get() == null }.minByOrNull { it.lastUsed }
                                ?: idle.minByOrNull { it.lastUsed }
                            if (victim != null) {
                                entries.remove(victim)
                                victim.retired = true
                                evicted += victim
                            }
                        }
                        if (entries.size < maxEntries) {
                            found = Entry<S>(temporary = false).also { entries += it }
                            created = true
                        } else if (temporariesInUse < maxTemporaries) {
                            // Every entry is in use: validate in a private store rather than wait for one of them.
                            temporariesInUse++
                            temporaries.incrementAndGet()
                            found = Entry(temporary = true)
                        } else {
                            // Saturated: wait (bounded) for an entry to become idle or a temporary state to be released.
                            val now = System.nanoTime()
                            if (deadline == Long.MIN_VALUE) deadline = now + TimeUnit.MILLISECONDS.toNanos(settings.temporaryWaitMillis)
                            val remaining = deadline - now
                            if (remaining <= 0) throw GraphStateCacheSaturatedException(saturatedMessage())
                            stateFreed.awaitNanos(remaining)
                        }
                    }
                    if (found != null) {
                        // Remember the newest handle: an equal one, or one that replaces a collected handle.
                        val currentHandle = found.handle?.get()
                        if (!found.temporary && currentHandle !== data && (currentHandle == null || byHandle || created)) {
                            found.handle = if (handleEqual) equalHandleReference(data) else WeakReference(data)
                            found.handleEqual = handleEqual
                            found.handleHash = hash
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
            if (pinned != null) return pinned
            if (miss) return null
            candidates?.let { list ->
                val match = list.firstOrNull { (_, handle) -> handle == data }
                if (match != null) {
                    verified = match.first
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
        val release = locked {
            entry.users--
            val idle = entry.users == 0
            if (idle) {
                if (entry.temporary) temporariesInUse--
                stateFreed.signalAll()
            }
            idle && (entry.retired || entry.temporary)
        }
        if (release) releaseAll(listOf(entry), rethrow = false)
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
            val handle = entry.handle ?: continue
            if (handle.get() != null) continue
            // Found by handle only: no later call can hit it (an equal handle reloads; that reference is soft, so
            // it is cleared under memory pressure, which is when a copy of a store should go).
            var drop = entry.handleEqual || entry.loaded?.digest == null
            if (!drop && entry.users == 0) {
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
                if (entry.users == 0) released += entry
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
        private val reportedSettings = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
                System.getLogger(GraphStateCache::class.java.name).log(
                    System.Logger.Level.WARNING,
                    "Ignoring the system property $property=\"$raw\": it must be a positive integer. Using the default, $default.",
                )
            }
            return default
        }
    }

    /**
     * Whether a graph is compared with `equals` (handle equality) rather than by instance. This is an explicit
     * contract (see the class documentation), never inferred from the shape of a foreign class: a data class, a Java
     * record or a collection is not handle-equal however its `equals` is declared.
     */
    internal object HandleEquality : ClassValue<Boolean>() {
        private const val KASTOR_RDF_PACKAGE = "com.geoknoesis.kastor.rdf."

        fun of(graph: RdfGraph): Boolean = get(graph.javaClass)

        override fun computeValue(type: Class<*>): Boolean {
            val declared = VersionedRdfGraph::class.java.isAssignableFrom(type) ||
                HandleEqualGraph::class.java.isAssignableFrom(type) ||
                type.name.startsWith(KASTOR_RDF_PACKAGE)
            if (!declared) return false
            // Without an override the graph is identified by instance, which needs no equals call.
            return try {
                type.getMethod("equals", Any::class.java).declaringClass != Any::class.java &&
                    type.getMethod("hashCode").declaringClass != Any::class.java
            } catch (e: ReflectiveOperationException) {
                false
            } catch (e: SecurityException) {
                false
            }
        }
    }

    /** Content digest of a graph, see [GraphDigester]. Digests of different digesters are not comparable. */
    internal data class GraphDigest(val w0: Long, val w1: Long, val w2: Long, val w3: Long, val count: Long)

    /**
     * Computes the content digest of a graph: the sum modulo 2^256 of `SHA-256(salt || encoding(triple))` over the
     * triples, plus the triple count. The encoding of a triple is unambiguous (term kind tags, length-prefixed
     * values), and the sum makes the digest independent of the order in which a store returns its triples, in one
     * pass and without sorting.
     *
     * ## What the digest guarantees
     * Two different contents produced without knowledge of [salt] have the same digest with negligible probability
     * (unlike a sum of `hashCode()`s, where e.g. the literals `"Aa"` and `"BB"` collide).
     *
     * A sum of hashes is, on its own, **not** collision resistant against crafted data: with unsalted hashes anyone
     * can compute the per-triple values offline and solve for a second set of triples with the same sum (the
     * generalised birthday attack on additive hashes, far below 2^128 work for a 256-bit sum). The salt closes that
     * off: it is 32 random bytes drawn per digester (per cache instance, never exposed, never persisted), so the
     * per-triple values of this process cannot be computed by a party that only controls the data, and a collision
     * cannot be prepared in advance. The digest is therefore suitable for what it is used for (deciding whether a
     * cached state was built from this content) and for nothing else: it is not a stable or public fingerprint, and
     * it does not resist a party that can read the salt (i.e. the memory of this process).
     *
     * One digester computes digests without allocating per triple: values are fed to the hash through one reused
     * buffer, as UTF-16 code units.
     */
    internal class GraphDigester(salt: ByteArray = randomSalt()) {
        private val salt: ByteArray = salt.copyOf()

        fun digest(triples: Iterable<RdfTriple>): GraphDigest {
            val sha = MessageDigest.getInstance("SHA-256")
            val sink = Sink(sha)
            val out = ByteArray(32)
            var s0 = 0L
            var s1 = 0L
            var s2 = 0L
            var s3 = 0L
            var count = 0L
            for (triple in triples) {
                sha.update(salt)
                sink.term(triple.subject)
                sink.term(triple.predicate)
                sink.term(triple.obj)
                sink.flush()
                sha.digest(out, 0, 32)
                // (s0..s3) += out, as big-endian 256-bit integers, modulo 2^256.
                val a3 = long(out, 24)
                val r3 = s3 + a3
                var carry = if (java.lang.Long.compareUnsigned(r3, a3) < 0) 1L else 0L
                val t2 = s2 + long(out, 16)
                val r2 = t2 + carry
                carry = if (java.lang.Long.compareUnsigned(t2, s2) < 0 || java.lang.Long.compareUnsigned(r2, t2) < 0) 1L else 0L
                val t1 = s1 + long(out, 8)
                val r1 = t1 + carry
                carry = if (java.lang.Long.compareUnsigned(t1, s1) < 0 || java.lang.Long.compareUnsigned(r1, t1) < 0) 1L else 0L
                s0 += long(out, 0) + carry
                s1 = r1
                s2 = r2
                s3 = r3
                count++
            }
            return GraphDigest(s0, s1, s2, s3, count)
        }

        private fun long(bytes: ByteArray, at: Int): Long {
            var value = 0L
            for (i in at until at + 8) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
            return value
        }

        /** Feeds the encoding of terms to [sha] through one reused buffer. */
        private class Sink(private val sha: MessageDigest) {
            private val buffer = ByteArray(1024)
            private var position = 0

            fun flush() {
                if (position > 0) {
                    sha.update(buffer, 0, position)
                    position = 0
                }
            }

            private fun byte(value: Int) {
                if (position == buffer.size) flush()
                buffer[position++] = value.toByte()
            }

            private fun field(value: String?) {
                if (value == null) {
                    byte(0)
                    return
                }
                byte(1)
                val length = value.length
                byte(length ushr 24)
                byte(length ushr 16)
                byte(length ushr 8)
                byte(length)
                for (i in 0 until length) {
                    val c = value[i].code
                    byte(c ushr 8)
                    byte(c)
                }
            }

            fun term(term: RdfTerm) {
                when (term) {
                    is Iri -> { byte('I'.code); field(term.value) }
                    is BlankNode -> { byte('B'.code); field(term.id) }
                    is LangString -> {
                        byte('L'.code); field(term.lexical); field(term.lang); field(term.direction?.toString())
                    }
                    is Literal -> { byte('T'.code); field(term.lexical); field(term.datatype.value) }
                    is TripleTerm -> {
                        byte('R'.code)
                        term(term.triple.subject)
                        term(term.triple.predicate)
                        term(term.triple.obj)
                    }
                    else -> { byte('?'.code); field(term.toString()) }
                }
            }
        }

        private companion object {
            private val random = SecureRandom()
            fun randomSalt(): ByteArray = ByteArray(32).also(random::nextBytes)
        }
    }
}
