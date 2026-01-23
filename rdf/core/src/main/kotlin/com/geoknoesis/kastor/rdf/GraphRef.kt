package com.geoknoesis.kastor.rdf

import java.io.Closeable

/**
 * Reference to an RDF graph with source tracking metadata.
 * 
 * This class implements [RdfGraph] by delegation, making it transparently
 * usable as a graph while also carrying metadata for optimization.
 * 
 * **Design Pattern:**
 * Follows the delegation pattern used by RDF4J's `DelegatingRepositoryConnection`
 * and similar wrapper classes in industry-standard libraries.
 * 
 * **Example:**
 * ```kotlin
 * val repo = Rdf.memory()
 * val graph = repo.getGraph(iri("http://example.org/graph"))
 * 
 * // Create a reference with source tracking
 * val graphRef = GraphRef(graph, repo, iri("http://example.org/graph"))
 * 
 * // Use it as a normal graph
 * val triples = graphRef.getTriples()  // Delegates to graph
 * 
 * // But also access source metadata
 * val source = graphRef.sourceRepository  // Returns repo
 * ```
 */
class GraphRef(
    private val graph: RdfGraph,
    override val sourceRepository: RdfRepository? = null,
    override val sourceGraphName: Iri? = null
) : RdfGraph by graph, SourceTrackedGraph {
    
    /**
     * Get the underlying graph being referenced.
     * 
     * @return The actual graph instance
     */
    fun getReferencedGraph(): RdfGraph = graph
    
    /**
     * Check if this reference has source tracking information.
     * 
     * @return true if both sourceRepository and sourceGraphName are known
     */
    fun hasSourceTracking(): Boolean = sourceRepository != null
    
    /**
     * Create a new GraphRef with updated source tracking.
     * 
     * @param repository The source repository
     * @param graphName The graph name (null for default graph)
     * @return A new GraphRef with updated source information
     */
    fun withSource(repository: RdfRepository, graphName: Iri? = null): GraphRef {
        return GraphRef(graph, repository, graphName)
    }
}

/**
 * Extension function to convert any graph to a GraphRef.
 * 
 * If the graph already implements SourceTrackedGraph, preserves that information.
 * Otherwise creates a GraphRef without source tracking.
 * 
 * @param sourceRepository Optional source repository
 * @param sourceGraphName Optional source graph name
 * @return A GraphRef wrapping this graph
 */
fun RdfGraph.asGraphRef(
    sourceRepository: RdfRepository? = null,
    sourceGraphName: Iri? = null
): GraphRef {
    return when (this) {
        is GraphRef -> this  // Already a GraphRef
        is SourceTrackedGraph -> GraphRef(
            this,
            sourceRepository ?: this.sourceRepository,
            sourceGraphName ?: this.sourceGraphName
        )
        else -> GraphRef(this, sourceRepository, sourceGraphName)
    }
}

