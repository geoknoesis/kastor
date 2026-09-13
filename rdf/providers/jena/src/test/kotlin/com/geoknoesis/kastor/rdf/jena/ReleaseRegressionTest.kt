package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Path
import java.time.Duration

class ReleaseRegressionTest {
    @Test fun `closing parser after first item closes its owned input`() {
        var closed = false
        val bytes = (0 until 10_000).joinToString("\n") { "<urn:s$it> <urn:p> <urn:o> ." }.toByteArray()
        val input = object : java.io.ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        }
        Rdf.openTripleStream(input, RdfFormat.N_TRIPLES).use { assertNotNull(it.first()) }
        assertTrue(closed)
    }
    // TDB maps remain locked until JVM exit on Windows; Gradle cleans the task scratch directory afterwards.
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) lateinit var directory: Path
    private val triple = RdfTriple(Iri("urn:a"), Iri("urn:p"), Literal("old"))
    @Test fun `inference reopening preserves assertions and infers new facts`() {
        JenaRepository.Tdb2Repository(directory.toString()).use { it.editDefaultGraph().addTriple(triple) }
        JenaRepository.Tdb2RepositoryWithInference(directory.toString()).use { repo ->
            assertTrue(repo.defaultGraph.hasTriple(triple))
            repo.editDefaultGraph().addTriples(JenaProvider().parseGraph(
                "<urn:C> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <urn:D> . <urn:a> a <urn:C> .".byteInputStream(), "TURTLE").getTriples())
            assertTrue(repo.ask(SparqlAskQuery("ASK { <urn:a> a <urn:D> }")))
        }
        JenaRepository.Tdb2Repository(directory.toString()).use { assertTrue(it.defaultGraph.hasTriple(triple)) }
    }
    @Test fun `dataset import appends and default clear preserves named graphs`() {
        JenaRepository.MemoryRepository().use { repo ->
            repo.editDefaultGraph().addTriple(triple)
            repo.editGraph(Iri("urn:g")).addTriple(triple)
            JenaProvider().parseDataset(repo, "<urn:new> <urn:p> 1 . <urn:g> { <urn:new> <urn:p> 2 }".byteInputStream(), "TRIG")
            assertEquals(2, repo.defaultGraph.size())
            assertEquals(2, repo.getGraph(Iri("urn:g")).size())
            repo.editDefaultGraph().clear()
            assertEquals(2, repo.getGraph(Iri("urn:g")).size())
        }
    }
    @Test fun `transactions roll back and nested transactions join`() {
        JenaRepository.MemoryRepository().use { repo ->
            assertThrows(IllegalStateException::class.java) {
                repo.transaction { transaction { editDefaultGraph().addTriple(triple) }; error("rollback") }
            }
            assertEquals(0, repo.defaultGraph.size())
        }
    }
    @Test fun `bound queries preserve literals comments bound expressions and blank node identity`() {
        JenaRepository.MemoryRepository().use { repo ->
            val b = BlankNode("focus")
            repo.editDefaultGraph().addTriple(RdfTriple(b, Iri("urn:p"), Literal("\$this")))
            val query = SparqlSelectQuery("SELECT \$this WHERE { \$this <urn:p> ?value . FILTER(BOUND(\$this) && ?value = '\$this') # \$this\n }")
            val rows = repo.withSelectRows(query, mapOf("this" to b), Duration.ofSeconds(5)) { it.toList() }
            assertEquals(1, rows.size)
        }
    }
    @Test fun `scoped query and parse may stop early`() {
        JenaRepository.MemoryRepository().use { repo ->
            repo.editDefaultGraph().addTriple(triple)
            repo.withSelectRows(SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }")) { assertEquals(1, it.take(1).count()) }
            repo.editDefaultGraph().addTriple(triple.copy(obj = Literal("new")))
        }
        val input = (0..10000).joinToString("\n") { "<urn:s$it> <urn:p> $it ." }.byteInputStream()
        input.use { JenaProvider().openTripleStream(it, "TURTLE").use { stream -> assertEquals(1, stream.take(1).count()) } }
    }
}
