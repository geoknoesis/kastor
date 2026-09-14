package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.MutableRdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlAskQuery
import org.apache.jena.rdf.model.ModelFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Language tags keep the case they were written with in the store, and lookups by a [LangString]
 * whose tag differs only in case still find / remove the stored literal.
 */
class JenaLangTagCaseTest {
    @TempDir(cleanup = CleanupMode.NEVER)
    lateinit var tmp: Path

    private val s = Iri("http://example.org/s")
    private val p = Iri("http://example.org/p")
    private val doc = "<http://example.org/s> <http://example.org/p> \"x\"@en-GB ."

    private fun repositories(): List<Pair<String, JenaRepository>> = listOf(
        "memory" to JenaRepository.MemoryRepository(),
        "memory-inference" to JenaRepository.MemoryRepositoryWithInference(),
        "tdb2" to JenaRepository.Tdb2Repository(tmp.resolve("tdb").toString()),
        "tdb2-inference" to JenaRepository.Tdb2RepositoryWithInference(tmp.resolve("tdb-inf").toString()),
    )

    private fun forEachLoaded(block: (String, JenaRepository) -> Unit) = repositories().forEach { (name, repo) ->
        repo.use {
            JenaProvider().parseDataset(it, doc.byteInputStream(), "TURTLE")
            block(name, it)
        }
    }

    @Test
    fun `parsed language tag keeps its case in the store`() = forEachLoaded { name, repo ->
        assertTrue(repo.ask(SparqlAskQuery("ASK { ?s ?p ?o FILTER(LANG(?o) = \"en-GB\") }")), name)
    }

    @Test
    fun `triple read back from the store can be found and removed`() = forEachLoaded { name, repo ->
        val graph = repo.editDefaultGraph()
        val triple = graph.find(s, p, null).single()
        assertTrue(graph.hasTriple(triple), name)
        assertTrue(graph.removeTriple(triple), name)
        assertTrue(graph.find(s, p, null).isEmpty(), name)
    }

    @Test
    fun `lookup with a differently cased tag falls back to case-insensitive matching`() = forEachLoaded { name, repo ->
        assertCaseInsensitiveLookups(name, repo.editDefaultGraph())
    }

    @Test
    fun `wrapped standalone model supports case-insensitive lookups`() {
        val model = ModelFactory.createDefaultModel()
        org.apache.jena.riot.RDFParser.fromString(doc, org.apache.jena.riot.Lang.NTRIPLES).parse(model)
        assertEquals("en-GB", model.listStatements().next().`object`.asLiteral().language)
        assertCaseInsensitiveLookups("standalone", JenaBridge.fromJenaModel(model))
    }

    @Test
    fun `batch removal also falls back to case-insensitive matching`() = forEachLoaded { name, repo ->
        val graph = repo.editDefaultGraph()
        assertTrue(graph.removeTriples(listOf(RdfTriple(s, p, LangString("x", "EN-gb")))), name)
        assertTrue(graph.find(s, p, null).isEmpty(), name)
    }

    private fun assertCaseInsensitiveLookups(name: String, graph: MutableRdfGraph) {
        val lower = RdfTriple(s, p, LangString("x", "en-gb"))
        assertTrue(graph.hasTriple(lower), name)
        assertEquals(1, graph.find(s, p, LangString("x", "en-gb")).size, name)
        assertEquals(1, graph.find(null, null, LangString("x", "EN-GB")).size, name)
        assertFalse(graph.hasTriple(RdfTriple(s, p, LangString("y", "en-gb"))), "$name: lexical form must still match")
        assertFalse(graph.hasTriple(RdfTriple(s, p, LangString("x", "en-us"))), "$name: tag must still match")
        assertTrue(graph.removeTriple(lower), name)
        assertTrue(graph.find(s, p, null).isEmpty(), name)
        assertFalse(graph.removeTriple(lower), name)
    }
}
