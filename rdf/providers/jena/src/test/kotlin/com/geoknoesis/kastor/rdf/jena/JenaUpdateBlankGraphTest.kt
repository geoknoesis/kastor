package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.UpdateQuery
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A SPARQL `UPDATE` never leaves a graph named by a blank node behind: such a graph (created by `LOAD` of a quad
 * document, or by `INSERT { GRAPH ?g { } }` with `?g` bound to a blank node) gets a skolem IRI when the request
 * ends, as on the RDF4J provider and as `parseDataset` does.
 */
class JenaUpdateBlankGraphTest {
    // TDB2 keeps its store files memory-mapped for the JVM's lifetime, so Windows cannot delete them after the test.
    @TempDir(cleanup = CleanupMode.NEVER)
    lateinit var tmp: Path

    private val ex = "http://example.org/"
    private val p = Iri(ex + "p")
    private fun triple(n: Int) = RdfTriple(Iri(ex + "s$n"), p, Iri(ex + "o$n"))
    private fun nt(n: Int) = "<${ex}s$n> <${p.value}> <${ex}o$n>"

    private fun repositories(): Map<String, () -> JenaRepository> = mapOf(
        "memory" to { JenaRepository.MemoryRepository() },
        "memory-inference" to { JenaRepository.MemoryRepositoryWithInference() },
        "tdb2" to { JenaRepository.Tdb2Repository(tmp.resolve("tdb2-" + System.nanoTime()).toString()) },
        "tdb2-inference" to { JenaRepository.Tdb2RepositoryWithInference(tmp.resolve("tdb2i-" + System.nanoTime()).toString()) },
    )

    private val skolemName = Regex("urn:kastor:skolem:([0-9a-f]{32}):.+")

    /** The named graphs of [repo] that hold the test's triples, as graph name to triples. */
    private fun graphs(repo: JenaRepository): Map<Iri, Set<RdfTriple>> =
        repo.listGraphs().associateWith { repo.getGraph(it).find(null, p, null).toSet() }

    /** Graph names a query sees (an inference view serves graphs named by IRIs only). */
    private fun graphNamesSeenByQueries(repo: JenaRepository): Set<String> =
        repo.withSelectRows(SparqlSelectQuery("SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s <${p.value}> ?o } }")) { rows ->
            rows.map { it.get("g").toString() }.toSet()
        }

    @Test
    @Timeout(120)
    fun `INSERT into a graph variable bound to a blank node creates a skolem graph`() {
        for ((variant, open) in repositories()) {
            open().use { repo ->
                repo.update(UpdateQuery("INSERT { GRAPH ?g { ${nt(1)} . ${nt(2)} } } WHERE { BIND(BNODE() AS ?g) }"))
                val graphs = graphs(repo)
                assertEquals(1, graphs.size, "$variant: $graphs")
                val (name, triples) = graphs.entries.single()
                assertTrue(skolemName.matches(name.value), "$variant: ${name.value}")
                assertNotNull(blankNodeIdOfSkolemGraph(name), variant)
                assertEquals(setOf(triple(1), triple(2)), triples, variant)
                assertEquals(setOf(name.toString()), graphNamesSeenByQueries(repo), variant)
                assertTrue(repo.hasGraph(name), variant)

                // Another request draws another load id, and what exists is left alone.
                repo.update(UpdateQuery("INSERT { GRAPH ?g { ${nt(3)} } } WHERE { BIND(BNODE() AS ?g) }"))
                val after = graphs(repo)
                assertEquals(2, after.size, "$variant: $after")
                assertEquals(setOf(triple(1), triple(2)), after[name], variant)
                val second = after.keys.single { it != name }
                assertEquals(setOf(triple(3)), after[second], variant)
                assertNotEquals(skolemName.matchEntire(name.value)!!.groupValues[1], skolemName.matchEntire(second.value)!!.groupValues[1], variant)
            }
        }
    }

    @Test
    @Timeout(120)
    fun `LOAD of quads with blank graph labels creates skolem graphs`() {
        val document = tmp.resolve("blank-graphs.nq")
        Files.writeString(document, "${nt(1)} _:g .\n${nt(2)} _:g .\n${nt(3)} _:h .\n${nt(4)} <${ex}named> .\n${nt(5)} .\n")
        for ((variant, open) in repositories()) {
            open().use { repo ->
                repo.update(UpdateQuery("LOAD <${document.toUri()}>"))
                val graphs = graphs(repo)
                assertEquals(
                    setOf(setOf(triple(1), triple(2)), setOf(triple(3)), setOf(triple(4))),
                    graphs.values.toSet(),
                    "$variant: $graphs",
                )
                assertEquals(setOf(triple(4)), graphs[Iri(ex + "named")], variant)
                val skolems = graphs.keys.filter { it.value != ex + "named" }
                assertEquals(2, skolems.size, variant)
                assertTrue(skolems.all { skolemName.matches(it.value) }, "$variant: $skolems")
                assertEquals(1, skolems.map { skolemName.matchEntire(it.value)!!.groupValues[1] }.toSet().size, "$variant: one load id per request")
                assertEquals(2, skolems.map { assertNotNull(blankNodeIdOfSkolemGraph(it)) }.toSet().size, variant)
                assertEquals(graphs.keys.map { it.toString() }.toSet(), graphNamesSeenByQueries(repo), variant)
                assertTrue(repo.defaultGraph.hasTriple(triple(5)), variant)
            }
        }
    }

    @Test
    @Timeout(120)
    fun `a blank graph is skolemized when its request ends, inside a transaction too, and rolled back with it`() {
        for ((variant, open) in repositories()) {
            open().use { repo ->
                runCatching {
                    repo.transaction {
                        update(UpdateQuery("INSERT { GRAPH ?g { ${nt(1)} } } WHERE { BIND(BNODE() AS ?g) }"))
                        val name = listGraphs().single()
                        assertTrue(skolemName.matches(name.value), "$variant: ${name.value}")
                        assertTrue(getGraph(name).hasTriple(triple(1)), variant)
                        error("roll back")
                    }
                }.also { assertEquals("roll back", it.exceptionOrNull()?.message, "$variant: ${it.exceptionOrNull()}") }
                assertEquals(emptyList(), repo.listGraphs(), variant)
            }
        }
    }

    @Test
    @Timeout(120)
    fun `a blank node that names a graph and is used in its triples stays a blank node in the triples`() {
        JenaRepository.MemoryRepository().use { repo ->
            repo.update(UpdateQuery("INSERT { GRAPH ?g { ?g <${p.value}> <${ex}o1> } } WHERE { BIND(BNODE() AS ?g) }"))
            val name = repo.listGraphs().single()
            val subject = repo.getGraph(name).find(null, p, null).single().subject
            assertTrue(subject is com.geoknoesis.kastor.rdf.BlankNode, "$subject")
            assertEquals((subject as com.geoknoesis.kastor.rdf.BlankNode).id, blankNodeIdOfSkolemGraph(name))
        }
    }

    @Test
    @Timeout(120)
    fun `an update that names its graphs by IRI is not rewritten`() {
        JenaRepository.MemoryRepository().use { repo ->
            repo.update(UpdateQuery("INSERT DATA { GRAPH <${ex}g> { ${nt(1)} } }"))
            repo.update(UpdateQuery("INSERT { GRAPH ?g { ${nt(2)} } } WHERE { BIND(<${ex}g> AS ?g) }"))
            assertEquals(mapOf(Iri(ex + "g") to setOf(triple(1), triple(2))), graphs(repo))
            assertNull(blankNodeIdOfSkolemGraph(Iri(ex + "g")))
        }
    }
}
