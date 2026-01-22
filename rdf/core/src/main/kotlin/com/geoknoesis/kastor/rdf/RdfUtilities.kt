package com.geoknoesis.kastor.rdf

/**
 * Utility functions and extensions for common RDF operations.
 * These utilities improve developer experience and provide helpful debugging tools.
 * 
 * **API Stability:** These utilities are stable and part of the public API.
 * They follow Kotlin stdlib patterns and are designed for long-term compatibility.
 */

// === ERROR HANDLING UTILITIES ===

/**
 * Executes a SPARQL SELECT query and returns null if it fails.
 * 
 * This is a safe wrapper around [RdfRepository.select] that catches [RdfQueryException]
 * and allows custom error handling.
 * 
 * **Example:**
 * ```kotlin
 * val result = repo.selectOrNull(query) { e ->
 *     logger.error("Query failed: ${e.query}", e)
 *     null
 * }
 * result?.forEach { binding ->
 *     println(binding.getString("name"))
 * }
 * ```
 * 
 * @param query The SPARQL SELECT query to execute
 * @param onError Error handler that receives the exception and returns a fallback result
 * @return SparqlQueryResult if successful, null if an error occurred
 */
inline fun RdfRepository.selectOrNull(
    query: SparqlSelect,
    onError: (RdfQueryException) -> SparqlQueryResult? = { null }
): SparqlQueryResult? = try {
    select(query)
} catch (e: RdfQueryException) {
    onError(e)
}

/**
 * Executes a SPARQL SELECT query and returns a [Result] type for functional error handling.
 * 
 * This allows using Kotlin's [Result] API for error handling:
 * ```kotlin
 * repo.selectResult(query) { result ->
 *     result.map { it.getString("name") }
 * }.onSuccess { names ->
 *     println("Found names: $names")
 * }.onFailure { e ->
 *     logger.error("Query failed", e)
 * }
 * ```
 * 
 * @param query The SPARQL SELECT query to execute
 * @param transform Transformation function to apply to the query result
 * @return Result containing the transformed value or the exception
 */
inline fun <T> RdfRepository.selectResult(
    query: SparqlSelect,
    transform: (SparqlQueryResult) -> T
): Result<T> = runCatching {
    transform(select(query))
}

// === COMPARISON UTILITIES ===

/**
 * Checks if two RDF terms are equivalent.
 * 
 * Two terms are equivalent if they are of the same type and have the same value:
 * - Two IRIs are equivalent if their string values are equal
 * - Two Literals are equivalent if their lexical values and datatypes are equal
 * - Two BlankNodes are equivalent if their IDs are equal
 * - Two TripleTerms are equivalent if their embedded triples are equivalent
 * 
 * **Example:**
 * ```kotlin
 * val term1 = Iri("http://example.org/person")
 * val term2 = Iri("http://example.org/person")
 * assertTrue(term1 equivalentTo term2)
 * ```
 * 
 * @param other The other RDF term to compare
 * @return true if the terms are equivalent, false otherwise
 */
infix fun RdfTerm.equivalentTo(other: RdfTerm): Boolean = when {
    this is Iri && other is Iri -> value == other.value
    this is Literal && other is Literal -> 
        lexical == other.lexical && datatype == other.datatype
    this is BlankNode && other is BlankNode -> id == other.id
    this is TripleTerm && other is TripleTerm -> 
        triple.subject equivalentTo other.triple.subject &&
        triple.predicate equivalentTo other.triple.predicate &&
        triple.obj equivalentTo other.triple.obj
    else -> false
}

// === DEBUG UTILITIES ===

/**
 * Generates a debug string representation of the graph.
 * 
 * **Example:**
 * ```kotlin
 * println(graph.debug())
 * // Output:
 * // Graph contains 3 triples:
 * //   <http://example.org/person> <http://xmlns.com/foaf/0.1/name> "Alice"
 * //   <http://example.org/person> <http://xmlns.com/foaf/0.1/age> "30"^^<http://www.w3.org/2001/XMLSchema#integer>
 * //   <http://example.org/person> <http://xmlns.com/foaf/0.1/email> "alice@example.com"
 * ```
 * 
 * @return A formatted string representation of the graph
 */
fun RdfGraph.debug(): String = buildString {
    appendLine("Graph contains ${size()} triples:")
    getTriples().forEach { triple ->
        appendLine("  ${triple.subject} ${triple.predicate} ${triple.obj}")
    }
}

/**
 * Generates a pretty-printed string representation of the graph.
 * 
 * This is a more compact format than [debug], suitable for logging or display.
 * 
 * **Example:**
 * ```kotlin
 * println(graph.prettyPrint())
 * // Output:
 * // <http://example.org/person> <http://xmlns.com/foaf/0.1/name> "Alice"
 * // <http://example.org/person> <http://xmlns.com/foaf/0.1/age> "30"^^<http://www.w3.org/2001/XMLSchema#integer>
 * // <http://example.org/person> <http://xmlns.com/foaf/0.1/email> "alice@example.com"
 * ```
 * 
 * @return A pretty-printed string representation of all triples
 */
fun RdfGraph.prettyPrint(): String = 
    getTriples().joinToString("\n") { "${it.subject} ${it.predicate} ${it.obj}" }

// === VALIDATION UTILITIES ===

/**
 * Validates a list of IRI strings and converts them to [Iri] instances.
 * 
 * **Example:**
 * ```kotlin
 * val iris = listOf("http://example.org/person", "http://example.org/place")
 *     .validateIris()
 * ```
 * 
 * @return List of validated Iri instances
 * @throws IllegalArgumentException if any IRI string is invalid
 */
fun List<String>.validateIris(): List<Iri> = map { it.toIri() }

/**
 * Gets all triples from a specific graph or the default graph as a lazy sequence.
 * 
 * **Performance:** O(1) to create the sequence, O(n) to iterate.
 * This method provides lazy evaluation, avoiding intermediate list creation.
 * Use this for large graphs where you don't need all triples at once.
 * 
 * **Example:**
 * ```kotlin
 * // Process triples lazily without loading all into memory
 * repo.getTriplesSequence(graphName)
 *     .filter { it.predicate == FOAF.name }
 *     .take(100)
 *     .forEach { println(it) }
 * ```
 * 
 * @param graphName The name of the graph, or null for the default graph
 * @return Sequence of all triples in the specified graph (lazy evaluation)
 */
fun RdfRepository.getTriplesSequence(graphName: Iri? = null): Sequence<RdfTriple> =
    if (graphName == null) defaultGraph.getTriplesSequence()
    else getGraph(graphName).getTriplesSequence()

