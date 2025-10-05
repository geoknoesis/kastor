package com.geoknoesis.kastor.rdf

import kotlin.time.Duration
import kotlin.time.measureTime
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.dsl.TripleDsl

/**
 * Super Sleek RDF Extensions
 * 
 * This file provides elegant, Kotlin-idiomatic extensions that make RDF operations
 * feel natural and intuitive. These extensions transform the core API into a
 * truly delightful developer experience.
 */

// === FLUENT INTERFACE ===

/**
 * Fluent interface for chaining repository operations.
 */
class RdfOperations(private val repository: RdfRepository) {
    
    fun add(configure: TripleDsl.() -> Unit): RdfOperations {
        repository.add(configure)
        return this
    }
    
    fun addToGraph(graphName: Iri, configure: TripleDsl.() -> Unit): RdfOperations {
        repository.addToGraph(graphName, configure)
        return this
    }
    
    fun query(sparql: String): QueryResult {
        return repository.query(sparql)
    }
    
    fun ask(sparql: String): Boolean {
        return repository.ask(sparql)
    }
    
    fun construct(sparql: String): List<RdfTriple> {
        return repository.construct(sparql)
    }
    
    fun describe(sparql: String): List<RdfTriple> {
        return repository.describe(sparql)
    }
    
    fun update(sparql: String): RdfOperations {
        repository.update(sparql)
        return this
    }
    
    fun transaction(operations: RdfRepository.() -> Unit): RdfOperations {
        repository.transaction(operations)
        return this
    }
    
    fun readTransaction(operations: RdfRepository.() -> Unit): RdfOperations {
        repository.readTransaction(operations)
        return this
    }
    
    fun clear(): RdfOperations {
        repository.clear()
        return this
    }
    
    fun getStatistics(): RepositoryStatistics {
        return repository.getStatistics()
    }
    
    fun getPerformanceMonitor(): PerformanceMonitor {
        return repository.getPerformanceMonitor()
    }
}

/**
 * Start a fluent operation chain.
 */
fun RdfRepository.fluent(): RdfOperations = RdfOperations(this)

// === STRING CONVERSIONS ===

/**
 * Convert a string to an IRI.
 */
fun String.toIri(): Iri = Iri(this)

/**
 * Convert a string to a resource (IRI).
 */
fun String.toResource(): RdfResource = Iri(this)

/**
 * Convert a string to a literal.
 */
fun String.toLiteral(): Literal = Literal(this, XSD.string)



// === QUERY RESULT EXTENSIONS ===

/**
 * Get the first result as a specific type.
 */
inline fun <reified T> QueryResult.firstAs(): T? {
    return firstOrNull()?.let { binding ->
        when (T::class) {
            String::class -> binding.getString("value") as T?
            Int::class -> binding.getInt("value") as T?
            Double::class -> binding.getDouble("value") as T?
            Boolean::class -> binding.getBoolean("value") as T?
            else -> null
        }
    }
}

/**
 * Map results to a specific type.
 */
inline fun <reified T> QueryResult.mapAs(): List<T> {
    return mapNotNull { binding ->
        when (T::class) {
            String::class -> binding.getString("value") as T?
            Int::class -> binding.getInt("value") as T?
            Double::class -> binding.getDouble("value") as T?
            Boolean::class -> binding.getBoolean("value") as T?
            else -> null
        }
    }
}

// === REPOSITORY QUERY EXTENSIONS ===

/**
 * Get the first result of a query.
 */
fun RdfRepository.queryFirst(sparql: String): BindingSet? {
    return query(sparql).firstOrNull()
}

/**
 * Get results as a list of strings.
 */
fun RdfRepository.queryList(sparql: String): List<String> {
    return query(sparql).mapNotNull { it.getString("value") }
}

/**
 * Get results as a map.
 */
fun <K, V> RdfRepository.queryMap(
    sparql: String,
    keySelector: (BindingSet) -> K,
    valueSelector: (BindingSet) -> V
): Map<K, V> {
    return query(sparql).associate { keySelector(it) to valueSelector(it) }
}

// === PERFORMANCE MONITORING ===

/**
 * Execute a query and return both result and timing.
 */
fun RdfRepository.queryTimed(sparql: String): Pair<QueryResult, Duration> {
    val duration = measureTime { query(sparql) }
    return query(sparql) to duration
}

/**
 * Execute an operation and return timing.
 */
