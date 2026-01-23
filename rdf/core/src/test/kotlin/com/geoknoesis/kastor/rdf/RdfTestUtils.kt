package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.dsl.GraphDsl

/**
 * Test utilities for RDF operations.
 * These utilities simplify common testing patterns and reduce boilerplate.
 */
object RdfTestUtils {
    
    /**
     * Creates a test repository for unit testing.
     * 
     * **Example:**
     * ```kotlin
     * val repo = RdfTestUtils.createTestRepository()
     * repo.add { ... }
     * ```
     * 
     * @return A new in-memory repository instance
     */
    fun createTestRepository(): RdfRepository = Rdf.memory()
    
    /**
     * Asserts that a triple exists in the graph.
     * 
     * **Example:**
     * ```kotlin
     * RdfTestUtils.assertTripleExists(
     *     graph = repo.defaultGraph,
     *     subject = personIri,
     *     predicate = FOAF.name,
     *     obj = Literal("Alice", XSD.string)
     * )
     * ```
     * 
     * @param graph The graph to check
     * @param subject The subject of the triple
     * @param predicate The predicate of the triple
     * @param obj The object of the triple
     * @throws AssertionError if the triple does not exist
     */
    fun assertTripleExists(
        graph: RdfGraph,
        subject: RdfResource,
        predicate: Iri,
        obj: RdfTerm
    ) {
        val triple = RdfTriple(subject, predicate, obj)
        require(graph.hasTriple(triple)) {
            "Expected triple not found: $subject $predicate $obj"
        }
    }
    
    /**
     * Asserts that a triple does not exist in the graph.
     * 
     * @param graph The graph to check
     * @param subject The subject of the triple
     * @param predicate The predicate of the triple
     * @param obj The object of the triple
     * @throws AssertionError if the triple exists
     */
    fun assertTripleNotExists(
        graph: RdfGraph,
        subject: RdfResource,
        predicate: Iri,
        obj: RdfTerm
    ) {
        val triple = RdfTriple(subject, predicate, obj)
        require(!graph.hasTriple(triple)) {
            "Unexpected triple found: $subject $predicate $obj"
        }
    }
    
    /**
     * Creates a test graph with sample data.
     * 
     * **Example:**
     * ```kotlin
     * val graph = RdfTestUtils.createTestGraph {
     *     val person = Iri("http://example.org/person")
     *     person - FOAF.name - "Alice"
     *     person - FOAF.age - 30
     * }
     * ```
     * 
     * @param configure DSL configuration block
     * @return A new graph with the configured triples
     */
    fun createTestGraph(configure: GraphDsl.() -> Unit): MutableRdfGraph {
        return Rdf.graph(configure)
    }
}

