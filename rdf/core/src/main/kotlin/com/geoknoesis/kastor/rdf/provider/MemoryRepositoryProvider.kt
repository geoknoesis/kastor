package com.geoknoesis.kastor.rdf.provider

import com.geoknoesis.kastor.rdf.*
import java.lang.ref.Cleaner
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Memory repository provider implementation.
 *
 * Provides a minimal in-memory RDF repository intended for graph-only testing
 * and development. It does **not** support:
 * - SPARQL queries (`select`, `ask`, `construct`, `describe`, `update`)
 * - RDF parsing or serialization
 *
 * For full functionality (SPARQL, parsing, serialization), depend on
 * `:rdf:jena` or `:rdf:rdf4j` and use [Rdf.memory] / [Rdf.persistent], which
 * pick a real provider via `ServiceLoader`. This provider is registered
 * unconditionally so that `Rdf.repository { providerId = "memory" }` always
 * resolves, but it is never selected by the `Rdf.memory()` / `Rdf.persistent()`
 * factory methods.
 */
class MemoryRepositoryProvider : RdfProvider {
    
    override val id: String = "memory"
    
    override val name: String = "Memory Repository"
    
    override val version: String = "1.0.0"

    /** Graph-only (no parsing, serialization or SPARQL): try every other provider first. */
    override val priority: Int = -100
    
    override fun variants(): List<RdfVariant> {
        return listOf(RdfVariant("memory", "In-memory store"))
    }
    
    override fun createRepository(variantId: String, config: RdfConfig): RdfRepository {
        if (variantId != "memory") {
            throw IllegalArgumentException("Unsupported memory variant: $variantId")
        }
        return MemoryRepository(config)
    }
    
    override fun getCapabilities(variantId: String?): ProviderCapabilities {
        // Graph-only provider with serializable, snapshot-backed transactions.
        return ProviderCapabilities(
            rdfVersion = "1.1",
            supportsTripleTerms = false,
            supportsInference = false,
            supportsTransactions = true,
            supportsNamedGraphs = true,
            supportsUpdates = false,
            supportsRdfStar = true,
            maxMemoryUsage = Long.MAX_VALUE
        )
    }
}

/**
 * Simple in-memory RDF repository implementation.
 * 
 * **Resource Management:**
 * - Always use [use] or call [close] explicitly
 * - In development, finalizer warnings help detect leaks
 */
class MemoryRepository(private val config: RdfConfig) : RdfRepository {
    private val lock = Any()
    private val graphs = linkedMapOf<Iri, MemoryGraph>()
    @Volatile private var closed = false
    private val transactionMode = ThreadLocal<Boolean?>()
    private fun checkAccess(write: Boolean) {
        check(!closed) { "Repository is closed" }
        check(!write || transactionMode.get() != false) { "Cannot write inside a read transaction" }
    }
    private val default = MemoryGraph(emptyList(), lock, ::checkAccess)
    override val defaultGraph: RdfGraph get() = synchronized(lock) { checkAccess(false); default }
    override fun getGraph(name: Iri): RdfGraph = synchronized(lock) {
        checkAccess(false)
        graphs.getOrPut(name) { MemoryGraph(emptyList(), lock, ::checkAccess) }
    }
    override fun hasGraph(name: Iri): Boolean = synchronized(lock) { checkAccess(false); graphs[name]?.size()?.let { it > 0 } ?: false }
    override fun listGraphs(): List<Iri> = synchronized(lock) { checkAccess(false); graphs.filterValues { it.size() > 0 }.keys.toList() }
    override fun createGraph(name: Iri): RdfGraph = getGraph(name)
    override fun removeGraph(name: Iri): Boolean = synchronized(lock) {
        checkAccess(true)
        val removed = graphs.remove(name)
        val changed = removed?.clear() ?: false
        changed
    }
    override fun editDefaultGraph(): MutableRdfGraph = defaultGraph as MutableRdfGraph
    override fun editGraph(name: Iri): MutableRdfGraph = getGraph(name) as MutableRdfGraph
    override fun select(query: SparqlSelect): SparqlQueryResult = throw UnsupportedOperationException("Memory provider has no SPARQL engine")
    override fun ask(query: SparqlAsk): Boolean = throw UnsupportedOperationException("Memory provider has no SPARQL engine")
    override fun construct(query: SparqlConstruct): Sequence<RdfTriple> = throw UnsupportedOperationException("Memory provider has no SPARQL engine")
    override fun describe(query: SparqlDescribe): Sequence<RdfTriple> = throw UnsupportedOperationException("Memory provider has no SPARQL engine")
    override fun update(query: UpdateQuery): Unit = throw UnsupportedOperationException("Memory provider has no SPARQL engine")
    override fun transaction(operations: RdfRepository.() -> Unit): Unit = synchronized(lock) {
        checkAccess(true)
        if (transactionMode.get() != null) { operations(this); return@synchronized }
        val previousDefault = default.getTriples()
        val previous = graphs.mapValues { (_, graph) -> graph to graph.getTriples() }
        transactionMode.set(true)
        try { operations(this) }
        catch (e: Throwable) {
            default.clear(); default.addTriples(previousDefault)
            graphs.values.forEach { it.clear() }; graphs.clear()
            previous.forEach { (name, snapshot) -> snapshot.first.addTriples(snapshot.second); graphs[name] = snapshot.first }
            throw e
        } finally { transactionMode.remove() }
    }
    override fun readTransaction(operations: RdfRepository.() -> Unit): Unit = synchronized(lock) {
        checkAccess(false)
        if (transactionMode.get() != null) { operations(this); return@synchronized }
        transactionMode.set(false)
        try { operations(this) } finally { transactionMode.remove() }
    }
    override fun clear(): Boolean = synchronized(lock) {
        checkAccess(true)
        var changed = default.clear()
        graphs.values.forEach { if (it.clear()) changed = true }
        graphs.clear()
        changed
    }
    override fun isClosed(): Boolean = closed
    override fun close(): Unit = synchronized(lock) {
        if (!closed) {
            check(transactionMode.get() == null) { "Cannot close inside a transaction" }
            clear(); closed = true
        }
    }
    override fun getCapabilities(): ProviderCapabilities = MemoryRepositoryProvider().getCapabilities("memory")
}

