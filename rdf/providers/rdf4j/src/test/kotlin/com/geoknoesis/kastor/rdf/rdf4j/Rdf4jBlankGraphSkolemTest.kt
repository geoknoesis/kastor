package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Blank-node graph names (TriG `_:g { }`, an N-Quads graph label `_:g`) are skolemized when a dataset is loaded,
 * like the Jena provider does: repositories name graphs by IRI, so the graphs would otherwise be unreachable.
 */
class Rdf4jBlankGraphSkolemTest {
    private val ex = "http://example.org/"
    private fun t(o: String) = RdfTriple(Iri(ex + "s"), Iri(ex + "p"), Iri(ex + o))

    private val inputs = listOf(
        "TRIG" to """
            PREFIX ex: <$ex>
            ex:g { ex:s ex:p ex:o . }
            _:g { ex:s ex:p ex:o2 . }
            _:h { ex:s ex:p ex:o3 . }
            _:g { ex:s ex:p ex:o4 . }
        """.trimIndent(),
        "N-QUADS" to """
            <${ex}s> <${ex}p> <${ex}o> <${ex}g> .
            <${ex}s> <${ex}p> <${ex}o2> _:g .
            <${ex}s> <${ex}p> <${ex}o3> _:h .
            <${ex}s> <${ex}p> <${ex}o4> _:g .
        """.trimIndent() + "\n",
    )

    private fun skolemGraphs(repo: Rdf4jRepository): List<Iri> =
        repo.listGraphs().filter { it.value.startsWith(Rdf4jFormatSupport.SKOLEM_GRAPH_PREFIX) }

    @Test
    fun `blank-node graph names are skolemized on load and reachable through the graph API`() {
        for ((format, data) in inputs) {
            val first = Rdf4jRepository.MemoryRepository().use { repo ->
                Rdf4jProvider().parseDataset(repo, data.byteInputStream(), format)
                val graphs = repo.listGraphs()
                assertEquals(3, graphs.size, "$format: $graphs")
                assertTrue(repo.getGraph(Iri(ex + "g")).hasTriple(t("o")), format)
                val skolem = skolemGraphs(repo)
                assertEquals(2, skolem.size, "$format: one graph per distinct blank label: $graphs")
                val g = skolem.single { repo.getGraph(it).hasTriple(t("o2")) }
                assertTrue(repo.getGraph(g).hasTriple(t("o4")), "$format: the same label names the same graph")
                assertEquals(2, repo.getGraph(g).size(), format)
                val h = skolem.single { it != g }
                assertEquals(listOf(t("o3")), repo.getGraph(h).getTriples(), format)
                assertTrue(repo.hasGraph(h), format)
                skolem.toSet()
            }
            val again = Rdf4jRepository.MemoryRepository().use { repo ->
                Rdf4jProvider().parseDataset(repo, data.byteInputStream(), format)
                skolemGraphs(repo).toSet()
            }
            assertTrue((first intersect again).isEmpty(), "$format: blank graph names are scoped to one load")
        }
    }

    @Test
    fun `two loads into one repository do not share a skolem graph`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val data = "<${ex}s> <${ex}p> <${ex}o> _:g .\n"
            Rdf4jProvider().parseDataset(repo, data.byteInputStream(), "N-QUADS")
            Rdf4jProvider().parseDataset(repo, data.byteInputStream(), "N-QUADS", "http://example.org/base")
            assertEquals(2, skolemGraphs(repo).size)
        }
    }

    @Test
    fun `the skolem IRI scheme is the Jena provider's`() {
        val data = "<${ex}s> <${ex}p> <${ex}o> _:g .\n"
        val jena = JenaRepository.MemoryRepository().use { repo ->
            JenaProvider().parseDataset(repo, data.byteInputStream(), "N-QUADS")
            repo.listGraphs().single().value
        }
        val rdf4j = Rdf4jRepository.MemoryRepository().use { repo ->
            Rdf4jProvider().parseDataset(repo, data.byteInputStream(), "N-QUADS")
            repo.listGraphs().single().value
        }
        assertTrue(jena.startsWith(Rdf4jFormatSupport.SKOLEM_GRAPH_PREFIX), jena)
        assertTrue(rdf4j.startsWith(Rdf4jFormatSupport.SKOLEM_GRAPH_PREFIX), rdf4j)
        // Both are `urn:kastor:skolem:` followed by an opaque, IRI-safe token.
        assertTrue(Regex("[A-Za-z0-9._~-]+").matches(rdf4j.removePrefix(Rdf4jFormatSupport.SKOLEM_GRAPH_PREFIX)), rdf4j)
    }
}
