package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.UpdateQuery
import com.geoknoesis.kastor.rdf.VersionedRdfGraph
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Graph handles have a stable identity (repository instance and graph name), and the graphs of a repository that
 * Kastor created and fully controls carry a modification stamp ([VersionedRdfGraph]); graphs of wrapped or
 * inferencing repositories do not claim one.
 */
class Rdf4jGraphIdentityTest {
    @TempDir
    lateinit var tmp: Path

    private val ex = "http://example.org/"
    private val g = Iri(ex + "g")
    private val h = Iri(ex + "h")
    private fun t(o: String) = RdfTriple(Iri(ex + "s"), Iri(ex + "p"), Literal(o))

    @Test
    fun `handles of one graph are equal and handles of different graphs are not`() {
        val wrapped = Rdf4jRepository(SailRepository(MemoryStore()).also { it.init() })
        for (repo in listOf(Rdf4jRepository.MemoryRepository(), wrapped, Rdf4jRepository.MemoryRdfsRepository())) {
            repo.use {
                val first = repo.getGraph(g)
                assertEquals(first, repo.getGraph(g))
                assertEquals(first.hashCode(), repo.getGraph(g).hashCode())
                assertEquals<RdfGraph>(first, repo.editGraph(g))
                assertEquals(first, repo.createGraph(g))
                assertEquals(first, repo.getGraph(Iri(g.value)))
                assertNotEquals(first, repo.getGraph(h))
                assertNotEquals(first, repo.defaultGraph)
                assertEquals(repo.defaultGraph, repo.defaultGraph)
                assertEquals<RdfGraph>(repo.defaultGraph, repo.editDefaultGraph())
                assertEquals(repo.defaultGraph.hashCode(), repo.editDefaultGraph().hashCode())
                assertFalse(first.equals(null))
                assertFalse(first.equals("graph"))
                // Equal handles work as map keys.
                val states = hashMapOf(repo.getGraph(g) to "g", repo.defaultGraph to "default")
                assertEquals("g", states[repo.getGraph(g)])
                assertEquals("default", states[repo.defaultGraph])
                assertNull(states[repo.getGraph(h)])
            }
        }
    }

    @Test
    fun `handles of different repositories are never equal`() {
        Rdf4jRepository.MemoryRepository().use { one ->
            Rdf4jRepository.MemoryRepository().use { other ->
                assertNotEquals(one.getGraph(g), other.getGraph(g))
                assertNotEquals(one.defaultGraph, other.defaultGraph)
            }
        }
        // Two Kastor repositories over one RDF4J repository are different handles too: each has its own stamp.
        val store = SailRepository(MemoryStore()).also { it.init() }
        val first = Rdf4jRepository(store)
        val second = Rdf4jRepository(store)
        assertNotEquals(first.getGraph(g), second.getGraph(g))
        first.close()
    }

    private fun stamp(graph: RdfGraph): Long = (graph as VersionedRdfGraph).modificationStamp

