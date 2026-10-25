package com.geoknoesis.kastor.rdf

// The graph interfaces of the term model: read-only graphs, the modification-stamp contract, mutable
// graphs and source tracking.

/**
 * Interface for RDF graphs - collections of RDF triples.
 * 
 * A graph is a set of RDF triples. This interface provides operations for
 * managing triples within a graph.
 */
interface RdfGraph {
    /** Match a pattern. Indexed providers override this to avoid scanning the graph. */
    fun find(subject: RdfResource? = null, predicate: Iri? = null, obj: RdfTerm? = null): List<RdfTriple> =
        getTriplesSequence().filter {
            (subject == null || it.subject == subject) && (predicate == null || it.predicate == predicate) &&
                (obj == null || it.obj == obj)
        }.toList()

    /**
     * Checks if a triple exists in the graph.
     * 
     * **Performance:** O(1) for most implementations (hash-based lookup).
     * 
     * @param triple The triple to check
     * @return true if the triple exists, false otherwise
     */
    fun hasTriple(triple: RdfTriple): Boolean
    
    /**
     * Gets all triples in the graph.
     * 
     * **Performance:** O(n) where n is the number of triples.
     * For large graphs, consider using [getTriplesSequence] for lazy evaluation
     * or SPARQL queries with filters instead.
     * 
     * @return List of all triples (defensive copy)
     */
    fun getTriples(): List<RdfTriple>
    
    /**
     * Gets all triples in the graph as a lazy sequence.
     * 
     * **Performance:** O(1) to create the sequence, O(n) to iterate.
     * This method provides lazy evaluation, avoiding intermediate list creation.
     * Use this for large graphs where you don't need all triples at once.
     * 
     * **Example:**
     * ```kotlin
     * // Process triples lazily without loading all into memory
     * graph.getTriplesSequence()
     *     .filter { it.predicate == FOAF.name }
     *     .take(100)
     *     .forEach { println(it) }
     * ```
     * 
     * @return Sequence of all triples (lazy evaluation)
     */
    fun getTriplesSequence(): Sequence<RdfTriple> = getTriples().asSequence()
    
    /**
     * Get the number of triples in this graph.
     */
    fun size(): Int
}

/**
 * A graph that exposes a cheap modification stamp, so callers that cache work derived from its content (for example
 * a SHACL validator's copy of the data) can detect changes without scanning the triples.
 *
 * ## The contract
 *
 * **The stamp identifies the content that the calling thread would read right now.** Whenever two reads of
 * [modificationStamp] of one graph (the same instance, or equal handles of the same graph of the same repository)
 * return the same value - on whichever threads, at whichever times - the content that each reading thread would have
 * read from the graph at the moment of its stamp read is the same. The converse does not hold: a different stamp
 * does not guarantee different content (a triple added and then removed). Only equality is meaningful.
 *
 * 1. **Committed stamp.** Outside write transactions a graph has one stamp, its *committed stamp*, and every thread
 *    reads it. A change of the content made outside a transaction replaces it by a value the graph never had.
 * 2. **A thread inside its own write transaction** ([RdfRepository.transaction]) reads *transaction-private* stamps
 *    for the graphs that transaction has changed: values the graph never had, that no other thread ever reads, and
 *    that each further write of the transaction to the graph replaces by a new one. For a graph its transaction has
 *    not changed, the thread reads the committed stamp.
 * 3. **Every other thread keeps reading the last committed stamp** until the commit completes: never the stamp of
 *    content it cannot read. (A thread whose read transaction pins a snapshot reads the stamp of that snapshot for
 *    as long as it reads that snapshot.)
 * 4. **Commit** replaces the committed stamp of every graph the transaction changed by a new value - one the graph
 *    never had, not one of the private stamps - no later than the moment the changed content becomes visible to
 *    other threads.
 * 5. **Rollback** leaves the committed stamps as they were before the transaction (the content is what it was), and
 *    the private stamps of the transaction are never returned again, to any thread. A provider that cannot tell that
 *    the content was restored may publish new committed stamps instead.
 * 6. **No reuse.** A value is never returned for two different contents of a graph: not across removal and
 *    re-creation of a named graph, and not after a rolled back transaction.
 * 7. **Reads do not block.** A stamp read does not wait for a writer or for the transaction of another thread: a
 *    cache keyed by the stamp is there to avoid reading the graph, and must be able to serve the committed content
 *    while a long transaction is open.
 * 8. **Failure.** A stamp read fails where a content read would fail for good (for example with
 *    [IllegalStateException] on a closed repository) - for every graph of the repository alike, default or named.
 *
 * A cache shared between threads and keyed by (graph handle, stamp) is therefore safe without reading the graph on a
 * hit: a thread never finds, under the stamp it reads, a state built from uncommitted content of another thread's
 * transaction (no dirty read), and a thread inside a transaction never finds, under the stamp it reads, a state
 * built before one of its own writes (no missed own write).
 *
 * ## Consumers: read the stamp before the content
 *
 * The stamp and the content are two separate reads, and outside a transaction the graph may change between them:
 *
 * ```kotlin
 * val stamp = graph.modificationStamp      // 1. stamp
 * if (stamp != cached.stamp) {
 *     cached = Cached(stamp, derive(graph)) // 2. content, stored under the stamp read before it
 * }
 * ```
 *
 * A change between the two reads then leaves newer content under an older stamp, which only costs a reload on the
 * next call (the older stamp is never returned again once the content has changed). In the opposite order (content,
 * then stamp) older content would be stored under the newer stamp and served as current until the next change: a
 * stale cache hit. To read both atomically, read them inside [RdfRepository.readTransaction] of the owning repository
 * where the provider holds a lock for it (the memory provider does).
 */
