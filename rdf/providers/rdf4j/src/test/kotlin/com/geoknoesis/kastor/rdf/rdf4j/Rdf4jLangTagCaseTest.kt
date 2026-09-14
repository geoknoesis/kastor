package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Direction
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlAskQuery
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Language tags keep the case they were written with in the store (including the `lang--dir`
 * encoding), and lookups by a [LangString] whose tag differs only in case still find / remove it.
 */
class Rdf4jLangTagCaseTest {
    @TempDir(cleanup = CleanupMode.NEVER)
    lateinit var tmp: Path

    private val s = Iri("http://example.org/s")
    private val p = Iri("http://example.org/p")
    private val doc = """
        <http://example.org/s> <http://example.org/p> "x"@en-GB .
        <http://example.org/s> <http://example.org/q> "y"@ar-EG--rtl .
    """.trimIndent()

    private fun repositories(): List<Pair<String, Rdf4jRepository>> = listOf(
        "memory" to Rdf4jRepository.MemoryRepository(),
        "native" to Rdf4jRepository.NativeRepository(tmp.resolve("native").toString()),
        "memory-rdfs" to Rdf4jRepository.MemoryRdfsRepository(),
        "native-rdfs" to Rdf4jRepository.NativeRdfsRepository(tmp.resolve("native-rdfs").toString()),
    )

    private fun forEachLoaded(block: (String, Rdf4jRepository) -> Unit) = repositories().forEach { (name, repo) ->
        repo.use {
            Rdf4jProvider().parseDataset(it, doc.byteInputStream(), "TURTLE")
            block(name, it)
        }
    }

    @Test
    fun `parsed language tags keep their case in the store`() = forEachLoaded { name, repo ->
        assertTrue(repo.ask(SparqlAskQuery("ASK { ?s ?p ?o FILTER(LANG(?o) = \"en-GB\") }")), name)
        assertTrue(repo.ask(SparqlAskQuery("ASK { ?s ?p ?o FILTER(LANG(?o) = \"ar-EG--rtl\") }")), name)
    }

    @Test
    fun `triple read back from the store can be found and removed`() = forEachLoaded { name, repo ->
        val graph = repo.editDefaultGraph()
        val triple = graph.find(s, p, null).single()
        assertTrue(graph.hasTriple(triple), name)
        assertTrue(graph.removeTriple(triple), name)
        assertTrue(graph.find(s, p, null).isEmpty(), name)
        val directional = graph.find(s, Iri("http://example.org/q"), null).single()
        assertEquals(Direction.RTL, (directional.obj as LangString).direction, name)
        assertTrue(graph.removeTriple(directional), name)
    }

    @Test
    fun `lookup with a differently cased tag falls back to case-insensitive matching`() = forEachLoaded { name, repo ->
        val graph = repo.editDefaultGraph()
        val lower = RdfTriple(s, p, LangString("x", "en-gb"))
        assertTrue(graph.hasTriple(lower), name)
        assertEquals(1, graph.find(s, p, LangString("x", "EN-gb")).size, name)
        assertEquals(1, graph.find(null, null, LangString("x", "en-gb")).size, name)
        assertFalse(graph.hasTriple(RdfTriple(s, p, LangString("x", "en-us"))), name)
        assertTrue(graph.removeTriple(lower), name)
        assertTrue(graph.find(s, p, null).isEmpty(), name)
        assertFalse(graph.removeTriple(lower), name)

        val q = Iri("http://example.org/q")
        val directional = RdfTriple(s, q, LangString("y", "ar-eg", Direction.RTL))
        assertTrue(graph.hasTriple(directional), name)
        assertFalse(graph.hasTriple(RdfTriple(s, q, LangString("y", "ar-eg", Direction.LTR))), "$name: direction must match")
        assertTrue(graph.removeTriples(listOf(directional)), name)
        assertTrue(graph.find(s, q, null).isEmpty(), name)
    }

    /**
     * NativeStore resolves a literal to its stored id through a small value-id cache whose keys ignore tag
     * case, but falls back to the on-disk store (exact bytes) on a miss. Reopening the store empties the
     * cache, so the case-insensitive lookup must not depend on it.
     */
    @Test
    fun `native store lookup with a differently cased tag works with a cold value cache`() {
        for (variant in listOf("native", "native-rdfs")) {
            val location = tmp.resolve("cold-$variant").toString()
            fun open() = if (variant == "native") Rdf4jRepository.NativeRepository(location) else Rdf4jRepository.NativeRdfsRepository(location)
            open().use { Rdf4jProvider().parseDataset(it, doc.byteInputStream(), "TURTLE") }
            open().use { repo ->
                val graph = repo.editDefaultGraph()
                val lower = RdfTriple(s, p, LangString("x", "en-gb"))
                assertTrue(graph.hasTriple(lower), "$variant: hasTriple after reopen")
                assertEquals(1, graph.find(s, p, LangString("x", "EN-gb")).size, "$variant: find after reopen")
                assertFalse(graph.hasTriple(RdfTriple(s, p, LangString("x", "en-us"))), variant)
            }
            open().use { repo ->
                val graph = repo.editDefaultGraph()
                val q = Iri("http://example.org/q")
                assertTrue(graph.removeTriple(RdfTriple(s, p, LangString("x", "en-gb"))), "$variant: removeTriple after reopen")
                assertTrue(graph.find(s, p, null).isEmpty(), variant)
                assertTrue(graph.removeTriples(listOf(RdfTriple(s, q, LangString("y", "ar-eg", Direction.RTL)))), "$variant: removeTriples after reopen")
                assertTrue(graph.find(s, q, null).isEmpty(), variant)
            }
            open().use {
                // Inference variants also count RDFS entailments; the asserted statements themselves must be gone.
                assertTrue(it.defaultGraph.find(s, null, null).none { t -> t.obj is LangString }, "$variant: removals persisted")
            }
        }
    }

    @Test
    fun `native store lookup with a differently cased tag works after the value cache evicts`() {
        Rdf4jRepository.NativeRepository(tmp.resolve("evicted").toString()).use { repo ->
            val graph = repo.editDefaultGraph()
            graph.addTriple(RdfTriple(s, p, LangString("x", "en-GB")))
            // Touch far more distinct values than the 128-entry value-id cache holds.
            graph.addTriples((0 until 2_000).map { RdfTriple(Iri("http://example.org/s$it"), Iri("http://example.org/p$it"), LangString("v$it", "de-AT")) })
            (0 until 2_000).forEach { graph.hasTriple(RdfTriple(Iri("http://example.org/s$it"), Iri("http://example.org/p$it"), LangString("v$it", "de-AT"))) }
            assertTrue(graph.hasTriple(RdfTriple(s, p, LangString("x", "en-gb"))))
            assertTrue(graph.removeTriple(RdfTriple(s, p, LangString("x", "EN-GB".lowercase()))))
            assertTrue(graph.find(s, p, null).isEmpty())
        }
    }
}
