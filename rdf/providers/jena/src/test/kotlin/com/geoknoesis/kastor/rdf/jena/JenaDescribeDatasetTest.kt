package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfQueryException
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlDescribeQuery
import com.geoknoesis.kastor.rdf.string
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `JenaRepository.describe` describes resources from the default graph of the query's dataset (the store's default
 * graph, or the merge of the `FROM` graphs), not from every named graph of the store as Jena's own describe handler
 * does.
 */
class JenaDescribeDatasetTest {
    private val s = Iri("urn:s")
    private val p = Iri("urn:p")

    private fun populated(repo: JenaRepository): JenaRepository = repo.also {
        val b = BlankNode("b1")
        repo.editDefaultGraph().addTriples(
            listOf(RdfTriple(s, p, string("default")), RdfTriple(s, Iri("urn:q"), b), RdfTriple(b, p, string("nested"))),
        )
        repo.editGraph(Iri("urn:g1")).addTriple(RdfTriple(s, p, string("named1")))
        repo.editGraph(Iri("urn:g2")).addTriple(RdfTriple(s, p, string("named2")))
    }

    private fun JenaRepository.described(query: String): List<String> =
        describe(SparqlDescribeQuery(query)).mapNotNull { (it.obj as? Literal)?.lexical }.distinct().sorted().toList()

    @Test
    fun `describe reads the store's default graph only`() {
        populated(JenaRepository.MemoryRepository()).use { repo ->
            assertEquals(listOf("default", "nested"), repo.described("DESCRIBE <urn:s>"))
            assertEquals(3, repo.describe(SparqlDescribeQuery("DESCRIBE <urn:s>")).count())
            assertEquals(listOf("default", "nested"), repo.described("DESCRIBE ?s WHERE { ?s <urn:p> \"default\" }"))
            assertEquals(listOf("default", "nested"), repo.described("DESCRIBE * WHERE { ?s <urn:p> \"default\" }"))
            assertEquals(listOf("default", "nested"), repo.described("DESCRIBE ?s WHERE { GRAPH <urn:g1> { ?s ?p ?o } }"))
            assertEquals(emptyList(), repo.described("DESCRIBE ?s WHERE { ?s <urn:p> \"named1\" }"))
            assertEquals(emptyList(), repo.described("DESCRIBE <urn:absent>"))
            assertEquals(emptyList(), repo.described("DESCRIBE ?s"))
        }
    }

    @Test
    fun `describe with dataset clauses reads the declared default graph`() {
        populated(JenaRepository.MemoryRepository()).use { repo ->
            assertEquals(listOf("named1"), repo.described("DESCRIBE <urn:s> FROM <urn:g1>"))
            assertEquals(listOf("named1", "named2"), repo.described("DESCRIBE <urn:s> FROM <urn:g1> FROM <urn:g2>"))
            assertEquals(emptyList(), repo.described("DESCRIBE <urn:s> FROM NAMED <urn:g1>"))
            assertEquals(listOf("named2"), repo.described("DESCRIBE ?s FROM <urn:g2> FROM NAMED <urn:g1> WHERE { GRAPH ?g { ?s ?p ?o } }"))
        }
    }

    @Test
    fun `solution modifiers of the describe query are honoured`() {
        JenaRepository.MemoryRepository().use { repo ->
            repo.editDefaultGraph().addTriples((1..5).map { RdfTriple(Iri("urn:r$it"), p, string("v$it")) })
            assertEquals(listOf("v1", "v2"), repo.described("DESCRIBE ?s WHERE { ?s <urn:p> ?o } ORDER BY ?o LIMIT 2"))
            assertEquals(listOf("v5"), repo.described("DESCRIBE ?s WHERE { ?s <urn:p> ?o } ORDER BY ?o OFFSET 4"))
        }
    }

    @Test
    fun `describe on an inference repository includes entailed triples of the default graph only`() {
        populated(JenaRepository.MemoryRepositoryWithInference()).use { repo ->
            val c = Iri("urn:C")
            val d = Iri("urn:D")
            repo.editDefaultGraph().addTriples(listOf(RdfTriple(s, RDF.type, c), RdfTriple(c, RDFS.subClassOf, d)))
            repo.editGraph(Iri("urn:g1")).addTriple(RdfTriple(s, RDF.type, Iri("urn:OnlyNamed")))
            val described = repo.describe(SparqlDescribeQuery("DESCRIBE <urn:s>")).toList()
            assertTrue(RdfTriple(s, RDF.type, d) in described, described.toString())
            assertFalse(described.any { it.obj == Iri("urn:OnlyNamed") })
            assertEquals(listOf("default", "nested"), repo.described("DESCRIBE <urn:s>"))
            assertEquals(listOf("named1"), repo.described("DESCRIBE <urn:s> FROM <urn:g1>"))
            // Inside a write transaction (a private inference view) too.
            repo.transaction {
                editDefaultGraph().addTriple(RdfTriple(s, p, string("uncommitted")))
                assertEquals(
                    listOf("default", "nested", "uncommitted"),
                    describe(SparqlDescribeQuery("DESCRIBE <urn:s>")).mapNotNull { (it.obj as? Literal)?.lexical }.distinct().sorted().toList(),
                )
            }
        }
    }

    @Test
    fun `a query that is not a DESCRIBE is rejected`() {
        populated(JenaRepository.MemoryRepository()).use { repo ->
            assertFailsWith<RdfQueryException> { repo.describe(SparqlDescribeQuery("SELECT * { ?s ?p ?o }")).toList() }
            assertFailsWith<RdfQueryException> { repo.describe(SparqlDescribeQuery("DESCRIBE {")).toList() }
        }
    }
}
