package com.geoknoesis.kastor.rdf.provider

import com.geoknoesis.kastor.rdf.*
import java.lang.ref.Cleaner

/**
 * Memory repository provider implementation.
 * Provides in-memory RDF repositories for testing and development.
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
        return ProviderCapabilities(
            supportsInference = false,
            supportsTransactions = false,
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
    
    private val graphs = mutableMapOf<Iri, MutableRdfGraph>()
    @Volatile
    private var closed = false
    private val leakState = LeakState()
    private val cleanable = cleaner.register(this, leakState)
    
    override val defaultGraph: RdfGraph by lazy { MemoryGraph() }
    
    override fun getGraph(name: Iri): RdfGraph {
        return graphs.getOrPut(name) { MemoryGraph() }
    }
    
    override fun hasGraph(name: Iri): Boolean = graphs.containsKey(name)
    
    override fun listGraphs(): List<Iri> = graphs.keys.toList()
    
    override val namedGraphs: Map<Iri, RdfGraph>
        get() {
            return graphs.toMap()
        }
    
    override fun createGraph(name: Iri): RdfGraph {
        if (graphs.containsKey(name)) {
            throw IllegalArgumentException("Graph $name already exists")
        }
        val graph = MemoryGraph()
        graphs[name] = graph
        return graph
    }
    
    override fun removeGraph(name: Iri): Boolean {
        val removed = graphs.remove(name) != null
        return removed
    }

    override fun editDefaultGraph(): GraphEditor {
        return defaultGraph as MutableRdfGraph
    }

    override fun editGraph(name: Iri): GraphEditor {
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
            supportsInference = false,
            supportsTransactions = false,
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
    
    private val triples = mutableSetOf<RdfTriple>().apply { addAll(initialTriples) }
    
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
        return this.triples.removeAll(triples)
    }
    
    override fun hasTriple(triple: RdfTriple): Boolean {
        return triples.contains(triple)
    }
    
    override fun getTriples(): List<RdfTriple> {
        return triples.toList()
    }
    
    override fun getTriplesSequence(): Sequence<RdfTriple> {
        return triples.asSequence()
    }
    
    override fun size(): Int {
        return triples.size
    }
    
    override fun clear(): Boolean {
        val hadTriples = triples.isNotEmpty()
        triples.clear()
        return hadTriples
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









