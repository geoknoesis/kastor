package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration
import kotlin.test.assertEquals

/**
 * A dataset of a repository's default graph has no named graphs, so a `GRAPH` pattern of a query against it matches
 * nothing - and the engine must not evaluate the pattern against the default graph to find that out. Each query here
 * has a `GRAPH` pattern that is a cross product of five triple patterns over a default graph of 150 triples: about
 * 76 thousand million solutions if it were evaluated, which no engine finishes. The time limit is not a performance
 * bound, it only tells "not evaluated" from "does not come back".
 *
 * Jena and RDF4J also prune a group that ends in `FILTER(false)`, which the rewrite used before; the empty table it
 * uses now yields no solution to filter on any engine, pruning or not. The results are compared with the same query
 * on a store that has only the default graph.
 */
class CrossProviderGraphRewriteTest {
    private val providers: Map<String, () -> RdfRepository> = linkedMapOf(
        "jena" to { JenaRepository.MemoryRepository() },
        "rdf4j" to { Rdf4jRepository.MemoryRepository() },
    )
    private val p = Iri("urn:p")
    private val g1 = Iri("urn:g1")
    private val triples = (1..150).map { RdfTriple(Iri("urn:s$it"), p, string("v$it")) }

    private val crossProduct = "?a1 ?b1 ?c1 . ?a2 ?b2 ?c2 . ?a3 ?b3 ?c3 . ?a4 ?b4 ?c4 . ?a5 ?b5 ?c5"

    /** The queries, and the number of solutions each has when there are no named graphs. */
    private fun queries(name: String): Map<String, Int> = linkedMapOf(
        "SELECT * { GRAPH $name { $crossProduct } }" to 0,
        "SELECT * { ?s <urn:p> ?o GRAPH $name { $crossProduct } }" to 0,
        "SELECT * { ?s <urn:p> ?o OPTIONAL { GRAPH $name { $crossProduct } } }" to 150,
        "SELECT * { ?s <urn:p> ?o MINUS { GRAPH $name { ?s ?b1 ?c1 . $crossProduct } } }" to 150,
        "SELECT * { { ?s <urn:p> ?o } UNION { GRAPH $name { $crossProduct } } }" to 150,
        "SELECT * { ?s <urn:p> ?o FILTER NOT EXISTS { GRAPH $name { $crossProduct } } }" to 150,
        "SELECT * { ?s <urn:p> ?o FILTER EXISTS { GRAPH $name { $crossProduct } } }" to 0,
        "SELECT * { ?s <urn:p> ?o OPTIONAL { GRAPH $name { { $crossProduct } UNION { $crossProduct OPTIONAL { ?x ?y ?z } } } } }" to 150,
    )

    private fun rows(result: SparqlQueryResult): List<Map<String, RdfTerm>> =
        result.map { row -> row.getVariableNames().mapNotNull { name -> row.get(name)?.let { name to it } }.toMap() }
            .sortedBy { it.toString() }

    @TestFactory
    fun `GRAPH patterns of a dataset without named graphs are not evaluated against the default graph`(): List<DynamicTest> =
        providers.flatMap { (provider, factory) ->
            listOf("<urn:g1>", "?g").map { name ->
                DynamicTest.dynamicTest("$provider: GRAPH $name") {
                    factory().use { repo ->
                        repo.editDefaultGraph().addTriples(triples)
                        // A named graph the dataset does not include.
                        repo.editGraph(g1).addTriples(triples.take(3))
                        factory().use { reference ->
                            reference.editDefaultGraph().addTriples(triples)
                            val dataset = Dataset { defaultGraph(repo) }
                            for ((query, expected) in queries(name)) {
                                val found = assertTimeoutPreemptively(
                                    Duration.ofSeconds(60),
                                    ThrowingSupplier { rows(dataset.select(SparqlSelectQuery(query))) },
                                    "the GRAPH pattern was evaluated: $query",
                                )
                                assertEquals(expected, found.size, query)
                                // A store without named graphs answers the same, and binds the same variables.
                                assertEquals(rows(reference.select(SparqlSelectQuery(query))), found, query)
                                found.forEach { assertEquals(setOf("s", "o"), it.keys, query) }
                            }
                        }
                    }
                }
            }
        }
}
