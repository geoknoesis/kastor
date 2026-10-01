package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlDescribeQuery
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.string
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/**
 * `DESCRIBE` on a plain repository describes resources from the default graph of the query's dataset on both
 * providers: the store's default graph, or the merge of the `FROM` graphs when the query declares a dataset. Named
 * graphs are only read by the `WHERE` clause (inside `GRAPH`), never by the description itself.
 */
class Rdf4jDescribeParityTest {
    private val providers: Map<String, () -> RdfRepository> = linkedMapOf(
        "jena" to { JenaRepository.MemoryRepository() },
        "jena-inference" to { JenaRepository.MemoryRepositoryWithInference() },
        "rdf4j" to { Rdf4jRepository.MemoryRepository() },
        "rdf4j-rdfs" to { Rdf4jRepository.MemoryRdfsRepository() },
    )
    private val s = Iri("urn:s")
    private val p = Iri("urn:p")
    private val q = Iri("urn:q")

    private fun populated(factory: () -> RdfRepository): RdfRepository = factory().also { repo ->
        val b = BlankNode("b1")
        repo.editDefaultGraph().addTriples(
            listOf(RdfTriple(s, p, string("default")), RdfTriple(s, q, b), RdfTriple(b, p, string("nested"))),
        )
        repo.editGraph(Iri("urn:g1")).addTriples(
            listOf(RdfTriple(s, p, string("named1")), RdfTriple(Iri("urn:only-g1"), p, string("only1"))),
        )
        repo.editGraph(Iri("urn:g2")).addTriple(RdfTriple(s, p, string("named2")))
    }

    /** Lexical forms of the literal objects described (an inferencing store also returns entailed triples). */
    private fun RdfRepository.described(query: String): List<String> =
        describe(SparqlDescribeQuery(query)).mapNotNull { (it.obj as? Literal)?.lexical }.distinct().sorted().toList()

    private val cases: List<Pair<String, List<String>>> = listOf(
        "DESCRIBE <urn:s>" to listOf("default", "nested"),
        "DESCRIBE ?s WHERE { ?s <urn:p> \"default\" }" to listOf("default", "nested"),
        "DESCRIBE * WHERE { ?s <urn:p> \"default\" }" to listOf("default", "nested"),
        // A resource found in a named graph is described from the default graph.
        "DESCRIBE ?s WHERE { GRAPH <urn:g2> { ?s ?p ?o } }" to listOf("default", "nested"),
        "DESCRIBE ?s WHERE { GRAPH ?g { ?s ?p \"only1\" } }" to emptyList(),
        "DESCRIBE <urn:only-g1>" to emptyList(),
        "DESCRIBE ?s WHERE { ?s <urn:p> \"named1\" }" to emptyList(),
        // The query's own dataset: the description comes from its default graph, the merge of the FROM graphs.
        "DESCRIBE <urn:s> FROM <urn:g1>" to listOf("named1"),
        "DESCRIBE <urn:s> FROM <urn:g1> FROM <urn:g2>" to listOf("named1", "named2"),
        "DESCRIBE ?s FROM <urn:g1> WHERE { ?s <urn:p> \"only1\" }" to listOf("only1"),
        "DESCRIBE <urn:s> FROM NAMED <urn:g1>" to emptyList(),
        "DESCRIBE ?s FROM NAMED <urn:g1> WHERE { GRAPH ?g { ?s ?p ?o } }" to emptyList(),
        "DESCRIBE ?s FROM <urn:g2> FROM NAMED <urn:g1> WHERE { GRAPH ?g { ?s ?p \"named1\" } }" to listOf("named2"),
    )

    @TestFactory
    fun `describe reads the default graph of the query dataset`(): List<DynamicTest> = cases.flatMap { (query, expected) ->
        providers.map { (provider, factory) ->
            DynamicTest.dynamicTest("$provider: $query") {
                populated(factory).use { repo -> assertEquals(expected, repo.described(query), query) }
            }
        }
    }

    @TestFactory
    fun `describe returns the same triples on both providers`(): List<DynamicTest> = cases.map { (query, _) ->
        DynamicTest.dynamicTest(query) {
            fun triples(factory: () -> RdfRepository): Set<String> = populated(factory).use { repo ->
                // Blank node labels are provider specific.
                repo.describe(SparqlDescribeQuery(query)).map { triple ->
                    listOf(triple.subject, triple.predicate, triple.obj).joinToString(" ") { if (it is BlankNode) "_:b" else it.toString() }
                }.toSet()
            }
            assertEquals(triples(providers.getValue("jena")), triples(providers.getValue("rdf4j")))
        }
    }
}
