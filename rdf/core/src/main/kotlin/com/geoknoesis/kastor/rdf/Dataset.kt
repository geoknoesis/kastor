package com.geoknoesis.kastor.rdf

import java.io.Closeable

/**
 * Represents a Dataset as defined in SPARQL 1.1 specification.
 * 
 * A dataset consists of:
 * - **Default Graph**: The union of one or more graphs (specified via FROM clauses)
 * - **Named Graphs**: Graphs accessible via GRAPH patterns (specified via FROM NAMED clauses)
 * 
 * This interface follows the industry standard pattern used by Apache Jena and RDF4J,
 * where datasets are separate from repositories and can reference graphs from multiple sources.
 * 
 * **Key Design Principles:**
 * - **Read-only**: Datasets are immutable views for query execution
 * - **Multi-source**: Graphs can come from different repositories
 * - **Optimized**: Uses FROM clauses when graphs share a repository
 * - **SPARQL-compliant**: Follows SPARQL 1.1 dataset semantics exactly
 * 
 * **Example:**
 * ```kotlin
 * val usersRepo = Rdf.memory()
 * val productsRepo = Rdf.memory()
 * 
 * val dataset = Dataset {
 *     // Default graph: union of these graphs
 *     defaultGraph(usersRepo.defaultGraph)
 *     defaultGraph(productsRepo.getGraph(iri("http://example.org/products")))
 *     
 *     // Named graphs accessible via GRAPH <name> { ... }
 *     namedGraph(iri("http://example.org/users"), usersRepo.getGraph(iri("http://example.org/users")))
 *     namedGraph(iri("http://example.org/products"), productsRepo.getGraph(iri("http://example.org/products")))
 * }
 * 
 * // Execute query against dataset
 * val result = dataset.select(query)
 * ```
 * 
 * @see RdfRepository for mutable dataset operations
 * @see SparqlQueryable for minimal read-only query interface
 * @see SparqlMutable for mutable query operations
 * @see <a href="https://www.w3.org/TR/sparql11-query/#datasets">SPARQL 1.1 Dataset Specification</a>
 */
interface Dataset : SparqlQueryable {
    
    /**
     * List of graphs whose union forms the default graph.
     * 
     * According to SPARQL 1.1, when multiple FROM clauses are specified,
     * the default graph is the union of all those graphs.
     * 
     * @return List of graphs that form the default graph (union)
     */
    val defaultGraphs: List<RdfGraph>
    
    /**
     * Map of graph names to graphs for named graph access.
     * 
     * These graphs are accessible via `GRAPH <name> { ... }` patterns in SPARQL queries.
     * 
     * @return Map of graph IRIs to their corresponding graphs
     */
    val namedGraphs: Map<Iri, RdfGraph>
    
    /**
     * Get the union of all default graphs as a single graph view.
     * 
     * This is what queries use when no FROM clause is specified in the query.
     * The implementation may optimize this by using FROM clauses when possible.
     * 
     * @return A graph representing the union of all default graphs
     */
    override val defaultGraph: RdfGraph
    
    /**
     * Get a named graph by IRI.
     * 
     * @param name The IRI of the named graph
     * @return The named graph, or null if it doesn't exist in this dataset
     */
    fun getNamedGraph(name: Iri): RdfGraph?
    
    /**
     * Check if a named graph exists in this dataset.
     * 
     * @param name The IRI of the named graph
     * @return true if the named graph exists, false otherwise
     */
    fun hasNamedGraph(name: Iri): Boolean
    
    /**
     * List all named graph IRIs in this dataset.
     * 
     * @return List of IRIs for all named graphs in this dataset
     */
    fun listNamedGraphs(): List<Iri>
    
    /**
     * Minimal graph access for compatibility with SparqlQueryable.
     * Returns the named graph if it exists, otherwise the default graph.
     * 
     * @param name The IRI of the graph
     * @return The graph (named or default)
     */
    override fun graph(name: Iri): RdfGraph = getNamedGraph(name) ?: defaultGraph
}

