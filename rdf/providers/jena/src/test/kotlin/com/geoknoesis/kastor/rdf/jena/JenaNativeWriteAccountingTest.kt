package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.VersionedRdfGraph
import org.apache.jena.graph.GraphUtil
import org.apache.jena.graph.Node
import org.apache.jena.graph.NodeFactory
import org.apache.jena.graph.Triple
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Writes made through the Jena objects that `JenaBridge.getJenaModel` / `getJenaGraph` hand out for a repository
 * graph are writes of the repository: they move the modification stamp, are seen by inference views, and never reach
 * the store behind the repository's back.
 */
class JenaNativeWriteAccountingTest {
    // TDB2 keeps its store files memory-mapped for the JVM's lifetime, so Windows cannot delete them after the test.
    @TempDir(cleanup = CleanupMode.NEVER)
    lateinit var tmp: Path

    private val ex = "http://example.org/"
    private val p = Iri(ex + "p")
    private val g1 = Iri(ex + "g1")
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
    private fun cls(n: Int) = Iri(ex + "C$n")
    private val x = Iri(ex + "x")
    private fun triple(n: Int) = RdfTriple(Iri(ex + "s$n"), p, Iri(ex + "o$n"))
    private fun node(iri: Iri): Node = NodeFactory.createURI(iri.value)
    private fun jena(t: RdfTriple): Triple = JenaTerms.toJenaTriple(t)

    private fun repositories(): Map<String, () -> JenaRepository> = mapOf(
        "memory" to { JenaRepository.MemoryRepository() },
        "memory-inference" to { JenaRepository.MemoryRepositoryWithInference() },
        "tdb2" to { JenaRepository.Tdb2Repository(tmp.resolve("tdb2-" + System.nanoTime()).toString()) },
        "tdb2-inference" to { JenaRepository.Tdb2RepositoryWithInference(tmp.resolve("tdb2i-" + System.nanoTime()).toString()) },
    )

    private fun stamp(graph: RdfGraph): Long = (graph as VersionedRdfGraph).modificationStamp

    /** The test's own triples in [graph] (an inference repository adds the RDFS axioms to every graph). */
    private fun content(graph: RdfGraph): Set<RdfTriple> = graph.find(null, p, null).toSet()

    /**
     * What kastor-gen's validation cache does with a graph handle: it reads the stamp, then the content, and serves
     * the content it has for an equal stamp without reading the graph again.
     */
    private class StampedCache(private val graph: RdfGraph, private val read: (RdfGraph) -> Set<RdfTriple>) {
        private var cachedStamp: Long? = null
        private var cached: Set<RdfTriple> = emptySet()
        var hits = 0
            private set

        fun content(): Set<RdfTriple> {
            val stamp = (graph as VersionedRdfGraph).modificationStamp
            if (stamp == cachedStamp) {
                hits++
                return cached
            }
            cached = read(graph)
            cachedStamp = stamp
            return cached
        }
    }

    /** Every way of writing through the native objects; each one changes the content of a graph holding triples 1..3. */
    private fun nativeWrites(): Map<String, (Model) -> Unit> = mapOf(
        "Model.add(statement)" to { m -> m.add(m.asStatement(jena(triple(10)))) },
        "Model.remove(statement)" to { m -> m.remove(m.asStatement(jena(triple(1)))) },
        "Model.removeAll()" to { m -> m.removeAll() },
        "Model.removeAll(s, p, o)" to { m -> m.removeAll(m.createResource(ex + "s1"), null, null) },
        "Model.add(model)" to { m ->
            m.add(ModelFactory.createDefaultModel().also { it.graph.add(jena(triple(11))); it.graph.add(jena(triple(12))) })
        },
        "Model.remove(model)" to { m -> m.remove(ModelFactory.createDefaultModel().also { it.graph.add(jena(triple(2))) }) },
        "Resource.addProperty" to { m -> m.createResource(ex + "s13").addProperty(m.createProperty(p.value), m.createResource(ex + "o13")) },
        "Statement.remove()" to { m -> m.asStatement(jena(triple(3))).remove() },
        "Graph.add" to { m -> m.graph.add(jena(triple(14))) },
        "Graph.add(s, p, o)" to { m -> m.graph.add(node(Iri(ex + "s15")), node(p), node(Iri(ex + "o15"))) },
        "Graph.delete" to { m -> m.graph.delete(jena(triple(1))) },
        "Graph.remove(s, p, o)" to { m -> m.graph.remove(node(Iri(ex + "s2")), Node.ANY, Node.ANY) },
        "Graph.clear()" to { m -> m.graph.clear() },
        "GraphUtil.add(iterator)" to { m -> GraphUtil.add(m.graph, listOf(jena(triple(16)), jena(triple(17))).iterator()) },
        "GraphUtil.delete(iterator)" to { m -> GraphUtil.delete(m.graph, listOf(jena(triple(1)), jena(triple(2))).iterator()) },
        "Model.executeInTxn" to { m -> m.executeInTxn { m.graph.add(jena(triple(18))) } },
        "Model.calculateInTxn" to { m -> m.calculateInTxn<Unit> { m.graph.delete(jena(triple(3))) } },
    )

