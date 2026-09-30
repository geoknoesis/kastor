package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An inference view's worker holds a read transaction on its snapshot (for TDB2, one that pins the store version and
 * can stall compaction). Idle views are released after a configurable, short timeout, and closing the repository
 * ends every worker before `close()` returns.
 */
class JenaInferenceViewLifecycleTest {
    // TDB2 keeps its store files memory-mapped for the JVM's lifetime, so Windows cannot delete them after the test.
    @TempDir(cleanup = CleanupMode.NEVER)
    lateinit var tmp: Path

    private val ex = "http://example.org/"
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")

    private fun liveWorkers() = Thread.getAllStackTraces().keys.count { it.name == "kastor-jena-inference" && it.isAlive }

    private fun useView(repo: JenaRepository) {
        repo.editDefaultGraph().addTriples(
            listOf(RdfTriple(Iri(ex + "A"), subClassOf, Iri(ex + "B")), RdfTriple(Iri(ex + "a"), type, Iri(ex + "A"))),
        )
        val rows = repo.withSelectRows(SparqlSelectQuery("SELECT ?c WHERE { <${ex}a> a ?c }")) { it.count() }
        assertTrue(rows >= 2, "rows=$rows")
    }

    private fun awaitNoWorkers(before: Int, within: Duration) {
        val deadline = System.nanoTime() + within.toNanos()
        while (liveWorkers() > before && System.nanoTime() < deadline) Thread.sleep(20)
    }

    @Test
    fun `the default idle timeout is short`() {
        assertTrue(JenaRepository.DEFAULT_VIEW_IDLE_TIMEOUT <= Duration.ofSeconds(1), "${JenaRepository.DEFAULT_VIEW_IDLE_TIMEOUT}")
    }

    @Test
    @Timeout(60)
    fun `an idle tdb2 inference view releases its worker after the configured timeout`() {
        val before = liveWorkers()
        JenaRepository.Tdb2RepositoryWithInference(tmp.resolve("idle").toString(), Duration.ofMillis(100)).use { repo ->
            useView(repo)
            awaitNoWorkers(before, Duration.ofSeconds(5))
            assertEquals(before, liveWorkers(), "the idle view's worker must end its read transaction and exit")
            useView(repo) // a later read opens a fresh view
        }
    }

    @Test
    @Timeout(60)
    fun `closing the repository ends inference workers before close returns`() {
        val before = liveWorkers()
        for (repo in listOf(
            JenaRepository.Tdb2RepositoryWithInference(tmp.resolve("close").toString(), Duration.ofMinutes(10)),
            JenaRepository.MemoryRepositoryWithInference(Duration.ofMinutes(10)),
        )) {
            useView(repo)
            assertTrue(liveWorkers() > before, "the view has a worker while the repository is open")
            repo.close()
            assertEquals(before, liveWorkers(), "no worker outlives close()")
        }
    }

    @Test
    fun `the idle timeout must be positive`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { JenaRepository.MemoryRepositoryWithInference(Duration.ZERO) }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            JenaProvider().createRepository("memory-inference", com.geoknoesis.kastor.rdf.RdfConfig(options = mapOf("viewIdleTimeoutMillis" to "0")))
        }
        JenaProvider().createRepository("memory-inference", com.geoknoesis.kastor.rdf.RdfConfig(options = mapOf("viewIdleTimeoutMillis" to "250"))).close()
    }
}
