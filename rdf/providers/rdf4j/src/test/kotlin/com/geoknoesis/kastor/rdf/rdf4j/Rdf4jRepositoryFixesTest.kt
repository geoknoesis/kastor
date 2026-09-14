package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Direction
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.RdfQueryException
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlConstructQuery
import com.geoknoesis.kastor.rdf.SparqlDescribeQuery
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.UpdateQuery
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.nativerdf.NativeStore
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Rdf4jRepositoryFixesTest {
    @TempDir(cleanup = CleanupMode.NEVER)
    lateinit var tmp: Path

    private val s = Iri("http://example.org/s")
    private val p = Iri("http://example.org/p")

    @Test
    fun `repositories report the capabilities of the variant they were created as`() {
        val provider = Rdf4jProvider()
        fun assertVariant(variant: String, repo: Rdf4jRepository) = repo.use {
            val caps = it.getCapabilities()
            val expected = provider.getCapabilities(variant)
            assertEquals(expected.supportsTripleTerms, caps.supportsTripleTerms, variant)
            assertEquals(expected.supportsInference, caps.supportsInference, variant)
            assertEquals(expected.supportsShacl, caps.supportsShacl, variant)
        }
        assertVariant("native", Rdf4jRepository.NativeRepository(tmp.resolve("n").toString()))
        assertVariant("native-rdfs", Rdf4jRepository.NativeRdfsRepository(tmp.resolve("nr").toString()))
        assertVariant("native-shacl", Rdf4jRepository.NativeShaclRepository(tmp.resolve("ns").toString()))
        assertVariant("memory", Rdf4jRepository.MemoryRepository())
        assertVariant("memory-shacl", Rdf4jRepository.MemoryShaclRepository())

        assertFalse(provider.getCapabilities("native").supportsTripleTerms)
        assertTrue(provider.getCapabilities("memory").supportsTripleTerms)
        assertTrue(provider.getCapabilities("native-rdfs").supportsInference)

        // A wrapped native repository is classified by its Sail.
        val wrapped = SailRepository(NativeStore(tmp.resolve("wrapped").toFile())).also { it.init() }
        Rdf4jRepository(wrapped).use { assertFalse(it.getCapabilities().supportsTripleTerms) }
    }

    @Test
    fun `native store really cannot hold triple terms, matching its capabilities`() {
        Rdf4jRepository.NativeRepository(tmp.resolve("native-tt").toString()).use { repo ->
            assertThrows(Exception::class.java) {
                repo.editDefaultGraph().addTriple(RdfTriple(s, p, TripleTerm(RdfTriple(s, p, Iri("http://example.org/o")))))
            }
        }
    }

    @Test
    fun `CONSTRUCT and DESCRIBE evaluation failures surface as RdfQueryException`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.editDefaultGraph().addTriple(RdfTriple(s, p, Iri("http://example.org/o")))
            assertThrows(RdfQueryException::class.java) {
                repo.construct(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { SERVICE <http://127.0.0.1:1/sparql> { ?s ?p ?o } }")).toList()
            }
            assertThrows(RdfQueryException::class.java) {
                repo.describe(SparqlDescribeQuery("DESCRIBE ?s WHERE { SERVICE <http://127.0.0.1:1/sparql> { ?s ?p ?o } }")).toList()
            }
        }
    }

    @Test
    fun `directional literal syntax in SPARQL fails with an explicit error while bindings work`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val error = assertThrows(RdfQueryException::class.java) {
                repo.update(UpdateQuery("INSERT DATA { <http://example.org/s> <http://example.org/p> \"x\"@ar--rtl }"))
            }
            assertTrue(error.message!!.contains("directional"), error.message)

            val rtl = LangString("x", "ar", Direction.RTL)
            repo.editDefaultGraph().addTriple(RdfTriple(s, p, rtl))
            val rows = repo.withSelectRows(SparqlSelectQuery("SELECT ?s WHERE { ?s ?p ?o }"), mapOf("o" to rtl), Duration.ofSeconds(10)) { it.toList() }
            assertEquals(1, rows.size)

            // A plain parse error without directional literals keeps the generic message.
            val plain = assertThrows(RdfQueryException::class.java) { repo.update(UpdateQuery("INSERT DATA { broken")) }
            assertFalse(plain.message!!.contains("directional"), plain.message)
        }
    }
}
