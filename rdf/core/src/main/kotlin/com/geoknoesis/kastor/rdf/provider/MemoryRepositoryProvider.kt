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
        // The memory provider's `transaction { ... }` succeeds, but it offers no
        // isolation or rollback. We advertise it as a no-op transaction so callers
        // who write `repo.transaction { ... }` for symmetry with real providers
        // do not break, while making it clear the provider does not implement
        // ACID semantics.
        //
        // The provider does not parse or serialise RDF, but it does store
        // [TripleTerm] objects faithfully via [MemoryGraph], so we advertise
        // RDF 1.1 conformance with no triple-term capability declared at the
        // provider level.
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
    
    private val graphs = ConcurrentHashMap<Iri, MutableRdfGraph>()
    @Volatile
    private var closed = false
    private val leakState = LeakState()
    private val cleanable = cleaner.register(this, leakState)
    
    override val defaultGraph: RdfGraph by lazy { MemoryGraph() }
    
    override fun getGraph(name: Iri): RdfGraph {
        // computeIfAbsent is atomic on ConcurrentHashMap, unlike Kotlin's getOrPut.
        return graphs.computeIfAbsent(name) { MemoryGraph() }
    }

    override fun hasGraph(name: Iri): Boolean = graphs.containsKey(name)

    override fun listGraphs(): List<Iri> = graphs.keys.toList()

    override val namedGraphs: Map<Iri, RdfGraph>
        get() {
            return graphs.toMap()
        }

    override fun createGraph(name: Iri): RdfGraph {
        val graph = MemoryGraph()
        // Atomic check-and-insert avoids a TOCTOU race between containsKey and put.
        if (graphs.putIfAbsent(name, graph) != null) {
            throw IllegalArgumentException("Graph $name already exists")
        }
        return graph
    }
    
    override fun removeGraph(name: Iri): Boolean {
        val removed = graphs.remove(name) != null
        return removed
    }

    override fun editDefaultGraph(): MutableRdfGraph {
        return defaultGraph as MutableRdfGraph
    }

    override fun editGraph(name: Iri): MutableRdfGraph {
        return getGraph(name) as MutableRdfGraph
    }
    
    override fun select(query: SparqlSelect): SparqlQueryResult {
        throw UnsupportedOperationException("Memory repository does not support SPARQL queries.")
    }
    
    override fun ask(query: SparqlAsk): Boolean {
        throw UnsupportedOperationException("Memory repository does not support SPARQL ASK.")
    }
    
    override fun construct(query: SparqlConstruct): Sequence<RdfTriple> {
        throw UnsupportedOperationException("Memory repository does not support SPARQL CONSTRUCT.")
    }
    
    override fun describe(query: SparqlDescribe): Sequence<RdfTriple> {
        throw UnsupportedOperationException("Memory repository does not support SPARQL DESCRIBE.")
    }
    
    override fun update(query: UpdateQuery) {
        throw UnsupportedOperationException("Memory repository does not support SPARQL UPDATE.")
    }
    
    override fun transaction(operations: RdfRepository.() -> Unit) {
        // Simple implementation - just execute operations directly
        operations.invoke(this)
    }
    
    override fun readTransaction(operations: RdfRepository.() -> Unit) {
        // Simple implementation - just execute operations directly
        operations.invoke(this)
    }
    
    override fun clear(): Boolean {
        val hadDefault = editDefaultGraph().clear()
        val hadNamed = graphs.isNotEmpty()
        graphs.clear()
        return hadDefault || hadNamed
    }
    
    override fun isClosed(): Boolean = closed
    
    override fun close() {
        closed = true
        leakState.closed = true
        cleanable.clean()
        graphs.clear()
        editDefaultGraph().clear()
    }
    
    private class LeakState : Runnable {
        @Volatile
        var closed: Boolean = false

        override fun run() {
            if (!closed) {
                System.err.println(
                    "WARNING: MemoryRepository was not closed properly! " +
                        "Always use 'use' block or call 'close()' explicitly. " +
                        "This is a resource leak."
                )
            }
        }
    }

    private companion object {
        val cleaner: Cleaner = Cleaner.create()
    }
    
    override fun getCapabilities(): ProviderCapabilities {
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
 * Simple in-memory graph implementation.
 */
class MemoryGraph(initialTriples: Collection<RdfTriple> = emptyList()) : MutableRdfGraph {

    // Insertion-ordered set guarded for concurrent access. Single-element
    // operations are synchronized internally by the wrapper; compound reads
    // (iteration) must hold the set's monitor, which we do via `synchronized`.
    private val triples: MutableSet<RdfTriple> =
        Collections.synchronizedSet(LinkedHashSet<RdfTriple>(initialTriples))

    override fun addTriple(triple: RdfTriple) {
        triples.add(triple)
    }

    override fun addTriples(triples: Collection<RdfTriple>) {
        this.triples.addAll(triples)
    }

    override fun removeTriple(triple: RdfTriple): Boolean {
        return triples.remove(triple)
    }

    override fun removeTriples(triples: Collection<RdfTriple>): Boolean {
        return this.triples.removeAll(triples.toSet())
    }

    override fun hasTriple(triple: RdfTriple): Boolean {
        return triples.contains(triple)
    }

    override fun getTriples(): List<RdfTriple> {
        // Snapshot under the monitor to avoid ConcurrentModificationException.
        return synchronized(triples) { triples.toList() }
    }

    override fun getTriplesSequence(): Sequence<RdfTriple> {
        // Return a sequence over a stable snapshot, not the live set.
        return synchronized(triples) { triples.toList() }.asSequence()
    }

    override fun size(): Int {
        return triples.size
    }

    override fun clear(): Boolean {
        return synchronized(triples) {
            val hadTriples = triples.isNotEmpty()
            triples.clear()
            hadTriples
        }
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