/** Insertion ordered graph with subject/predicate/object indexes and snapshot reads. */
class MemoryGraph(
    initialTriples: Collection<RdfTriple> = emptyList(),
    private val lock: Any = Any(),
    private val access: (Boolean) -> Unit = {},
) : MutableRdfGraph {
    private val triples = linkedSetOf<RdfTriple>()
    private val subjects = mutableMapOf<RdfResource, MutableSet<RdfTriple>>()
    private val predicates = mutableMapOf<Iri, MutableSet<RdfTriple>>()
    private val objects = mutableMapOf<RdfTerm, MutableSet<RdfTriple>>()
    init { if (initialTriples.isNotEmpty()) addTriples(initialTriples) }
    override fun addTriple(triple: RdfTriple): Unit = synchronized(lock) {
        access(true)
        if (triples.add(triple)) {
            subjects.getOrPut(triple.subject) { linkedSetOf() }.add(triple)
            predicates.getOrPut(triple.predicate) { linkedSetOf() }.add(triple)
            objects.getOrPut(triple.obj) { linkedSetOf() }.add(triple)
        }
    }
    override fun addTriples(triples: Collection<RdfTriple>): Unit = synchronized(lock) { access(true); triples.forEach(::addTriple) }
    override fun removeTriple(triple: RdfTriple): Boolean = synchronized(lock) {
        access(true)
        if (!triples.remove(triple)) return@synchronized false
        fun <K> remove(index: MutableMap<K, MutableSet<RdfTriple>>, key: K) {
            index[key]?.let { it.remove(triple); if (it.isEmpty()) index.remove(key) }
        }
        remove(subjects, triple.subject); remove(predicates, triple.predicate); remove(objects, triple.obj)
        true
    }
    override fun removeTriples(triples: Collection<RdfTriple>): Boolean = synchronized(lock) {
        access(true)
        var changed = false
        triples.forEach { if (removeTriple(it)) changed = true }
        changed
    }
    override fun hasTriple(triple: RdfTriple): Boolean = synchronized(lock) { access(false); triple in triples }
    override fun getTriples(): List<RdfTriple> = synchronized(lock) { access(false); triples.toList() }
    override fun getTriplesSequence(): Sequence<RdfTriple> = getTriples().asSequence()
    override fun find(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): List<RdfTriple> = synchronized(lock) {
        access(false)
        val candidates = listOfNotNull(subject?.let { subjects[it].orEmpty() }, predicate?.let { predicates[it].orEmpty() },
            obj?.let { objects[it].orEmpty() }).minByOrNull { it.size } ?: triples
        candidates.filter { (subject == null || it.subject == subject) && (predicate == null || it.predicate == predicate) && (obj == null || it.obj == obj) }
    }
    override fun size(): Int = synchronized(lock) { access(false); triples.size }
    override fun clear(): Boolean = synchronized(lock) {
        access(true)
        val changed = triples.isNotEmpty()
        triples.clear(); subjects.clear(); predicates.clear(); objects.clear()
        changed
    }
}

/**
 * Simple empty SPARQL query result implementation.
 * Implemented as a singleton object for efficiency.
 */
object EmptySparqlQueryResult : SparqlQueryResult {
    
    override fun iterator(): Iterator<BindingSet> = emptyList<BindingSet>().iterator()
    
    override fun count(): Int = 0
    
    override fun first(): BindingSet? = null
    
    override fun toList(): List<BindingSet> = emptyList()
    
    override fun asSequence(): Sequence<BindingSet> = emptySequence()
}









