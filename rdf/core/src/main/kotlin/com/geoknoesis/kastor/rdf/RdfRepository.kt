package com.geoknoesis.kastor.rdf

/**
 * Main interface for RDF repository operations.
 * Provides a unified API for all RDF operations including graph management and SPARQL queries.
 * 
 * **Relationship with [Dataset], [SparqlQueryable], and [SparqlMutable]:**
 * - [SparqlQueryable] is the minimal interface providing read-only SPARQL query operations
 * - [SparqlMutable] extends [SparqlQueryable] and adds [update] for SPARQL UPDATE operations
 * - [Dataset] extends [SparqlQueryable] and represents a SPARQL dataset (read-only, multiple default/named graphs)
 * - [RdfRepository] extends [Dataset] and [SparqlMutable], adding graph management operations (create/remove graphs, editing, etc.)
 * 
 * A repository is essentially a mutable dataset. All [RdfRepository] implementations also implement [Dataset],
 * [SparqlMutable], and [SparqlQueryable], so you can use any interface depending on your needs.
 */
interface RdfRepository : Dataset, SparqlMutable {
    
    // === GRAPH OPERATIONS ===
    
    /**
     * Get the default graph for this repository.
     */
    override val defaultGraph: RdfGraph
    
    /**
     * Get a named graph by IRI.
     */
    fun getGraph(name: Iri): RdfGraph

    /**
     * Minimal core API graph access.
     */
    override fun graph(name: Iri): RdfGraph = getGraph(name)
    
    /**
     * Check if a named graph exists.
     */
    fun hasGraph(name: Iri): Boolean
    
    /**
     * List all named graphs in the repository.
     */
    fun listGraphs(): List<Iri>
    
    // === DATASET INTERFACE IMPLEMENTATION ===
    
    /**
     * List of graphs whose union forms the default graph.
     * For a repository, this is just the single default graph.
     */
    override val defaultGraphs: List<RdfGraph>
        get() = listOf(defaultGraph)
    
    /**
     * Map of graph names to graphs for named graph access.
     * For a repository, this includes all named graphs.
     * 
     * Note: Implementations should cache this value to avoid expensive recomputation.
     */
    override val namedGraphs: Map<Iri, RdfGraph>
        get() = listGraphs().associateWith { getGraph(it) }
    
    /**
     * Get a named graph by IRI (Dataset interface method).
     */
    override fun getNamedGraph(name: Iri): RdfGraph? {
        return if (hasGraph(name)) getGraph(name) else null
    }
    
    /**
     * Check if a named graph exists (Dataset interface method).
     */
    override fun hasNamedGraph(name: Iri): Boolean = hasGraph(name)
    
    /**
     * List all named graph IRIs (Dataset interface method).
     */
    override fun listNamedGraphs(): List<Iri> = listGraphs()
    
    /**
     * Create a new named graph.
     *
     * Creating a graph is a write, even when the graph stays empty: it needs write access, so call it outside
     * transactions or inside [transaction]. Inside [readTransaction] it is rejected (the bundled in-memory provider
     * throws), and inside [transaction] it is undone on rollback.
     */
    fun createGraph(name: Iri): RdfGraph
    
    /**
     * Remove a named graph and all its triples.
     */
    fun removeGraph(name: Iri): Boolean

    /**
     * Get a mutable graph for the default graph.
     */
    fun editDefaultGraph(): MutableRdfGraph

    /**
     * Get a mutable graph for a named graph.
     */
    fun editGraph(name: Iri): MutableRdfGraph
    
    // === QUERY OPERATIONS ===
    
    /**
     * Execute a SPARQL SELECT query.
     * 
     * **Error Handling:**
     * - Throws [RdfQueryException] if the query fails to parse or execute
     * - The exception includes the query string for debugging
     * - Use [selectOrNull] or [selectResult] for functional error handling
     * 
     * **Error Handling Pattern:**
     * - **Technical failures** (parsing, execution) → [RdfQueryException]
     * - **Semantic failures** (validation) → `ValidationResult` sealed class
     * - **Operations that should never fail** → Direct return types
     * 
     * @param query The SPARQL SELECT query to execute
     * @return SparqlQueryResult containing the query results
     * @throws RdfQueryException if the query fails to parse or execute
     */
    override fun select(query: SparqlSelect): SparqlQueryResult
    
    /**
     * Execute a SPARQL ASK query.
     */
    override fun ask(query: SparqlAsk): Boolean
    
    /**
     * Execute a SPARQL CONSTRUCT query.
     */
    override fun construct(query: SparqlConstruct): Sequence<RdfTriple>
    
    /**
     * Execute a SPARQL DESCRIBE query.
     */
    override fun describe(query: SparqlDescribe): Sequence<RdfTriple>
    
    /**
     * Execute a SPARQL UPDATE operation.
     */
    override fun update(query: UpdateQuery)
    
    // === TRANSACTION OPERATIONS ===
    
    /**
     * Execute operations within a transaction.
     * 
     * **Resource Management:**
     * - Transaction is automatically rolled back on exception
     * - Transaction is committed on successful completion
     * - Resources are cleaned up even if exception occurs
     * 
     * **Example:**
     * ```kotlin
     * repo.transaction {
     *     repo.addTriple(triple1)
     *     repo.addTriple(triple2)
     *     // If any operation throws, all changes are rolled back
     * }
     * ```
     * 
     * @param operations The operations to execute in the transaction
     * @throws RdfTransactionException if transaction fails
     */
    fun transaction(operations: RdfRepository.() -> Unit)
    
    /**
     * Execute read-only operations within a transaction.
     * 
     * **Resource Management:**
     * - Read transaction provides consistent view of data
     * - No changes are committed (read-only)
     * - Resources are cleaned up on completion
     * 
     * @param operations The read-only operations to execute
     * @throws RdfTransactionException if transaction fails
     */
    fun readTransaction(operations: RdfRepository.() -> Unit)
    
    // === UTILITY OPERATIONS ===
    
    /**
     * Clear all data from the repository.
     */
    fun clear(): Boolean
    
    /**
     * Check if the repository is closed.
     */
    fun isClosed(): Boolean
    
    /**
     * Get the capabilities of this repository.
     */
    fun getCapabilities(): ProviderCapabilities
}
