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
import java.lang.ref.ReferenceQueue
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded cache of a state derived from the content of data graphs (for example a SHACL engine's own copy of the
 * data), shared by the validation adapters. The state of a graph is rebuilt only when the graph is new to the cache
 * or its content changed.
 *
 * ## Which cached state belongs to a graph
 * - **Graphs with a modification stamp** ([VersionedRdfGraph], e.g. `MemoryGraph` and the named graphs of the memory
 *   repository) are found by their handle and are current when the stamp is the one the state was built for: O(1),
 *   the triples are not read. The handle is compared by identity, or with `equals` when its class defines handle
 *   equality (it overrides `equals` and `hashCode` and is neither a data class nor a collection), so a handle that is
 *   obtained anew for every call (`repository.getGraph(name)`) finds the state built for an equal handle. Equal
 *   handles must denote the same live graph and share its stamp.
 * - **Other graphs** are identified by their content: one pass over the triples computes an order-independent
 *   SHA-256-based digest, and the state built for that content is reused whichever handle it was built for (so new
 *   handles of an unchanged RDF4J or Jena repository graph hit the cache). When the content is not cached, the state
 *   last used by the same handle is rebuilt in place.
 *
 * A state is never used for content it was not built from: the stamp (or the triples the digest is computed from) is
 * read before the snapshot the state is built from, so a concurrent change makes the next call rebuild.
 *
 * ## Locking
 * The data graph is read (stamp, triples) only while this cache holds **no** lock, so [use] may be called while the
 * caller holds the graph's repository lock (inside `repository.transaction { }`) without risk of a lock-order
 * deadlock. Callers of the same graph are serialized on its entry while its state is (re)built - and for the whole
 * [use] block when the cache is [exclusive]; callers of different graphs never wait for each other.
 *
 * ## Bound
 * At most [maxEntries] states are kept. When a new graph arrives and the cache is full, the least recently used entry
 * that no caller is using is released (entries whose handle was garbage collected first). When every entry is in use
 * the new graph gets a private temporary state that is released when its [use] block ends, instead of waiting for (or
 * taking away) an entry that is in use. Entries of stamped graphs whose handle was garbage collected can never be
 * hit again and are released on the next call of [use] (or [size]).
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
 * @param owner name used in the "has been closed" error message.
 */