interface VersionedRdfGraph : RdfGraph {
    /**
     * The stamp of the content the calling thread would read from this graph right now (see [VersionedRdfGraph]):
     * the committed stamp, or inside the caller's own write transaction the private stamp of its latest write to
     * the graph. Read it **before** the content it is meant to describe. Only equality is meaningful to consumers.
     */
    val modificationStamp: Long
}

/**
 * Mutable RDF graph operations.
 * 
 * Provides both read and write operations for RDF graphs.
 */
/** Batch size of the default streaming `addTriples`/`removeTriples` overloads of [MutableRdfGraph]. */
internal const val STREAMING_WRITE_BATCH_SIZE: Int = 10_000

interface MutableRdfGraph : RdfGraph {
    /**
     * Adds a single triple to the graph.
     * 
     * **Performance:** O(1) for most implementations.
     * For adding multiple triples, prefer [addTriples] for better performance.
     * 
     * @param triple The triple to add
     */
    fun addTriple(triple: RdfTriple)

    /**
     * Adds multiple triples to the graph.
     * 
     * **Performance:** This method is optimized for batch operations.
     * For adding multiple triples, prefer this over multiple [addTriple] calls.
     * 
     * @param triples The triples to add
     */
    fun addTriples(triples: Collection<RdfTriple>)

    /**
     * Remove a triple from this graph.
     * @return true if the triple was removed, false if it wasn't present
     */
    fun removeTriple(triple: RdfTriple): Boolean

    /**
     * Remove multiple triples from this graph.
     * @return true if any triples were removed
     */
    fun removeTriples(triples: Collection<RdfTriple>): Boolean

    /**
     * Adds triples from an [Iterable] that need not be a [Collection].
     *
     * Collections are delegated to the `Collection` overload; other iterables are streamed like a
     * [Sequence]. Transactional providers override this to write in a single transaction.
     */
    fun addTriples(triples: Iterable<RdfTriple>) {
        if (triples is Collection<RdfTriple>) addTriples(triples) else addTriples(triples.asSequence())
    }

    /**
     * Adds triples from a [Sequence] while streaming: the sequence is consumed once and never fully
     * materialised. The default writes bounded batches through the `Collection` overload; transactional
     * providers (Jena, RDF4J) override it to stream into a single transaction.
     */
    fun addTriples(triples: Sequence<RdfTriple>) {
        triples.chunked(STREAMING_WRITE_BATCH_SIZE).forEach { batch -> addTriples(batch) }
    }

    /**
     * Removes triples from an [Iterable]; delegation as for the `Iterable` [addTriples] overload.
     * @return true if any triples were removed
     */
    fun removeTriples(triples: Iterable<RdfTriple>): Boolean =
        if (triples is Collection<RdfTriple>) removeTriples(triples) else removeTriples(triples.asSequence())

    /**
     * Removes triples from a [Sequence] while streaming (bounded batches by default).
     * @return true if any triples were removed
     */
    fun removeTriples(triples: Sequence<RdfTriple>): Boolean {
        var changed = false
        triples.chunked(STREAMING_WRITE_BATCH_SIZE).forEach { batch -> if (removeTriples(batch)) changed = true }
        return changed
    }

    /**
     * Clear all triples from this graph.
     * @return true if any triples were removed
     */
    fun clear(): Boolean
}

/**
 * Interface for graphs that know their source repository and graph name.
 * 
 * This follows the industry pattern of tracking graph provenance for optimization.
 * When graphs implement this interface, datasets can optimize query execution
 * by using FROM clauses instead of materializing unions.
 * 
 * **Implementation Note:**
 * Graph implementations (like SparqlGraph) should implement this interface
 * to enable dataset optimization.
 */
interface SourceTrackedGraph : RdfGraph {
    /**
     * The repository this graph comes from, if known.
     * 
     * @return The source repository, or null if not tracked
     */
    val sourceRepository: RdfRepository?
    
    /**
     * The name of this graph in the source repository, if known.
     * 
     * null means this is the default graph of the repository.
     * 
     * @return The graph name in the source repository, or null for default graph
     */
    val sourceGraphName: Iri?
}









