package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlAskQuery
import com.geoknoesis.kastor.rdf.SparqlConstructQuery
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.UpdateQuery
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.repository.Repository
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.eclipse.rdf4j.repository.RepositoryResult
import org.eclipse.rdf4j.repository.base.RepositoryConnectionWrapper
import org.eclipse.rdf4j.repository.base.RepositoryWrapper
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A query or update that reads inside and outside `GRAPH` needs the named graphs of the store. Enumerating them
 * (`getContextIDs`) is expensive on a `MemoryStore` (it walks every IRI and blank node of the store), so a repository
 * that Kastor created and controls enumerates them once and again only after a write; measured as the number of
 * `getContextIDs` calls.
 */
class Rdf4jContextEnumerationTest {
    private val ex = "http://example.org/"
    private val p = Iri(ex + "p")
    private fun t(o: String) = RdfTriple(Iri(ex + "s"), p, Literal(o))
    private fun g(n: Int) = Iri(ex + "g$n")

    /** Counts the context enumerations on any connection of the wrapped repository. */
    private class CountingRepository(delegate: Repository) : RepositoryWrapper(delegate) {
        val enumerations = AtomicLong()
        override fun getConnection(): RepositoryConnection = object : RepositoryConnectionWrapper(this, delegate.connection) {
            override fun getContextIDs(): RepositoryResult<Resource> {
                enumerations.incrementAndGet()
                return delegate.contextIDs
            }
        }
    }

    private fun counted(tracked: Boolean): Pair<Rdf4jRepository, CountingRepository> {
        val counting = CountingRepository(SailRepository(MemoryStore()).also { it.init() })
        val repo = Rdf4jRepository(counting).let { if (tracked) it.withVariant("memory") else it }
        repo.editDefaultGraph().addTriple(t("default"))
        repo.editGraph(g(1)).addTriple(t("named1"))
        repo.editGraph(g(2)).addTriple(t("named2"))
        counting.enumerations.set(0)
        return repo to counting
    }

    /** Reads inside and outside GRAPH: the literals of the named graphs. */
    private val mixed = "SELECT ?o { ?s ?p \"default\" GRAPH ?g { ?s ?p ?o } }"

    private fun com.geoknoesis.kastor.rdf.RdfRepository.named(): List<String> =
        select(SparqlSelectQuery(mixed)).mapNotNull { (it.get("o") as? Literal)?.lexical }.sorted()

    @Test
    fun `the named graphs are enumerated once until a write`() {
        val (repo, counting) = counted(tracked = true)
        repo.use {
            repeat(10) { assertEquals(listOf("named1", "named2"), repo.named()) }
            assertEquals(1, counting.enumerations.get(), "ten queries")
            assertEquals(2, repo.listGraphs().size)
            assertEquals(true, repo.ask(SparqlAskQuery("ASK { ?s ?p \"default\" GRAPH ?g { ?s ?p \"named2\" } }")))
            assertEquals(2, repo.construct(SparqlConstructQuery("CONSTRUCT { ?g ?p ?o } WHERE { ?s ?p \"default\" GRAPH ?g { ?s ?p ?o } }")).count())
            repo.readTransaction { assertEquals(listOf("named1", "named2"), repo.named()) }
            assertEquals(1, counting.enumerations.get(), "a transaction that starts on the current state reuses the list")

            // An update whose WHERE reads inside and outside GRAPH reuses the list too (it is still current).
            repo.update(UpdateQuery("INSERT { GRAPH <${g(3).value}> { ?s ?p ?o } } WHERE { ?s ?p \"default\" GRAPH ?g { ?s ?p ?o } }"))
            assertEquals(1, counting.enumerations.get(), "the update")
            assertEquals(listOf("named1", "named1", "named2", "named2"), repo.named())
            assertEquals(2, counting.enumerations.get(), "the update changed the store")
            repeat(5) { repo.named() }
            repeat(5) { repo.update(UpdateQuery("DELETE { ?s <${ex}none> ?o } WHERE { ?s ?p \"default\" GRAPH ?g { ?s ?p ?o } }")) }
            assertEquals(2 + 4, counting.enumerations.get(), "the first update reuses the list, each later one follows a write")
        }
    }

    @Test
    fun `queries that read only inside or only outside GRAPH never enumerate`() {
        val (repo, counting) = counted(tracked = true)
        repo.use {
            repo.select(SparqlSelectQuery("SELECT ?o { ?s ?p ?o }")).toList()
            repo.select(SparqlSelectQuery("SELECT ?o { GRAPH ?g { ?s ?p ?o } }")).toList()
            repo.update(UpdateQuery("INSERT { ?s <${ex}q> ?o } WHERE { ?s ?p ?o }"))
            repo.update(UpdateQuery("INSERT { ?s <${ex}q> ?o } WHERE { GRAPH ?g { ?s ?p ?o } }"))
            assertEquals(0, counting.enumerations.get())
        }
    }

