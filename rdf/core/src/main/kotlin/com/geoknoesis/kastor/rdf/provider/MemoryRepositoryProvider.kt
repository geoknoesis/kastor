package com.geoknoesis.kastor.rdf.provider

import com.geoknoesis.kastor.rdf.*

/**
 * Memory repository provider implementation.
 * Provides in-memory RDF repositories for testing and development.
 */
class MemoryRepositoryProvider : RdfApiProvider {
    
    override fun getType(): String = "memory"
    
    override val name: String = "Memory Repository"
    
    override val version: String = "1.0.0"
    
    override fun createRepository(config: RdfConfig): RdfRepository {
        return MemoryRepository(config)
    }
    
    override fun getCapabilities(): ProviderCapabilities {
        return ProviderCapabilities(
            supportsInference = false,
            supportsTransactions = false,
            supportsNamedGraphs = false,
            supportsUpdates = false,
            supportsRdfStar = true,
            maxMemoryUsage = Long.MAX_VALUE
        )
    }
    
    override fun getSupportedTypes(): List<String> = listOf("memory")
    
    override fun isSupported(type: String): Boolean = type == "memory"
}

/**
 * Simple in-memory RDF repository implementation.
 */
class MemoryRepository(private val config: RdfConfig) : RdfRepository {
    
    private val graphs = mutableMapOf<Iri, RdfGraph>()
    private var closed = false
    
    override val defaultGraph: RdfGraph by lazy { MemoryGraph() }
    
    override fun getGraph(name: Iri): RdfGraph {
        return graphs.getOrPut(name) { MemoryGraph() }
    }
    
    override fun hasGraph(name: Iri): Boolean = graphs.containsKey(name)
    
    override fun listGraphs(): List<Iri> = graphs.keys.toList()
    
    override fun createGraph(name: Iri): RdfGraph {
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
    
    override fun query(sparql: String): QueryResult {
        // Simple implementation - just return empty results for now
        return EmptyQueryResult()
    }
    
    override fun ask(sparql: String): Boolean = false
    
    override fun construct(sparql: String): List<RdfTriple> = emptyList()
    
    override fun describe(sparql: String): List<RdfTriple> = emptyList()
    
    override fun update(sparql: String) {
        // No-op for now
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
    
    override fun getStatistics(): RepositoryStatistics {
        val totalTriples = defaultGraph.size() + graphs.values.sumOf { it.size() }
        return RepositoryStatistics(
            tripleCount = totalTriples.toLong(),
            graphCount = graphs.size + 1, // +1 for default graph
            memoryUsage = estimateMemoryUsage(),
            diskUsage = 0,
            lastModified = System.currentTimeMillis()
        )
    }
    
    override fun getPerformanceMonitor(): PerformanceMonitor {
        return PerformanceMonitor(
            queryCount = 0,
            averageQueryTime = 0.0,
            totalQueryTime = 0,
            cacheHitRate = 1.0,
            memoryUsage = estimateMemoryUsage()
        )
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
            supportsNamedGraphs = false,
            supportsUpdates = false,
            supportsRdfStar = true,
            maxMemoryUsage = Long.MAX_VALUE
        )
    }
    
    private fun estimateMemoryUsage(): Long {
        // Rough estimate based on number of triples
        val totalTriples = defaultGraph.size() + graphs.values.sumOf { it.size() }
        return totalTriples * 100L // Assume ~100 bytes per triple
    }
}

/**
 * Simple in-memory graph implementation.
 */
class MemoryGraph : RdfGraph {
    
    private val triples = mutableSetOf<RdfTriple>()
    
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
