package com.geoknoesis.kastor.rdf.provider

import com.geoknoesis.kastor.rdf.*

/**
 * Memory repository provider implementation.
 * Provides in-memory RDF repositories for testing and development.
 */
class MemoryRepositoryProvider : RdfApiProvider {
    
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
 */
class MemoryRepository(private val config: RdfConfig) : RdfRepository {
    
    private val graphs = mutableMapOf<Iri, MutableRdfGraph>()
    private var closed = false
    
    override val defaultGraph: MutableRdfGraph by lazy { MemoryGraph() }
    
    override fun getGraph(name: Iri): MutableRdfGraph {
        return graphs.getOrPut(name) { MemoryGraph() }
    }
    
    override fun hasGraph(name: Iri): Boolean = graphs.containsKey(name)
    
    override fun listGraphs(): List<Iri> = graphs.keys.toList()
    
    override fun createGraph(name: Iri): MutableRdfGraph {
        if (graphs.containsKey(name)) {
            throw IllegalArgumentException("Graph $name already exists")
        }
        val graph = MemoryGraph()
        graphs[name] = graph
        return graph
    }
    
    override fun removeGraph(name: Iri): Boolean {
        return graphs.remove(name) != null
    }
    
    override fun select(query: SparqlSelect): QueryResult {
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
        operations()
    }
    
    override fun readTransaction(operations: RdfRepository.() -> Unit) {
        // Simple implementation - just execute operations directly
        operations()
    }
    
    override fun clear(): Boolean {
        defaultGraph.clear()
        graphs.clear()
        return true
    }
    
    override fun isClosed(): Boolean = closed
    
    override fun close() {
        closed = true
        graphs.clear()
        defaultGraph.clear()
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
 * Simple empty query result implementation.
 */
class EmptyQueryResult : QueryResult {
    
    override fun iterator(): Iterator<BindingSet> = emptyList<BindingSet>().iterator()
    
    override fun count(): Int = 0
    
    override fun first(): BindingSet? = null
    
    override fun toList(): List<BindingSet> = emptyList()
    
    override fun asSequence(): Sequence<BindingSet> = emptySequence()
}