fun RdfRepository.operationTimed(operation: RdfRepository.() -> Unit): Pair<Unit, Duration> {
    val duration = measureTime { operation() }
    return Unit to duration
}

// === GRAPH OPERATIONS ===

/**
 * Add triples to a graph and return the graph for chaining.
 */
fun RdfGraph.addTriplesAndReturn(triples: Collection<RdfTriple>): RdfGraph {
    addTriples(triples)
    return this
}

/**
 * Remove triples from a graph and return the graph for chaining.
 */
fun RdfGraph.removeTriplesAndReturn(triples: Collection<RdfTriple>): RdfGraph {
    removeTriples(triples)
    return this
}

/**
 * Clear a graph and return the graph for chaining.
 */
fun RdfGraph.clearAndReturn(): RdfGraph {
    clear()
    return this
}

// === TRIPLE DSL EXTENSIONS ===

/**
 * Add multiple triples to the DSL.
 */
fun TripleDsl.addTriples(triples: Collection<RdfTriple>) {
    this.triples.addAll(triples)
}

/**
 * Create a triple explicitly.
 */
fun TripleDsl.triple(subject: RdfResource, predicate: Iri, obj: RdfTerm) {
    triples.add(RdfTriple(subject, predicate, obj))
}

/**
 * Create a triple with string predicate.
 */
fun TripleDsl.triple(subject: RdfResource, predicate: String, obj: RdfTerm) {
    triples.add(RdfTriple(subject, Iri(predicate), obj))
}

// === REPOSITORY BATCH OPERATIONS ===

/**
 * Add triples in batches for better performance.
 */
fun RdfRepository.addBatch(batchSize: Int = 1000, configure: TripleDsl.() -> Unit) {
    val dsl = TripleDsl().apply(configure)
    dsl.triples.chunked(batchSize).forEach { batch ->
        addTriples(batch)
    }
}

/**
 * Add triples to a specific graph in batches.
 */
fun RdfRepository.addBatchToGraph(graphName: Iri, batchSize: Int = 1000, configure: TripleDsl.() -> Unit) {
    val dsl = TripleDsl().apply(configure)
    dsl.triples.chunked(batchSize).forEach { batch ->
        getGraph(graphName).addTriples(batch)
    }
}

// === UTILITY EXTENSIONS ===

/**
 * Check if the repository has any triples.
 */
fun RdfRepository.hasTriples(): Boolean {
    return !defaultGraph.getTriples().isEmpty()
}

/**
 * Get the total number of triples.
 */
fun RdfRepository.tripleCount(): Int {
    return defaultGraph.size()
}

/**
 * Get repository size in a human-readable format.
 */
fun RdfRepository.sizeFormatted(): String {
    val count = tripleCount()
    return when {
        count < 1000 -> "$count triples"
        count < 1000000 -> "${count / 1000}K triples"
        else -> "${count / 1000000}M triples"
    }
}

/**
 * Get formatted statistics.
 */
fun RdfRepository.statisticsFormatted(): String {
    val stats = getStatistics()
    return """
        Repository Statistics:
        ├─ Total Triples: ${stats.totalTriples}
        ├─ Named Graphs: ${stats.graphCount}
        ├─ Size: ${stats.sizeBytes / 1024}KB
        └─ Last Modified: ${java.time.Instant.ofEpochMilli(stats.lastModified)}
    """.trimIndent()
}

// === CONVENIENCE FUNCTIONS ===

/**
 * Create an IRI from a string.
 */
fun iri(value: String): Iri = Iri(value)

/**
 * Create a literal from a string.
 */
fun literal(value: String): Literal = Literal(value, XSD.string)

/**
 * Create a literal from an integer.
 */
fun literal(value: Int): Literal = Literal(value.toString(), XSD.integer)

/**
 * Create a literal from a double.
 */
fun literal(value: Double): Literal = Literal(value.toString(), XSD.double)

/**
 * Create a literal from a boolean.
 */
fun literal(value: Boolean): Literal = Literal(value.toString(), XSD.boolean)

/**
 * Create a resource from a string.
 */
fun resource(value: String): RdfResource = Iri(value)

/**
 * Create a triple.
 */
fun triple(subject: RdfResource, predicate: Iri, obj: RdfTerm): RdfTriple {
    return RdfTriple(subject, predicate, obj)
}

/**
 * Create a triple with string predicate.
 */
fun triple(subject: RdfResource, predicate: String, obj: RdfTerm): RdfTriple {
    return RdfTriple(subject, Iri(predicate), obj)
}




