package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfQueryException
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import org.apache.jena.rdf.model.ModelFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Cached inference views stream their results: a query reads only what it consumes, concurrent readers interleave,
 * query timeouts stop the reasoner, and named graphs are prepared only when a query touches them.
 */
class JenaInferenceStreamingTest {
    // TDB2 keeps its store files memory-mapped for the JVM's lifetime, so Windows cannot delete them after the test.
    @TempDir(cleanup = CleanupMode.NEVER)
    lateinit var tmp: Path

    private val ex = "http://example.org/"
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
    private fun cls(n: Int) = Iri(ex + "C$n")
    private fun instance(n: Int) = Iri(ex + "i$n")
    private fun graph(n: Int) = Iri(ex + "g$n")

    private val instances = 20_000

    private fun schema() = (0 until 5).map { RdfTriple(cls(it), subClassOf, cls(it + 1)) }

    private fun load(repo: JenaRepository, count: Int = instances) {
        repo.editDefaultGraph().addTriples(schema() + (0 until count).map { RdfTriple(instance(it), type, cls(0)) })
    }

    private fun repositories(): List<Pair<String, () -> JenaRepository>> = listOf(
        "memory" to { JenaRepository.MemoryRepositoryWithInference() },
        "tdb2" to { JenaRepository.Tdb2RepositoryWithInference(tmp.resolve("tdb2-${System.nanoTime()}").toString()) },
    )