    @Test
    @Timeout(300)
    fun `a write through the native model changes the stamp and a stamp-keyed cache never serves the old content`() {
        for ((variant, open) in repositories()) {
            // One store per variant: every case leaves its graph empty again.
            open().use { repo ->
                for ((name, write) in nativeWrites()) {
                    for (graph in listOf(repo.defaultGraph, repo.getGraph(g1))) {
                        val what = "$variant, $name, $graph"
                        repo.transaction { (graph as com.geoknoesis.kastor.rdf.MutableRdfGraph).addTriples((1..3).map(::triple)) }
                        val cache = StampedCache(graph, ::content)
                        val before = cache.content()
                        assertEquals((1..3).map(::triple).toSet(), before, what)
                        assertSame(before, cache.content(), "$what: an unchanged graph is served from the cache")
                        assertEquals(1, cache.hits, what)
                        val stampBefore = stamp(graph)

                        write(assertNotNull(JenaBridge.getJenaModel(graph), what))

                        assertNotEquals(stampBefore, stamp(graph), "$what: the stamp must move")
                        val now = content(graph)
                        assertNotEquals(before, now, "$what: the write must change the content")
                        assertEquals(now, cache.content(), "$what: stale content served for an equal stamp")
                        repo.transaction { (graph as com.geoknoesis.kastor.rdf.MutableRdfGraph).clear() }
                    }
                }
            }
        }
    }

    @Test
    @Timeout(300)
    fun `inside a repository transaction a native write joins it and moves the transaction's stamp`() {
        for ((variant, open) in repositories()) {
            open().use { repo ->
                val graph = repo.getGraph(g1)
                val model = assertNotNull(JenaBridge.getJenaModel(graph))
                val cache = StampedCache(graph, ::content)
                assertFailsWith<IllegalStateException>(variant) {
                    repo.transaction {
                        assertEquals(emptySet(), cache.content(), variant)
                        val before = stamp(graph)
                        model.graph.add(jena(triple(1)))
                        assertNotEquals(before, stamp(graph), "$variant: the uncommitted native write must move the stamp")
                        assertEquals(setOf(triple(1)), cache.content(), "$variant: stale content inside the transaction")
                        error("roll back")
                    }
                }
                assertEquals(emptySet(), cache.content(), "$variant: the native write was rolled back with the transaction")
                repo.transaction { model.graph.add(jena(triple(2))) }
                assertEquals(setOf(triple(2)), cache.content(), variant)
            }
        }
    }

    @Test
    @Timeout(120)
    fun `an inference view sees a triple written through the native model`() {
        JenaRepository.MemoryRepositoryWithInference(Duration.ofMinutes(10)).use { repo ->
            repo.transaction {
                editDefaultGraph().addTriples(listOf(RdfTriple(x, type, cls(0)), RdfTriple(cls(0), subClassOf, cls(1))))
                editGraph(g1).addTriples(listOf(RdfTriple(x, type, cls(0)), RdfTriple(cls(0), subClassOf, cls(1))))
            }
            // Both graphs are prepared in the shared view of the current snapshot.
            assertTrue(repo.defaultGraph.hasTriple(RdfTriple(x, type, cls(1))))
            assertTrue(repo.getGraph(g1).hasTriple(RdfTriple(x, type, cls(1))))
            assertNotNull(repo.currentInferenceView())

            // Outside a transaction: the native write is its own repository write transaction.
            JenaBridge.getJenaModel(repo.defaultGraph)!!.graph.add(jena(RdfTriple(cls(1), subClassOf, cls(2))))
            assertTrue(repo.defaultGraph.hasTriple(RdfTriple(x, type, cls(2))), "default graph: entailment of the natively written triple")
            assertFalse(repo.getGraph(g1).hasTriple(RdfTriple(x, type, cls(2))))

            // Inside a transaction: recorded as a write of that graph, so the kept view does not go on serving it.
            assertTrue(repo.getGraph(g1).hasTriple(RdfTriple(x, type, cls(1))))
            repo.transaction {
                JenaBridge.getJenaGraph(getGraph(g1))!!.add(jena(RdfTriple(cls(1), subClassOf, cls(3))))
                assertTrue(getGraph(g1).hasTriple(RdfTriple(x, type, cls(3))), "the transaction's private view sees its own native write")
            }
            assertTrue(repo.getGraph(g1).hasTriple(RdfTriple(x, type, cls(3))), "named graph: entailment of the natively written triple")
            repo.transaction { JenaBridge.getJenaModel(getGraph(g1))!!.removeAll() }
            assertFalse(repo.getGraph(g1).hasTriple(RdfTriple(x, type, cls(1))), "named graph: natively cleared")
        }
    }

    @Test
    @Timeout(120)
    fun `the native objects never expose the store and never start a transaction of their own`() {
        for ((variant, open) in repositories()) {
            open().use { repo ->
                val model = assertNotNull(JenaBridge.getJenaModel(repo.defaultGraph))
                val graph = assertNotNull(JenaBridge.getJenaGraph(repo.defaultGraph))
                // A GraphView hands out its dataset and a GraphWrapper the graph it wraps.
                assertFalse(graph is org.apache.jena.sparql.core.GraphView, variant)
                assertFalse(graph is org.apache.jena.sparql.graph.GraphWrapper, variant)
                assertFalse(graph is org.apache.jena.graph.impl.WrappedGraph, variant)

                assertFailsWith<UnsupportedOperationException>(variant) { model.begin() }
                assertFailsWith<UnsupportedOperationException>(variant) { model.commit() }
                assertFailsWith<UnsupportedOperationException>(variant) { model.abort() }
                assertFalse(model.supportsTransactions(), variant)

                repo.transaction { editDefaultGraph().addTriples((1..3).map(::triple)) }
                repo.readTransaction {
                    val iterator = graph.find()
                    try {
                        iterator.next()
                        assertFailsWith<UnsupportedOperationException>(variant) { iterator.remove() }
                        // A read transaction of the repository cannot be written through the native graph either.
                        assertFailsWith<IllegalStateException>(variant) { graph.add(jena(triple(9))) }
                    } finally {
                        iterator.close()
                    }
                }
                // Closing the native objects must not close the store's graph.
                model.close()
                assertEquals(3, content(repo.defaultGraph).size, variant)
            }
        }
    }
}
