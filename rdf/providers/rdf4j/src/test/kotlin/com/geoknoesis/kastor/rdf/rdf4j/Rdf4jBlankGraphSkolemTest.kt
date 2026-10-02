package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.UpdateQuery
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Blank-node graph names (TriG `_:g { }`, an N-Quads graph label `_:g`) are skolemized when a dataset is loaded,
 * like the Jena provider does: repositories name graphs by IRI, so the graphs would otherwise be unreachable. The
 * same happens to the blank-node graphs a SPARQL `UPDATE` creates (`LOAD` of a quad document, `INSERT` into a graph
 * named by a variable), so a repository that only Kastor writes to never holds a blank-node graph. Blank-node contexts
 * that other RDF4J code wrote to a wrapped store are not graphs of the Kastor dataset: no query, update or listing
 * sees them.
 */
class Rdf4jBlankGraphSkolemTest {
    @TempDir
    lateinit var tmp: Path
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
        // Both are `urn:kastor:skolem:<load, 32 hex digits>:<percent-encoded blank node id>`.
        val scheme = Regex(Regex.escape(Rdf4jFormatSupport.SKOLEM_GRAPH_PREFIX) + "[0-9a-f]{32}:[A-Za-z0-9._%-]+")
        assertTrue(scheme.matches(jena), jena)
        assertTrue(scheme.matches(rdf4j), rdf4j)
    }

    private val scheme = Regex(Regex.escape(Rdf4jFormatSupport.SKOLEM_GRAPH_PREFIX) + "[0-9a-f]{32}:[A-Za-z0-9._%-]+")

    /** `graph=literal` for every literal the query binds to `?o`, with skolem graph names shortened to `skolem`. */
    private fun RdfRepository.literals(query: String): List<String> = select(SparqlSelectQuery(query)).map { row ->
        val graph = row.get("g")?.let { g -> (g as? Iri)?.value?.let { if (it.startsWith(Rdf4jFormatSupport.SKOLEM_GRAPH_PREFIX)) "skolem" else it } ?: "blank" }
        "$graph=${(row.get("o") as Literal).lexical}"
    }.sorted()

    private val graphOnly = "SELECT ?g ?o { GRAPH ?g { ?s ?p ?o } }"
    private val mixed = "SELECT ?g ?o { <${ex}s> <${ex}p> \"default\" GRAPH ?g { ?s ?p ?o } }"

    private fun quadFile(name: String): String {
        val file = tmp.resolve(name).toFile()
        file.writeText(
            "<${ex}s> <${ex}p> \"blank\" _:g .\n<${ex}s> <${ex}p> \"iri\" <${ex}g> .\n" +
                "<${ex}s> <${ex}p> \"default\" .\n_:g <${ex}p> \"about\" .\n",
        )
        return file.toURI().toString()
    }

    @Test
    fun `LOAD skolemizes the blank-node graphs of a quad document`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val url = quadFile("data.nq")
            repo.update(UpdateQuery("LOAD <$url>"))
            val skolem = skolemGraphs(repo).single()
            assertTrue(scheme.matches(skolem.value), skolem.value)
            assertEquals(setOf(Iri(ex + "g"), skolem), repo.listGraphs().toSet())
            assertEquals(listOf(RdfTriple(Iri(ex + "s"), Iri(ex + "p"), Literal("blank"))), repo.getGraph(skolem).getTriples())
            // The graph is a named graph for every kind of query.
            assertEquals(listOf("${ex}g=iri", "skolem=blank"), repo.literals(graphOnly))
            assertEquals(listOf("${ex}g=iri", "skolem=blank"), repo.literals(mixed))
            // The blank node keeps its identity as a term: the skolem name carries its id.
            val about = repo.defaultGraph.find(null, null, Literal("about")).single().subject as BlankNode
            assertTrue(skolem.value.endsWith(":" + about.id), "${skolem.value} names ${about.id}")
            // A second load has blank nodes of its own.
            repo.update(UpdateQuery("LOAD <$url>"))
            assertEquals(2, skolemGraphs(repo).size)
            assertEquals(listOf("${ex}g=iri", "skolem=blank", "skolem=blank"), repo.literals(mixed))
        }
    }

    @Test
    fun `LOAD INTO GRAPH and the operations after a LOAD are not affected`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val url = quadFile("into.nq")
            repo.update(UpdateQuery("LOAD <$url> INTO GRAPH <${ex}h> ; INSERT DATA { GRAPH <${ex}i> { <${ex}s> <${ex}p> \"i\" } }"))
            assertEquals(setOf(Iri(ex + "h"), Iri(ex + "i")), repo.listGraphs().toSet())
            assertEquals(4, repo.getGraph(Iri(ex + "h")).size())
        }
    }

    @Test
    fun `a blank-node graph is skolemized inside the transaction that loads it`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val url = quadFile("tx.nq")
            repo.transaction {
                update(UpdateQuery("LOAD <$url>"))
                assertEquals(listOf("${ex}g=iri", "skolem=blank"), literals(graphOnly))
                assertEquals(listOf("${ex}g=iri", "skolem=blank"), literals(mixed))
                assertEquals(2, listGraphs().size)
            }
            assertEquals(listOf("${ex}g=iri", "skolem=blank"), repo.literals(mixed))
        }
    }

    @Test
    fun `INSERT into a graph named by a blank node is skolemized`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.editDefaultGraph().addTriple(RdfTriple(BlankNode("b"), Iri(ex + "p"), Literal("x")))
            repo.editDefaultGraph().addTriple(RdfTriple(Iri(ex + "s"), Iri(ex + "p"), Literal("default")))
            repo.update(UpdateQuery("INSERT { GRAPH ?s { ?s <${ex}q> ?o } } WHERE { ?s ?p ?o FILTER(isBlank(?s)) }"))
            val skolem = repo.listGraphs().single()
            assertTrue(scheme.matches(skolem.value), skolem.value)
            assertTrue(skolem.value.endsWith(":b"), skolem.value)
            assertEquals(listOf(RdfTriple(BlankNode("b"), Iri(ex + "q"), Literal("x"))), repo.getGraph(skolem).getTriples())
            assertEquals(listOf("skolem=x"), repo.literals(graphOnly))
            assertEquals(listOf("skolem=x"), repo.literals(mixed))
        }
    }

    private val vf = SimpleValueFactory.getInstance()

    /** A store written by other RDF4J code: a default graph, an IRI context and a blank-node context. */
    private fun wrapped(iriContext: Boolean = true): SailRepository = SailRepository(MemoryStore()).apply {
        init()
        connection.use { conn ->
            val s = vf.createIRI(ex + "s")
            val p = vf.createIRI(ex + "p")
            conn.add(s, p, vf.createLiteral("default"))
            if (iriContext) conn.add(s, p, vf.createLiteral("named"), vf.createIRI(ex + "g"))
            conn.add(s, p, vf.createLiteral("foreign"), vf.createBNode("ctx"))
        }
    }

    @Test
    fun `blank-node contexts written by other code are invisible to every query and update`() {
        for (iriContext in listOf(true, false)) {
            val store = wrapped(iriContext)
            Rdf4jRepository(store).use { repo ->
                val named = if (iriContext) listOf("${ex}g=named") else emptyList()
                assertEquals(named.size, repo.listGraphs().size)
                assertEquals(named, repo.literals(graphOnly), "a query that only reads inside GRAPH")
                assertEquals(named, repo.literals(mixed), "a query that reads inside and outside GRAPH")
                assertEquals(emptyList(), repo.literals("SELECT ?o { GRAPH ?g { ?s ?p ?o FILTER(?o = \"foreign\") } }"))
                repo.update(UpdateQuery("INSERT { GRAPH <${ex}copy1> { ?s ?p ?o } } WHERE { GRAPH ?g { ?s ?p ?o } }"))
                repo.update(UpdateQuery("INSERT { GRAPH <${ex}copy2> { ?s ?p ?o } } WHERE { ?s ?p \"default\" GRAPH ?g { ?s ?p ?o } FILTER(?g != <${ex}copy1>) }"))
                val expected = if (iriContext) listOf(RdfTriple(Iri(ex + "s"), Iri(ex + "p"), Literal("named"))) else emptyList()
                assertEquals(expected, repo.getGraph(Iri(ex + "copy1")).getTriples(), "an update that only reads inside GRAPH")
                assertEquals(expected, repo.getGraph(Iri(ex + "copy2")).getTriples(), "an update that reads inside and outside GRAPH")
                // The foreign context is left as it is.
                store.connection.use { conn -> assertEquals(1, conn.size(vf.createBNode("ctx"))) }
            }
        }
    }

    @Test
    fun `an update on a wrapped store skolemizes only the blank-node graphs it creates`() {
        val store = wrapped()
        Rdf4jRepository(store).use { repo ->
            repo.update(UpdateQuery("LOAD <${quadFile("wrapped.nq")}>"))
            val skolem = skolemGraphs(repo).single()
            assertEquals(1, repo.getGraph(skolem).size())
            store.connection.use { conn ->
                assertEquals(1, conn.size(vf.createBNode("ctx")), "the context of other code is not touched")
                val blank = ArrayList<org.eclipse.rdf4j.model.Resource>()
                conn.contextIDs.use { ids -> while (ids.hasNext()) ids.next().let { if (it is org.eclipse.rdf4j.model.BNode) blank.add(it) } }
                assertEquals(listOf<org.eclipse.rdf4j.model.Resource>(vf.createBNode("ctx")), blank)
            }
        }
    }
}