    @Test
    fun `every write path that adds or removes a graph is seen by the next query`() {
        val (repo, counting) = counted(tracked = true)
        repo.use {
            assertEquals(listOf("named1", "named2"), repo.named())
            repo.editGraph(g(3)).addTriple(t("named3"))
            assertEquals(listOf("named1", "named2", "named3"), repo.named(), "graph API add")
            repo.update(UpdateQuery("INSERT DATA { GRAPH <${g(4).value}> { <${ex}s> <${ex}p> \"named4\" } }"))
            assertEquals(listOf("named1", "named2", "named3", "named4"), repo.named(), "SPARQL INSERT DATA")
            Rdf4jProvider().parseDataset(repo, "<${ex}s> <${ex}p> \"named5\" <${g(5).value}> .\n".byteInputStream(), "N-QUADS")
            assertEquals(listOf("named1", "named2", "named3", "named4", "named5"), repo.named(), "dataset load")
            repo.removeGraph(g(5))
            assertEquals(listOf("named1", "named2", "named3", "named4"), repo.named(), "removeGraph")
            repo.editGraph(g(4)).clear()
            assertEquals(listOf("named1", "named2", "named3"), repo.named(), "graph clear")
            repo.editGraph(g(3)).removeTriple(t("named3"))
            assertEquals(listOf("named1", "named2"), repo.named(), "removal of the last triple of a graph")
            repo.update(UpdateQuery("DROP GRAPH <${g(2).value}>"))
            assertEquals(listOf("named1"), repo.named(), "SPARQL DROP")
            // A later operation of one request sees the graph an earlier one created, also with a current list.
            repo.update(
                UpdateQuery(
                    "INSERT DATA { GRAPH <${g(6).value}> { <${ex}s> <${ex}p> \"named6\" } } ; " +
                        "INSERT { GRAPH <${g(7).value}> { ?s ?p ?o } } WHERE { ?s ?p \"default\" GRAPH <${g(6).value}> { ?s ?p ?o } }",
                ),
            )
            assertEquals(listOf("named1", "named6", "named6"), repo.named(), "multi-operation update")
            assertFailsWith<IllegalStateException> {
                repo.transaction {
                    editGraph(g(8)).addTriple(t("named8"))
                    assertEquals(listOf("named1", "named6", "named6", "named8"), named(), "uncommitted graph inside the transaction")
                    error("roll back")
                }
            }
            assertEquals(listOf("named1", "named6", "named6"), repo.named(), "rollback")
            repo.clear()
            repo.editDefaultGraph().addTriple(t("default"))
            assertEquals(emptyList(), repo.named(), "repository clear")
            assertEquals(emptyList(), repo.listGraphs())
            counting.enumerations.set(0)
            repo.named()
            assertEquals(0, counting.enumerations.get())
        }
    }

    @Test
    fun `inside a transaction the list is reused until a write`() {
        val (repo, counting) = counted(tracked = true)
        repo.use {
            repo.transaction {
                repeat(3) { assertEquals(listOf("named1", "named2"), named()) }
                assertEquals(1, counting.enumerations.get(), "three queries")
                editGraph(g(3)).addTriple(t("named3"))
                repeat(3) { assertEquals(listOf("named1", "named2", "named3"), named()) }
                assertEquals(2, counting.enumerations.get(), "after a graph write")
                update(UpdateQuery("INSERT DATA { GRAPH <${g(4).value}> { <${ex}s> <${ex}p> \"named4\" } }"))
                repeat(3) { assertEquals(listOf("named1", "named2", "named3", "named4"), named()) }
                assertEquals(3, counting.enumerations.get(), "after an update")
                assertEquals(4, listGraphs().size)
                assertEquals(3, counting.enumerations.get(), "listGraphs reuses the list")
            }
            repeat(3) { assertEquals(listOf("named1", "named2", "named3", "named4"), repo.named()) }
            assertEquals(4, counting.enumerations.get(), "after the commit")
        }
    }

    @Test
    fun `a graph another thread has not committed yet is not listed`() {
        val (repo, _) = counted(tracked = true)
        repo.use {
            val written = CountDownLatch(1)
            val checked = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)
            val writer = thread {
                try {
                    repo.transaction {
                        editGraph(g(3)).addTriple(t("named3"))
                        assertEquals(listOf("named1", "named2", "named3"), named(), "the writer sees its own graph")
                        written.countDown()
                        check(checked.await(30, TimeUnit.SECONDS)) { "the reader did not check in time" }
                    }
                } catch (e: Throwable) {
                    failure.set(e)
                    written.countDown()
                }
            }
            check(written.await(30, TimeUnit.SECONDS)) { "the writer did not write in time" }
            assertEquals(listOf("named1", "named2"), repo.named(), "uncommitted graph of another thread")
            assertEquals(2, repo.listGraphs().size)
            checked.countDown()
            writer.join(30_000)
            assertNull(failure.get())
            assertEquals(listOf("named1", "named2", "named3"), repo.named(), "after the commit")
        }
    }

    @Test
    fun `a wrapped store is enumerated for every query and sees writes of other code`() {
        val (repo, counting) = counted(tracked = false)
        repo.use {
            repeat(5) { assertEquals(listOf("named1", "named2"), repo.named()) }
            assertEquals(5, counting.enumerations.get())
            val vf = SimpleValueFactory.getInstance()
            repo.getRdf4jRepository().connection.use { raw ->
                raw.add(vf.createIRI(ex + "s"), vf.createIRI(p.value), vf.createLiteral("raw"), vf.createIRI(g(3).value))
            }
            assertEquals(listOf("named1", "named2", "raw"), repo.named())
            assertEquals(3, repo.listGraphs().size)
            repo.transaction {
                repeat(3) { assertEquals(listOf("named1", "named2", "raw"), named()) }
            }
            assertEquals(10, counting.enumerations.get(), "nothing is remembered for a store other code may change")
        }
    }
}
