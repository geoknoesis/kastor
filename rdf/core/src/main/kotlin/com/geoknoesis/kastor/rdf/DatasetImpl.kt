package com.geoknoesis.kastor.rdf

import java.io.Closeable

/**
 * Implementation of Dataset with optimization for same-repository graphs.
 * 
 * **Optimization Strategy:**
 * 1. Groups graphs by source repository
 * 2. For graphs from the same repository, uses FROM clauses (no materialization)
 * 3. For graphs from different repositories, materializes union as fallback
 * 
 * This follows the industry best practice of optimizing at the query level
 * when possible, and materializing only when necessary.
 */
internal class DatasetImpl(
    private val defaultGraphRefs: List<GraphRef>,
    private val namedGraphRefs: Map<Iri, GraphRef>
) : Dataset {
    
    override val defaultGraphs: List<RdfGraph> = defaultGraphRefs
    override val namedGraphs: Map<Iri, RdfGraph> = namedGraphRefs
    
    /**
     * Groups graphs by repository for optimization.
     */
    private data class RepositoryGroup(
        val repository: RdfRepository,
        val defaultGraphNames: List<Iri?>,  // null = repository's default graph
        val namedGraphNames: List<Iri>
    )
    
    /**
     * Groups of graphs by repository.
     */
    private val repositoryGroups: List<RepositoryGroup> by lazy {
        buildRepositoryGroups()
    }
    
    /**
     * Graphs without source tracking (need materialization).
     */
    private val untrackedDefaultGraphs: List<RdfGraph> by lazy {
        defaultGraphRefs.filter { !it.hasSourceTracking() }.map { it.getReferencedGraph() }
    }
    
    override val defaultGraph: RdfGraph by lazy {
        // Optimize if all graphs are from same repository
        if (untrackedDefaultGraphs.isEmpty() && repositoryGroups.size == 1) {
            val group = repositoryGroups.first()
            if (group.defaultGraphNames.size == defaultGraphRefs.size) {
                // All from same repo - use optimized union
                return@lazy OptimizedUnionGraph(group.repository, group.defaultGraphNames)
            }
        }
        
        // Fallback to materialized union
        if (defaultGraphRefs.size == 1) {
            defaultGraphRefs.first().getReferencedGraph()
        } else {
            UnionGraph(defaultGraphRefs.map { it.getReferencedGraph() })
        }
    }
    
    override fun getNamedGraph(name: Iri): RdfGraph? = namedGraphRefs[name]?.getReferencedGraph()
    
    override fun hasNamedGraph(name: Iri): Boolean = namedGraphRefs.containsKey(name)
    
    override fun listNamedGraphs(): List<Iri> = namedGraphRefs.keys.toList()
    
    override fun select(query: SparqlSelect): SparqlQueryResult {
        return tryOptimizedExecution(query) ?: executeOnMaterializedUnion(query)
    }
    
    override fun ask(query: SparqlAsk): Boolean {
        return tryOptimizedAskExecution(query) ?: executeAskOnMaterializedUnion(query)
    }
    
    override fun construct(query: SparqlConstruct): Sequence<RdfTriple> {
        return tryOptimizedConstructExecution(query) ?: executeConstructOnMaterializedUnion(query)
    }
    
    override fun describe(query: SparqlDescribe): Sequence<RdfTriple> {
        return tryOptimizedDescribeExecution(query) ?: executeDescribeOnMaterializedUnion(query)
    }
    
    override fun close() {
        val graphsToClose = LinkedHashSet<RdfGraph>()
        defaultGraphRefs.forEach { graphsToClose.add(it.getReferencedGraph()) }
        namedGraphRefs.values.forEach { graphsToClose.add(it.getReferencedGraph()) }
        graphsToClose.forEach { graph ->
            if (graph is Closeable) graph.close()
        }
    }
    
    /**
     * Build repository groups for optimization.
     */
    private fun buildRepositoryGroups(): List<RepositoryGroup> {
        val defaultByRepo = defaultGraphRefs
            .filter { it.hasSourceTracking() && it.sourceRepository != null }
            .mapNotNull { ref -> ref.sourceRepository?.let { it to ref.sourceGraphName } }
            .groupBy({ it.first }, { it.second })
        
        val namedByRepo = namedGraphRefs.entries
            .mapNotNull { (name, ref) ->
                if (!ref.hasSourceTracking()) return@mapNotNull null
                ref.sourceRepository?.let { repo -> repo to name }
            }
            .groupBy({ it.first }, { it.second })
        
        val allRepos = (defaultByRepo.keys + namedByRepo.keys).distinct()
        
        return allRepos.map { repo ->
            RepositoryGroup(
                repository = repo,
                defaultGraphNames = defaultByRepo[repo]?.toList() ?: emptyList(),
                namedGraphNames = namedByRepo[repo]?.toList() ?: emptyList()
            )
        }
    }
    
    /**
     * Try optimized execution using FROM clauses.
     */
    private fun tryOptimizedExecution(query: SparqlSelect): SparqlQueryResult? {
        if (untrackedDefaultGraphs.isNotEmpty() || repositoryGroups.size != 1) {
            return null
        }
        
        val group = repositoryGroups.first()
        val rewrittenQuery = rewriteQueryWithFromClauses(query, group) as SparqlSelect
        return group.repository.select(rewrittenQuery)
    }
    
    /**
     * Rewrite query to include FROM and FROM NAMED clauses.
     * 
     * Follows SPARQL 1.1 specification for dataset specification.
     */
    private fun rewriteQueryWithFromClauses(
        query: SparqlQuery,
        group: RepositoryGroup
    ): SparqlQuery {
        val queryText = query.sparql
        
        // Don't modify if query already specifies dataset
        // More robust check - look for FROM at start of line (after whitespace)
        val fromPattern = Regex("^\\s*FROM\\s+", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        if (queryText.contains(fromPattern)) {
            return query
        }
        
        val fromClauses = buildFromClauses(group)
        val fromNamedClauses = buildFromNamedClauses(group)
        
        val datasetClauses = buildString {
            if (fromClauses.isNotBlank()) {
                append(fromClauses.trim())
                append("\n")
            }
            if (fromNamedClauses.isNotBlank()) {
                append(fromNamedClauses.trim())
                append("\n")
            }
        }
        
        if (datasetClauses.isBlank()) {
            return query
        }
        
        // Insert after prefixes, before query type (SPARQL 1.1 grammar)
        val rewritten = insertDatasetClauses(queryText, datasetClauses.trim())
        
        return when (query) {
            is SparqlSelect -> SparqlSelectQuery(rewritten)
            is SparqlAsk -> SparqlAskQuery(rewritten)
            is SparqlConstruct -> SparqlConstructQuery(rewritten)
            is SparqlDescribe -> SparqlDescribeQuery(rewritten)
            else -> query
        }
    }
    
    /**
     * Build FROM clauses for default graphs.
     */
    private fun buildFromClauses(group: RepositoryGroup): String {
        return buildString {
            group.defaultGraphNames.forEach { graphName ->
                if (graphName != null) {
                    appendLine("FROM <${graphName.value}>")
                }
                // Default graph: no FROM clause needed (SPARQL 1.1 spec)
            }
        }
    }
    
    /**
     * Build FROM NAMED clauses for named graphs.
     */
    private fun buildFromNamedClauses(group: RepositoryGroup): String {
        return buildString {
            group.namedGraphNames.forEach { graphName ->
                appendLine("FROM NAMED <${graphName.value}>")
            }
            // Also include dataset's named graphs
            namedGraphRefs.forEach { (name, ref) ->
                if (ref.hasSourceTracking() && 
                    ref.sourceRepository == group.repository && 
                    !group.namedGraphNames.contains(name)) {
                    appendLine("FROM NAMED <${name.value}>")
                }
            }
        }
    }
    
    private fun insertDatasetClauses(query: String, clauses: String): String {
        // SPARQL 1.1 grammar: [PrefixDecl*] [SubSelect | SelectQuery | ConstructQuery | DescribeQuery | AskQuery]
        val pattern = Regex(
            """((?:PREFIX\s+\w+:\s*<[^>]+>\s*|VERSION\s+[\d.]+\s*)*)(SELECT|ASK|CONSTRUCT|DESCRIBE)""",
            RegexOption.IGNORE_CASE
        )
        return pattern.replace(query) { matchResult ->
            val before = matchResult.groupValues[1]
            val queryType = matchResult.groupValues[2]
            "$before\n$clauses\n$queryType"
        }
    }
    
    private fun tryOptimizedAskExecution(query: SparqlAsk): Boolean? {
        if (untrackedDefaultGraphs.isNotEmpty() || repositoryGroups.size != 1) return null
        val group = repositoryGroups.first()
        val rewrittenQuery = rewriteQueryWithFromClauses(query, group) as SparqlAsk
        return group.repository.ask(rewrittenQuery)
    }
    
    private fun tryOptimizedConstructExecution(query: SparqlConstruct): Sequence<RdfTriple>? {
        if (untrackedDefaultGraphs.isNotEmpty() || repositoryGroups.size != 1) return null
        val group = repositoryGroups.first()
        val rewrittenQuery = rewriteQueryWithFromClauses(query, group) as SparqlConstruct
        return group.repository.construct(rewrittenQuery)
    }
    
    private fun tryOptimizedDescribeExecution(query: SparqlDescribe): Sequence<RdfTriple>? {
        if (untrackedDefaultGraphs.isNotEmpty() || repositoryGroups.size != 1) return null
        val group = repositoryGroups.first()
        val rewrittenQuery = rewriteQueryWithFromClauses(query, group) as SparqlDescribe
        return group.repository.describe(rewrittenQuery)
    }
    
    // Materialized execution fallbacks
    
    /**
     * Materialize graphs into a temporary repository for query execution.
     * Uses streaming to avoid loading all triples into memory at once.
     */
    private fun materializeGraphs(repo: RdfRepository) {
        // Materialize default graphs using streaming
        defaultGraphRefs.forEach { ref ->
            val defaultGraphEditor = repo.editDefaultGraph()
            ref.getReferencedGraph().getTriplesSequence().forEach { triple ->
                defaultGraphEditor.addTriple(triple)
            }
        }
        
        // Materialize named graphs using streaming
        namedGraphRefs.forEach { (name, ref) ->
            val targetGraph = repo.createGraph(name)
            val graphEditor = targetGraph as MutableRdfGraph
            ref.getReferencedGraph().getTriplesSequence().forEach { triple ->
                graphEditor.addTriple(triple)
            }
        }
    }
    
    /**
     * Execute query on materialized union with proper resource management.
     */
    private fun <T> executeOnMaterializedUnion(
        query: SparqlQuery,
        execute: (RdfRepository, SparqlQuery) -> T
    ): T {
        val unionRepo = Rdf.memory()
        try {
            materializeGraphs(unionRepo)
            return execute(unionRepo, query)
        } finally {
            unionRepo.close()
        }
    }
    
    private fun executeOnMaterializedUnion(query: SparqlSelect): SparqlQueryResult {
        return executeOnMaterializedUnion(query) { repo, q -> repo.select(q as SparqlSelect) }
    }
    
    private fun executeAskOnMaterializedUnion(query: SparqlAsk): Boolean {
        return executeOnMaterializedUnion(query) { repo, q -> repo.ask(q as SparqlAsk) }
    }
    
    private fun executeConstructOnMaterializedUnion(query: SparqlConstruct): Sequence<RdfTriple> {
        return executeOnMaterializedUnion(query) { repo, q -> repo.construct(q as SparqlConstruct) }
    }
    
    private fun executeDescribeOnMaterializedUnion(query: SparqlDescribe): Sequence<RdfTriple> {
        return executeOnMaterializedUnion(query) { repo, q -> repo.describe(q as SparqlDescribe) }
    }
}

/**
 * Optimized union graph that uses FROM clauses instead of materialization.
 * 
 * This is a virtual graph that delegates to the repository with FROM clauses,
 * following the industry pattern of query-level optimization.
 */
internal class OptimizedUnionGraph(
    private val repository: RdfRepository,
    private val graphNames: List<Iri?>  // null = default graph
) : RdfGraph {
    
    /**
     * Build FROM clauses for the graphs in this union.
     * Filters out null values (default graphs) and creates proper FROM clauses.
     */
    private fun buildFromClauses(): String {
        return graphNames
            .filterNotNull()
            .joinToString("\n") { "FROM <${it.value}>" }
    }
    
    override fun hasTriple(triple: RdfTriple): Boolean {
        val fromClauses = buildFromClauses()
        val fromClauseText = if (fromClauses.isNotEmpty()) "$fromClauses\n" else ""
        
        val query = """
            ASK
            $fromClauseText
            WHERE {
                <${triple.subject}> <${triple.predicate}> ${formatTerm(triple.obj)} .
            }
        """.trimIndent()
        
        return repository.ask(SparqlAskQuery(query))
    }
    
    override fun getTriples(): List<RdfTriple> {
        val fromClauses = buildFromClauses()
        val fromClauseText = if (fromClauses.isNotEmpty()) "$fromClauses\n" else ""
        
        val query = """
            SELECT ?s ?p ?o
            $fromClauseText
            WHERE {
                ?s ?p ?o .
            }
        """.trimIndent()
        
        val result = repository.select(SparqlSelectQuery(query))
        return result.mapNotNull { binding ->
            val s = binding.get("s") as? RdfResource ?: return@mapNotNull null
            val p = binding.get("p") as? Iri ?: return@mapNotNull null
            val o = binding.get("o") ?: return@mapNotNull null
            RdfTriple(s, p, o)
        }
    }
    
    override fun getTriplesSequence(): Sequence<RdfTriple> {
        val fromClauses = buildFromClauses()
        val fromClauseText = if (fromClauses.isNotEmpty()) "$fromClauses\n" else ""

        val query = """
            SELECT ?s ?p ?o
            $fromClauseText
            WHERE {
                ?s ?p ?o .
            }
        """.trimIndent()

        val result = repository.select(SparqlSelectQuery(query))
        return result.asSequence().mapNotNull { binding ->
            val s = binding.get("s") as? RdfResource ?: return@mapNotNull null
            val p = binding.get("p") as? Iri ?: return@mapNotNull null
            val o = binding.get("o") ?: return@mapNotNull null
            RdfTriple(s, p, o)
        }
    }
    
    override fun size(): Int {
        return try {
            // Try optimized COUNT query
            val fromClauses = buildFromClauses()
            val fromClauseText = if (fromClauses.isNotEmpty()) "$fromClauses\n" else ""
            
            val query = """
                SELECT (COUNT(*) AS ?count)
                $fromClauseText
                WHERE {
                    ?s ?p ?o .
                }
            """.trimIndent()
            
            val result = repository.select(SparqlSelectQuery(query))
            val count = result.firstOrNull()?.get("count") as? TypedLiteral
            count?.lexical?.toIntOrNull() ?: getTriples().size
        } catch (e: Exception) {
            // Fallback to materialization if COUNT fails
            getTriples().size
        }
    }
    
    private fun formatTerm(term: RdfTerm): String {
        return when (term) {
            is Iri -> "<${term.value}>"
            is BlankNode -> if (term.id.startsWith("_:")) term.id else "_:${term.id}"
            is Literal -> {
                when (term) {
                    is LangString -> "\"${term.lexical}\"@${term.lang}"
                    is TypedLiteral -> "\"${term.lexical}\"^^<${term.datatype.value}>"
                    else -> "\"${term.lexical}\""
                }
            }
            else -> term.toString()
        }
    }
}

/**
 * Materialized union graph (fallback when optimization not possible).
 */
internal class UnionGraph(private val graphs: List<RdfGraph>) : RdfGraph {
    override fun hasTriple(triple: RdfTriple): Boolean {
        return graphs.any { it.hasTriple(triple) }
    }
    
    override fun getTriples(): List<RdfTriple> {
        val deduped = LinkedHashSet<RdfTriple>()
        graphs.forEach { graph -> deduped.addAll(graph.getTriples()) }
        return deduped.toList()
    }
    
    override fun getTriplesSequence(): Sequence<RdfTriple> {
        return sequence {
            val seen = HashSet<RdfTriple>()
            graphs.asSequence().flatMap { it.getTriplesSequence() }.forEach { triple ->
                if (seen.add(triple)) {
                    yield(triple)
                }
            }
        }
    }
    
    override fun size(): Int {
        return getTriples().size
    }
}