class GraphStateCache<S : Any>(
    val maxEntries: Int,
    private val exclusive: Boolean,
    private val load: (triples: List<RdfTriple>, previous: S?) -> S,
    private val release: (S) -> Unit,
    private val owner: String = "GraphStateCache",
) : AutoCloseable {

    init {
        require(maxEntries >= 1) { "maxEntries must be at least 1, got $maxEntries" }
    }

    /** Guards [entries], [closed], [useCounter] and the `users`/`lastUsed`/`retired`/`handle` fields of entries. */
    private val entriesLock = Any()
    private val entries = ArrayList<Entry<S>>()
    private val deadHandles = ReferenceQueue<RdfGraph>()
    private var closed = false
    private var useCounter = 0L

    private val loads = AtomicInteger()
    private val digests = AtomicInteger()
    private val temporaries = AtomicInteger()

    /** Number of states built so far (including rebuilds). */
    val loadCount: Int get() = loads.get()

    /** Number of content digests computed so far (one per [use] of a graph without a modification stamp). */
    val digestCount: Int get() = digests.get()

    /** Number of [use] calls served by a private temporary state because every cached entry was in use. */
    val temporaryCount: Int get() = temporaries.get()

    /** Number of cached states. */
    val size: Int
        get() {
            val dead = synchronized(entriesLock) { expungeDead() }
            releaseAll(dead, rethrow = false)
            return synchronized(entriesLock) { entries.size }
        }

    /** What a state was built from: the modification stamp of a stamped graph, else the content digest. */
    private class Loaded<S>(val state: S, val stamp: Long?, val digest: GraphDigest?) {
        fun isFor(stamp: Long?, digest: GraphDigest?): Boolean =
            if (stamp != null) this.stamp == stamp else this.digest != null && this.digest == digest
    }

    private class Entry<S>(val stamped: Boolean, val temporary: Boolean) {
        // Guarded by the cache's entriesLock.
        var handle: Reference<RdfGraph>? = null
        var lastUsed = 0L
        var users = 0
        var retired = false

        /** Written under this entry's monitor; read without it for the content lookup and the staleness peek. */
        @Volatile var loaded: Loaded<S>? = null
    }

    private interface EntryRef<S> { val entry: Entry<S> }
    private class WeakHandle<S>(graph: RdfGraph, queue: ReferenceQueue<RdfGraph>, override val entry: Entry<S>) :
        WeakReference<RdfGraph>(graph, queue), EntryRef<S>
    private class SoftHandle<S>(graph: RdfGraph, queue: ReferenceQueue<RdfGraph>, override val entry: Entry<S>) :
        SoftReference<RdfGraph>(graph, queue), EntryRef<S>

    /**
     * Runs [block] with the state of [data], building it first when [data] is new or changed. The state must not be
     * used after [block] returns.
     *
     * @throws IllegalStateException when the cache is closed.
     */
    fun <R> use(data: RdfGraph, block: (S) -> R): R {
        // 1. Read the graph while holding no lock of this cache.
        val stamp = (data as? VersionedRdfGraph)?.modificationStamp
        var triples: List<RdfTriple>? = null
        var digest: GraphDigest? = null
        if (stamp == null) {
            triples = data.getTriples()
            digest = GraphDigest.of(triples)
            digests.incrementAndGet()
        }
        // 2. Pin the entry: an entry in use is never released or handed to another graph.
        val entry = pin(data, stamp, digest)
        try {
            while (true) {
                // 3. A stale entry needs a snapshot, which is also taken without holding a lock.
                if (triples == null && entry.loaded?.isFor(stamp, digest) != true) triples = data.getTriples()
                if (exclusive) {
                    synchronized(entry) {
                        val state = ensure(entry, stamp, digest, triples)
                        if (state != null) return block(state)
                    }
                } else {
                    val state = synchronized(entry) { ensure(entry, stamp, digest, triples) }
                    if (state != null) return block(state)
                }
                // Another caller rebuilt the entry for a different stamp since the peek: take a snapshot and retry.
            }
        } finally {
            unpin(entry)
        }
    }

    /** The current state of [entry], (re)built from [triples] when stale; null when stale and there is no snapshot. */
    private fun ensure(entry: Entry<S>, stamp: Long?, digest: GraphDigest?, triples: List<RdfTriple>?): S? {
        val current = entry.loaded
        if (current != null && current.isFor(stamp, digest)) return current.state
        if (triples == null) return null
        val previous = if (exclusive) current?.state else null
        val state = load(triples, previous)
        entry.loaded = Loaded(state, stamp, digest)
        loads.incrementAndGet()
        if (current != null && current.state !== state) releaseQuietly(current.state)
        return state
    }

    private fun pin(data: RdfGraph, stamp: Long?, digest: GraphDigest?): Entry<S> {
        val evicted = ArrayList<Entry<S>>()
        val byHandle = HandleEquality.of(data.javaClass)
        val entry = synchronized(entriesLock) {
            check(!closed) { "$owner has been closed" }
            evicted += expungeDead()
            val use = ++useCounter
            val found =
                // Content first: a state built for the same content is current whichever handle it was built for.
                (if (digest != null) entries.firstOrNull { !it.stamped && it.loaded?.digest == digest } else null)
                    ?: entries.firstOrNull { e ->
                        if (e.stamped != (stamp != null)) return@firstOrNull false
                        val handle = e.handle?.get() ?: return@firstOrNull false
                        handle === data || (byHandle && handle.javaClass === data.javaClass && handle == data)
                    }
            val pinned = found ?: run {
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
                if (entries.size >= maxEntries) {
                    // Every entry is in use: validate in a private store rather than wait for one of them.
                    temporaries.incrementAndGet()
                    Entry<S>(stamped = stamp != null, temporary = true)
                } else {
                    Entry<S>(stamped = stamp != null, temporary = false).also { entries += it }
                }
            }
            // Remember the newest handle: an equal one (or, for content hits, one that replaces a collected handle).
            val currentHandle = pinned.handle?.get()
            if (!pinned.temporary && currentHandle !== data && (currentHandle == null || found == null || stamp != null)) {
                pinned.handle = if (byHandle) SoftHandle(data, deadHandles, pinned) else WeakHandle(data, deadHandles, pinned)
            }
            pinned.lastUsed = use
            pinned.users++
            pinned
        }
        // Outside the lock; nobody uses an evicted entry, so this never waits for a validation.
        releaseAll(evicted, rethrow = false)
        return entry
    }

    private fun unpin(entry: Entry<S>) {
        val release = synchronized(entriesLock) {
            entry.users--
            entry.users == 0 && (entry.retired || entry.temporary)
        }
        if (release) releaseAll(listOf(entry), rethrow = false)
    }

    /**
     * Removes the entries of stamped graphs whose handle was garbage collected (they can never be hit again) and
     * returns those that can be released now; entries in use are released by their last user. Entries identified by
     * content stay: another handle with the same content still hits them. Holds [entriesLock].
     */
    private fun expungeDead(): List<Entry<S>> {
        var result: ArrayList<Entry<S>>? = null
        while (true) {
            val reference = deadHandles.poll() ?: break
            @Suppress("UNCHECKED_CAST")
            val entry = (reference as EntryRef<S>).entry
            // The entry may have been given a newer handle since.
            if (entry.handle !== reference || !entry.stamped || entry.retired) continue
            entry.retired = true
            if (entries.remove(entry) && entry.users == 0) {
                (result ?: ArrayList<Entry<S>>().also { result = it }) += entry
            }
        }
        return result ?: emptyList()
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
     * with the others suppressed. Further [use] calls throw [IllegalStateException].
     */
    override fun close() {
        val idle = synchronized(entriesLock) {
            closed = true
            val all = entries.toList()
            entries.clear()
            all.forEach { it.retired = true }
            all.filter { it.users == 0 }
        }
        releaseAll(idle, rethrow = true)
    }

    /** Whether a graph class identifies the graph by `equals` (handle equality) rather than by instance. */
    private object HandleEquality : ClassValue<Boolean>() {
        fun of(type: Class<*>): Boolean = get(type)

        override fun computeValue(type: Class<*>): Boolean = try {
            type.getMethod("equals", Any::class.java).declaringClass != Any::class.java &&
                type.getMethod("hashCode").declaringClass != Any::class.java &&
                // Content equality (data classes, collections) is O(size) and does not identify a live graph.
                !Collection::class.java.isAssignableFrom(type) && !Map::class.java.isAssignableFrom(type) &&
                type.methods.none { it.name == "component1" && it.parameterCount == 0 }
        } catch (e: ReflectiveOperationException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Content digest of a graph: the sum modulo 2^256 of the SHA-256 digests of an unambiguous encoding of each
     * triple (term kind tags, length-prefixed UTF-8 values), plus the triple count. Order independent and collision
     * resistant: unlike a sum of `hashCode()`s, distinct contents (e.g. the literals `"Aa"` and `"BB"`, which share
     * a String hash code) do not produce the same digest in practice.
     */
    internal data class GraphDigest(val words: List<Long>, val count: Long) {
        companion object {
            fun of(triples: Iterable<RdfTriple>): GraphDigest {
                val sha = MessageDigest.getInstance("SHA-256")
                val sum = LongArray(4)
                var count = 0L
                for (triple in triples) {
                    encodeTerm(sha, triple.subject)
                    encodeTerm(sha, triple.predicate)
                    encodeTerm(sha, triple.obj)
                    addModulo(sum, sha.digest())
                    count++
                }
                return GraphDigest(sum.toList(), count)
            }

            private fun encodeTerm(sha: MessageDigest, term: RdfTerm) {
                fun field(value: String?) {
                    if (value == null) {
                        sha.update(0)
                        return
                    }
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    sha.update(1)
                    sha.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                    sha.update(bytes)
                }
                when (term) {
                    is Iri -> { sha.update('I'.code.toByte()); field(term.value) }
                    is BlankNode -> { sha.update('B'.code.toByte()); field(term.id) }
                    is LangString -> {
                        sha.update('L'.code.toByte()); field(term.lexical); field(term.lang); field(term.direction?.toString())
                    }
                    is Literal -> { sha.update('T'.code.toByte()); field(term.lexical); field(term.datatype.value) }
                    is TripleTerm -> {
                        sha.update('R'.code.toByte())
                        encodeTerm(sha, term.triple.subject)
                        encodeTerm(sha, term.triple.predicate)
                        encodeTerm(sha, term.triple.obj)
                    }
                    else -> { sha.update('?'.code.toByte()); field(term.toString()) }
                }
            }

            /** [sum] += [digest] (32 bytes, big-endian) modulo 2^256; [sum] holds four big-endian 64-bit words. */
            private fun addModulo(sum: LongArray, digest: ByteArray) {
                val words = ByteBuffer.wrap(digest)
                val add = LongArray(4) { words.long }
                var carry = 0L
                for (i in 3 downTo 0) {
                    val a = sum[i]
                    val s = a + add[i] + carry
                    carry = if (java.lang.Long.compareUnsigned(s, a) < 0 || (carry == 1L && s == a)) 1L else 0L
                    sum[i] = s
                }
            }
        }
    }
}