    @Test
    fun `the stamp changes on every write path and never repeats`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val seen = LinkedHashSet<Long>()
            fun changed(step: String) {
                // A new handle reads the stamp of the same graph.
                val now = stamp(repo.getGraph(g))
                assertTrue(seen.add(now), "the stamp did not change (or repeated) after: $step")
                assertEquals(now, stamp(repo.getGraph(g)), "the stamp changed without a write after: $step")
            }
            changed("creation")
            val graph = repo.editGraph(g)
            graph.addTriple(t("1")); changed("addTriple")
            graph.addTriples(listOf(t("2"), t("3"))); changed("addTriples(Collection)")
            graph.addTriples(sequenceOf(t("4"))); changed("addTriples(Sequence)")
            graph.removeTriple(t("1")); changed("removeTriple")
            graph.removeTriples(listOf(t("2"))); changed("removeTriples")
            graph.clear(); changed("graph clear")
            graph.addTriple(t("5")); changed("addTriple")
            repo.update(UpdateQuery("INSERT DATA { GRAPH <${g.value}> { <${ex}s> <${ex}p> \"6\" } }")); changed("SPARQL INSERT DATA")
            repo.update(UpdateQuery("DELETE WHERE { GRAPH <${g.value}> { ?s ?p \"6\" } }")); changed("SPARQL DELETE WHERE")
            repo.update(UpdateQuery("CLEAR GRAPH <${g.value}>")); changed("SPARQL CLEAR")
            Rdf4jProvider().parseDataset(repo, "<${ex}s> <${ex}p> \"7\" <${g.value}> .\n".byteInputStream(), "N-QUADS"); changed("parseDataset")
            repo.removeGraph(g); changed("removeGraph")
            graph.addTriple(t("8")); changed("re-creation of the graph")
            repo.clear(); changed("repository clear")
            repo.transaction { editGraph(g).addTriple(t("9")) }; changed("transaction commit")
            assertFailsWith<IllegalStateException> {
                repo.transaction {
                    editGraph(g).addTriple(t("10"))
                    error("roll back")
                }
            }
            changed("transaction rollback")
            assertEquals(listOf(t("9")), repo.getGraph(g).getTriples(), "the rolled back triple is gone")
            assertFailsWith<Exception> { repo.update(UpdateQuery("INSERT DATA { GRAPH <${g.value}> { <${ex}s> <${ex}p> \"11\" } } ; LOAD <file:///kastor/does/not/exist.ttl>")) }
            changed("failed update")
            repo.editDefaultGraph().addTriple(t("12")); changed("a write to another graph (the stamp is repository wide)")
        }
    }

    @Test
    fun `the stamp does not change on reads`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.editGraph(g).addTriple(t("1"))
            val before = stamp(repo.getGraph(g))
            repo.getGraph(g).getTriples()
            repo.getGraph(g).size()
            repo.getGraph(g).hasTriple(t("1"))
            repo.listGraphs()
            repo.hasGraph(g)
            repo.select(SparqlSelectQuery("SELECT * { { ?s ?p ?o } UNION { GRAPH ?g { ?s ?p ?o } } }")).toList()
            repo.readTransaction { getGraph(g).getTriples() }
            repo.transaction { getGraph(g).getTriples() }
            assertEquals(before, stamp(repo.getGraph(g)))
            assertEquals(before, stamp(repo.defaultGraph), "one stamp for the whole repository")
        }
    }

    @Test
    fun `inside a transaction the stamp changes with each write and again when it ends`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val seen = LinkedHashSet<Long>()
            seen.add(stamp(repo.getGraph(g)))
            repo.transaction {
                val graph = editGraph(g)
                graph.addTriple(t("1"))
                assertTrue(seen.add(stamp(graph)), "after the first write")
                assertEquals(seen.last(), stamp(graph), "no write in between")
                transaction { graph.addTriple(t("2")) }
                assertTrue(seen.add(stamp(graph)), "after a write in a nested transaction")
                update(UpdateQuery("INSERT DATA { GRAPH <${g.value}> { <${ex}s> <${ex}p> \"3\" } }"))
                assertTrue(seen.add(stamp(graph)), "after an update")
            }
            assertTrue(seen.add(stamp(repo.getGraph(g))), "after the commit")
        }
    }

    @Test
    fun `factory-created plain stores carry a stamp`() {
        val provider = Rdf4jProvider()
        val repositories = listOf(
            Rdf4jRepository.MemoryRepository(),
            Rdf4jRepository.MemoryStarRepository(),
            Rdf4jRepository.MemoryShaclRepository(),
            Rdf4jRepository.NativeRepository(tmp.resolve("native").toString()),
            provider.createRepository("memory", RdfConfig(providerId = "rdf4j", variantId = "memory")),
            provider.createRepository("memory", RdfConfig(providerId = "rdf4j", variantId = "memory", options = mapOf("lenientRead" to "true"))),
        )
        for (repo in repositories) {
            repo.use {
                assertTrue(repo.defaultGraph is VersionedRdfGraph, "default graph")
                assertTrue(repo.getGraph(g) is VersionedRdfGraph, "named graph")
                assertTrue(repo.editGraph(g) is VersionedRdfGraph, "editable named graph")
                assertTrue(repo.createGraph(g) is VersionedRdfGraph, "created graph")
                assertTrue(repo.editDefaultGraph() is VersionedRdfGraph, "editable default graph")
                val before = stamp(repo.getGraph(g))
                repo.editGraph(g).addTriple(t("1"))
                assertNotEquals(before, stamp(repo.getGraph(g)))
            }
        }
    }

    @Test
    fun `wrapped and inferencing repositories expose no stamp`() {
        val repositories = listOf(
            // Other code may write to a wrapped store.
            Rdf4jRepository(SailRepository(MemoryStore()).also { it.init() }),
            Rdf4jRepository(SailRepository(MemoryStore()).also { it.init() }, inference = false, lenientRead = true),
            // Entailed statements are content the repository does not write itself.
            Rdf4jRepository.MemoryRdfsRepository(),
            Rdf4jRepository.NativeRdfsRepository(tmp.resolve("native-rdfs").toString()),
            Rdf4jProvider().createRepository("memory-rdfs", RdfConfig(providerId = "rdf4j", variantId = "memory-rdfs")),
        )
        for (repo in repositories) {
            repo.use {
                for (graph in listOf(repo.defaultGraph, repo.getGraph(g), repo.editGraph(g), repo.createGraph(g), repo.editDefaultGraph())) {
                    assertFalse(graph is VersionedRdfGraph, "$graph must not claim a modification stamp")
                }
            }
        }
    }

    @Test
    fun `reading the stamp of a closed repository fails like a content read`() {
        val repo = Rdf4jRepository.MemoryRepository()
        val graph = repo.getGraph(g)
        repo.close()
        assertFailsWith<IllegalStateException> { graph.getTriples() }
        assertFailsWith<IllegalStateException> { stamp(graph) }
    }

    @Test
    fun `content cached under a stamp read before it is never stale`() {
        // The consumer protocol of VersionedRdfGraph: read the stamp, then the content; while the stamp is unchanged
        // the cached content is current. A writer commits concurrently.
        Rdf4jRepository.MemoryRepository().use { repo ->
            val writes = 300
            val done = AtomicBoolean(false)
            val failure = AtomicReference<String?>(null)
            val reader = thread {
                val graph = repo.getGraph(g)
                var cachedStamp = -1L
                var cachedSize = -1
                while (failure.get() == null) {
                    val finished = done.get()
                    val current = stamp(graph)
                    if (current != cachedStamp) {
                        cachedSize = graph.size()
                        cachedStamp = current
                    } else if (finished) {
                        // No write can be in flight any more: the cached content must be the final content.
                        if (cachedSize != writes) failure.set("stale content: cached $cachedSize of $writes under stamp $cachedStamp")
                        break
                    }
                }
            }
            val graph = repo.editGraph(g)
            repeat(writes) { graph.addTriple(t("$it")) }
            done.set(true)
            reader.join(60_000)
            assertFalse(reader.isAlive, "reader did not finish")
            assertNull(failure.get())
        }
    }
}