    private fun warmUp(repo: JenaRepository) {
        assertTrue(repo.defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(5))))
    }

    @Test
    @Timeout(300)
    fun `an unbound query with LIMIT reads only a small part of the store`() {
        for ((variant, create) in repositories()) {
            create().use { repo ->
                load(repo)
                warmUp(repo)
                val before = repo.inferenceBaseReads()
                val rows = repo.withSelectRows(SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o } LIMIT 10")) { it.count() }
                assertEquals(10, rows)
                val reads = repo.inferenceBaseReads() - before
                assertTrue(reads < instances / 4, "$variant: LIMIT 10 must not drain the inference closure ($reads store reads)")
            }
        }
    }

    @Test
    @Timeout(300)
    fun `a query timeout stops a long inference iteration`() {
        JenaRepository.MemoryRepositoryWithInference().use { repo ->
            load(repo)
            warmUp(repo)
            val before = repo.inferenceBaseReads()
            assertFailsWith<RdfQueryException> {
                repo.withSelectRows(SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }"), emptyMap(), Duration.ofMillis(300)) { rows ->
                    val iterator = rows.iterator()
                    assertTrue(iterator.hasNext())
                    iterator.next()
                    Thread.sleep(700)
                    var n = 0
                    while (iterator.hasNext()) { iterator.next(); n++ }
                    n
                }
            }
            val reads = repo.inferenceBaseReads() - before
            assertTrue(reads < instances / 4, "a timed-out query must stop reading the store ($reads store reads)")
            // The repository stays usable after the cancelled query.
            assertTrue(repo.defaultGraph.hasTriple(RdfTriple(instance(instances - 1), type, cls(3))))
        }
    }

    @Test
    @Timeout(300)
    fun `readers make progress while another reader holds a partially consumed inference iteration`() {
        for ((variant, create) in repositories()) {
            create().use { repo ->
                load(repo, 5_000)
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val slow = executor.submit<Int> {
                        repo.withSelectRows(SparqlSelectQuery("SELECT ?s WHERE { ?s a <${cls(5).value}> }")) { rows ->
                            val iterator = rows.iterator()
                            iterator.next()
                            started.countDown()
                            assertTrue(release.await(60, TimeUnit.SECONDS))
                            var n = 1
                            while (iterator.hasNext()) { iterator.next(); n++ }
                            n
                        }
                    }
                    assertTrue(started.await(60, TimeUnit.SECONDS), variant)
                    val other = executor.submit<Int> {
                        repo.withSelectRows(SparqlSelectQuery("SELECT ?s WHERE { ?s a <${cls(3).value}> }")) { it.count() }
                    }
                    assertEquals(5_000, other.get(60, TimeUnit.SECONDS), "$variant: a second reader must not wait for the first")
                    release.countDown()
                    assertEquals(5_000, slow.get(60, TimeUnit.SECONDS), variant)
                } finally {
                    release.countDown()
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    @Timeout(120)
    fun `a reader in a new transaction can use a view left partially consumed by an earlier transaction`() {
        for ((variant, create) in repositories()) {
            create().use { repo ->
                load(repo, 3_000)
                val first = repo.withSelectRows(SparqlSelectQuery("SELECT ?s WHERE { ?s a <${cls(4).value}> } LIMIT 3")) { it.count() }
                assertEquals(3, first, variant)
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val all = executor.submit<Int> {
                        repo.withSelectRows(SparqlSelectQuery("SELECT ?s WHERE { ?s a <${cls(4).value}> }")) { it.count() }
                    }.get(60, TimeUnit.SECONDS)
                    assertEquals(3_000, all, variant)
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    @Timeout(120)
    fun `named graph views are prepared only when a query touches them`() {
        JenaRepository.MemoryRepositoryWithInference().use { repo ->
            repo.transaction {
                for (g in 0 until 10) editGraph(graph(g)).addTriples(schema() + RdfTriple(instance(g), type, cls(0)))
            }
            val rows = repo.withSelectRows(SparqlSelectQuery("SELECT ?c WHERE { GRAPH <${graph(3).value}> { <${instance(3).value}> a ?c } }")) { it.count() }
            assertEquals(6, rows)
            val prepared = repo.preparedInferenceGraphs()
            assertTrue(graph(3).value in prepared, "$prepared")
            assertTrue(prepared.all { it == graph(3).value || it == "" }, "only the queried graph may be prepared: $prepared")
        }
    }

    @Test
    @Timeout(120)
    fun `inference results match a plain Jena RDFS model`() {
        for ((variant, create) in repositories()) {
            create().use { repo ->
                val defaultData = schema() + (0 until 50).map { RdfTriple(instance(it), type, cls(it % 3)) }
                val namedData = (0 until 3).associate { g -> graph(g) to schema() + RdfTriple(instance(100 + g), type, cls(g)) }
                repo.transaction {
                    editDefaultGraph().addTriples(defaultData)
                    namedData.forEach { (name, triples) -> editGraph(name).addTriples(triples) }
                }
                fun reference(triples: List<RdfTriple>): Set<RdfTriple> {
                    val model = ModelFactory.createDefaultModel()
                    triples.forEach { model.graph.add(JenaTerms.toJenaTriple(it)) }
                    return ModelFactory.createRDFSModel(model).graph.find().toList().map(JenaTerms::fromJenaTriple).toSet()
                }
                assertEquals(reference(defaultData), repo.defaultGraph.getTriples().toSet(), variant)
                namedData.forEach { (name, triples) -> assertEquals(reference(triples), repo.getGraph(name).getTriples().toSet(), "$variant $name") }

                val expectedTypes = namedData.flatMap { (name, triples) ->
                    reference(triples).filter { it.predicate == type }.map { name.value to it.subject.toString() + " " + it.obj.toString() }
                }.toSet()
                val actualTypes = repo.withSelectRows(SparqlSelectQuery("SELECT ?g ?s ?c WHERE { GRAPH ?g { ?s a ?c } }")) { rows ->
                    rows.map { row -> (row.get("g") as Iri).value to row.get("s").toString() + " " + row.get("c").toString() }.toSet()
                }
                assertEquals(expectedTypes, actualTypes, variant)
                val count = repo.withSelectRows(SparqlSelectQuery("SELECT (COUNT(*) AS ?n) WHERE { ?s a <${cls(5).value}> }")) { rows ->
                    rows.single().get("n").toString()
                }
                assertTrue(count.contains("50"), "$variant: $count")
            }
        }
    }
}
