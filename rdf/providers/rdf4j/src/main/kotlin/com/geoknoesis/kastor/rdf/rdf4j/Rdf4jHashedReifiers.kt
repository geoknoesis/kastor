package com.geoknoesis.kastor.rdf.rdf4j

import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.Triple
import org.eclipse.rdf4j.model.Value
import java.lang.ref.SoftReference
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-repository index of the quoted triples that have a **hashed** reifier id (see [Rdf4jTerms.reifierFor]): the id
 * of such a reifier does not carry its triple, so resolving it needs to know the triple. There is no process-wide
 * state: every [Rdf4jRepository] has its own index, and an operation keeps the triples it resolved for as long as it
 * runs (see [Rdf4jRepository.resolveHashedReifiers]).
 *
 * **Content.** An entry maps a hashed id to its triple. The mapping itself never becomes wrong (the id is the hash of
 * the triple), so an entry may outlive the statements the triple occurred in. Entries come from
 * - writes through the repository (graph API, dataset loads): [written];
 * - reads that produce a hashed reifier, and `_:r rdf:reifies <<( t )>>` patterns, which name the triple of `_:r`
 *   themselves: [read];
 * - a scan of the store: [rebuild].
 *
 * **Covering the store.** The index *covers* the store when every oversized triple value in it has an entry; an id
 * without an entry is then known not to be a reifier without looking at the store. A repository created by a factory
 * method starts covered ([trust]: the store is empty) and stays so, because every write through the graph API
 * registers the oversized triples it writes. A SPARQL `UPDATE` that may write triple values ends that
 * ([invalidate]); the next lookup of an id that is not in the index scans the store once ([rebuild]), after which the
 * index covers the store again. So a negative answer costs nothing until the next such update. A repository wrapping
 * an externally created store, which other code may change, is never covered, except for the rest of a transaction
 * that scanned it (as the batch operations of the graph API do).
 *
 * **Memory.** Triples are softly referenced, so the index never keeps a large triple alive that the store no longer
 * holds; a scan stores the value objects of the store itself, which stay reachable while they are stored. A triple
 * written by a transaction is kept strongly until that transaction ends, so that a scan by another thread (which
 * cannot see it yet) does not drop it. An entry whose triple was reclaimed is looked up again in the store when it is
 * asked for ([lookup] reports it). Reclaimed entries are removed by scans, and whenever the index doubles in size.
 */
internal class HashedReifierIndex {
    /** Hashed reifier id to its quoted triple; guarded by itself. */
    private val entries = HashMap<String, SoftReference<Triple>>()
    private var purgeAt = MIN_PURGE_SIZE

    /** Changes whenever the index may have stopped covering the store. */
    private val generation = AtomicLong()

    /** The [generation] the index is known to cover the store for. */
    @Volatile private var coveredAt = NEVER

    /** The same, for the rest of the current thread's transaction only (stores other code may change). */
    private val coveredInTransaction = ThreadLocal<Long?>()

    /** Changes with every [written] triple and when the transaction that wrote it ends. */
    private val registrations = AtomicLong()

    /** The triples the current thread's transaction wrote, kept reachable until it ends. */
    private val pinned = ThreadLocal<ArrayList<Triple>?>()

    /** Declares that the store holds no oversized triple value the index does not know (an empty store). */
    fun trust() {
        coveredAt = generation.get()
    }

    /** The store may now hold triple values the index does not know. */
    fun invalidate() {
        generation.incrementAndGet()
    }

    /** Registers the oversized triples of a value the current thread's transaction writes. */
    fun written(value: Value) {
        if (value !is Triple) return
        Rdf4jTerms.forEachHashedReifier(value) { id, triple ->
            (pinned.get() ?: ArrayList<Triple>().also(pinned::set)).add(triple)
            registrations.incrementAndGet()
            put(id, triple, replace = true)
        }
    }

    /** Registers a triple with a hashed reifier that a read (or a pattern) came across. */
    fun read(id: String, triple: Triple) = put(id, triple, replace = false)

    private fun put(id: String, triple: Triple, replace: Boolean) = synchronized(entries) {
        if (replace || entries[id]?.get() == null) entries[id] = SoftReference(triple)
        if (entries.size >= purgeAt) {
            // Reclaimed entries may stand for triples that are still stored: forget that the index covers the store.
            entries.values.removeIf { it.get() == null }
            generation.incrementAndGet()
            purgeAt = maxOf(MIN_PURGE_SIZE, entries.size * 2)
        }
    }

    /** Ends the current thread's transaction: after its commit or rollback. */
    fun transactionEnded() {
        if (pinned.get() != null) {
            // Counted before the triples are released: a scan that overlaps this moment keeps reclaimed entries.
            registrations.incrementAndGet()
            pinned.remove()
        }
        coveredInTransaction.remove()
    }

    /**
     * Adds the triples of the [ids] the index knows to [out]. Returns the ids that have an entry whose triple was
     * reclaimed: they may or may not be in the store.
     */
    fun lookup(ids: Set<String>, out: MutableMap<String, Triple>): Set<String> = synchronized(entries) {
        var reclaimed: MutableSet<String>? = null
        for (id in ids) {
            val reference = entries[id] ?: continue
            val triple = reference.get()
            if (triple != null) out[id] = triple else (reclaimed ?: HashSet<String>().also { reclaimed = it }).add(id)
        }
        reclaimed ?: emptySet()
    }

    /** True when an id without an entry is known not to stand for a triple of the store. */
    fun covers(inTransaction: Boolean): Boolean {
        val current = generation.get()
        return coveredAt == current || (inTransaction && coveredInTransaction.get() == current)
    }

    /**
     * Indexes the store from one scan: [scan] feeds every statement that holds a triple value. Returns the oversized
     * triples found, by hashed id. Triple values the index already holds (the same objects) are not hashed again.
     *
     * @param controlled true when only the owning repository changes the store: the index then covers it afterwards.
     */
    fun rebuild(inTransaction: Boolean, controlled: Boolean, scan: ((Statement) -> Unit) -> Unit): Map<String, Triple> {
        val current = generation.get()
        val before = registrations.get()
        val known = IdentityHashMap<Triple, String>()
        synchronized(entries) { entries.forEach { (id, reference) -> reference.get()?.let { known[it] = id } } }
        val found = HashMap<String, Triple>()
        val alreadyKnown: (Triple) -> Boolean = { triple -> known[triple]?.also { found[it] = triple } != null }
        scan { statement ->
            Rdf4jTerms.forEachHashedReifier(statement.subject, alreadyKnown) { id, triple -> found[id] = triple }
            Rdf4jTerms.forEachHashedReifier(statement.`object`, alreadyKnown) { id, triple -> found[id] = triple }
        }
        synchronized(entries) {
            found.forEach { (id, triple) -> entries[id] = SoftReference(triple) }
            // A reclaimed entry the scan did not find is not in the store - unless a transaction that wrote it ended
            // while the scan ran (its triples are kept reachable until then, and its end is counted).
            if (registrations.get() == before) entries.values.removeIf { it.get() == null }
        }
        // A later invalidation changes the generation, which makes this claim void.
        if (controlled) coveredAt = current else if (inTransaction) coveredInTransaction.set(current)
        return found
    }

    /** Forgets every entry, as a new process would have for a store it did not write (for tests). */
    fun forget() {
        synchronized(entries) { entries.clear() }
        generation.incrementAndGet()
        coveredInTransaction.remove()
    }

    private companion object {
        const val NEVER = -1L
        const val MIN_PURGE_SIZE = 1024
    }
}