/**
 * Builder for constructing datasets.
 * 
 * Follows the builder pattern used throughout the Kastor SDK for fluent,
 * type-safe construction of complex objects.
 * 
 * **Example:**
 * ```kotlin
 * val dataset = Dataset {
 *     defaultGraph(repo1.defaultGraph)
 *     defaultGraph(repo2.getGraph(iri("http://example.org/graph2")))
 *     namedGraph(iri("http://example.org/named1"), repo1.getGraph(iri("http://example.org/named1")))
 *     namedGraph(iri("http://example.org/named2"), repo2.defaultGraph)
 * }
 * ```
 */
class DatasetBuilder {
    private val defaultGraphs = mutableListOf<RdfGraph>()
    private val namedGraphs = mutableMapOf<Iri, RdfGraph>()
    
    /**
     * Add a graph to the default graphs (union).
     * 
     * @param graph The graph to add to the default graph union
     * @return This builder for method chaining
     */
    fun defaultGraph(graph: RdfGraph): DatasetBuilder {
        defaultGraphs.add(graph)
        return this
    }
    
    /**
     * Add a repository's default graph to the default graphs.
     * 
     * @param repository The repository whose default graph to add
     * @return This builder for method chaining
     */
    fun defaultGraph(repository: RdfRepository): DatasetBuilder {
        return defaultGraph(repository.defaultGraph.asGraphRef(repository, null))
    }
    
    /**
     * Add a named graph to the dataset.
     * 
     * @param name The IRI name for this graph in the dataset
     * @param graph The graph to add
     * @return This builder for method chaining
     */
    fun namedGraph(name: Iri, graph: RdfGraph): DatasetBuilder {
        namedGraphs[name] = graph
        return this
    }
    
    /**
     * Add a named graph from a repository.
     * 
     * @param name The IRI name for this graph in the dataset
     * @param repository The repository containing the graph
     * @param sourceGraphName The name of the graph in the source repository (null for default graph)
     * @return This builder for method chaining
     */
    fun namedGraph(name: Iri, repository: RdfRepository, sourceGraphName: Iri? = null): DatasetBuilder {
        val graph = sourceGraphName?.let { repository.getGraph(it) } ?: repository.defaultGraph
        return namedGraph(name, graph.asGraphRef(repository, sourceGraphName))
    }
    
    /**
     * Add multiple default graphs at once.
     * 
     * @param graphs The graphs to add to the default graph union
     * @return This builder for method chaining
     */
    fun defaultGraphs(vararg graphs: RdfGraph): DatasetBuilder {
        graphs.forEach { defaultGraph(it) }
        return this
    }
    
    /**
     * Add multiple named graphs at once.
     * 
     * @param pairs Pairs of (graph name, graph) to add
     * @return This builder for method chaining
     */
    fun namedGraphs(vararg pairs: Pair<Iri, RdfGraph>): DatasetBuilder {
        pairs.forEach { (name, graph) -> namedGraph(name, graph) }
        return this
    }
    
    /**
     * Build the dataset.
     * 
     * @return A new Dataset instance
     * @throws IllegalStateException if no default graphs are specified or validation fails
     */
    fun build(): Dataset {
        require(defaultGraphs.isNotEmpty()) { 
            "At least one default graph is required for a dataset" 
        }
        // Check for duplicate named graph names
        require(namedGraphs.size == namedGraphs.keys.distinct().size) {
            "Duplicate named graph names are not allowed"
        }
        return DatasetImpl(
            defaultGraphs.map { it.asGraphRef() },
            namedGraphs.mapValues { it.value.asGraphRef() }
        )
    }
}

/**
 * Factory function for building datasets.
 * 
 * **Example:**
 * ```kotlin
 * val dataset = Dataset {
 *     defaultGraph(repo.defaultGraph)
 *     namedGraph(iri("http://example.org/graph"), repo.getGraph(iri("http://example.org/graph")))
 * }
 * ```
 * 
 * @param configure Lambda to configure the dataset builder
 * @return A new Dataset instance
 */
fun Dataset(configure: DatasetBuilder.() -> Unit): Dataset {
    return DatasetBuilder().apply(configure).build()
}

