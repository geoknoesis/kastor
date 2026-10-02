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
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An inference view's worker holds a read transaction on its snapshot (for TDB2, one that pins the store version and
 * can stall compaction). Idle views are released after a configurable, short timeout, and closing the repository
 * ends every worker before `close()` returns.
 *
 * The workers looked at are the repository's own ([JenaRepository.inferenceWorkerThreads]), never every thread of the
 * JVM with the workers' name, and idle time is the repository's injected clock and timer.
 */
class JenaInferenceViewLifecycleTest {
    // TDB2 keeps its store files memory-mapped for the JVM's lifetime, so Windows cannot delete them after the test.
    @TempDir(cleanup = CleanupMode.NEVER)
    lateinit var tmp: Path

    private val ex = "http://example.org/"
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")

    private fun useView(repo: JenaRepository) {
        repo.editDefaultGraph().addTriples(
            listOf(RdfTriple(Iri(ex + "A"), subClassOf, Iri(ex + "B")), RdfTriple(Iri(ex + "a"), type, Iri(ex + "A"))),
        )
        val rows = repo.withSelectRows(SparqlSelectQuery("SELECT ?c WHERE { <${ex}a> a ?c }")) { it.count() }
        assertTrue(rows >= 2, "rows=$rows")
    }

    /** The idle checks a repository schedules; the test fires them itself. */
    private class ManualTimer {
        class Check(val delayNanos: Long, val task: Runnable) : ScheduledFuture<Any?> {
            @Volatile private var cancelled = false
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean = true.also { cancelled = true }
            override fun isCancelled(): Boolean = cancelled
            override fun isDone(): Boolean = cancelled
            override fun get(): Any? = null
            override fun get(timeout: Long, unit: TimeUnit): Any? = null
            override fun getDelay(unit: TimeUnit): Long = unit.convert(delayNanos, TimeUnit.NANOSECONDS)
            override fun compareTo(other: Delayed): Int = getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
        }

        val checks = java.util.concurrent.CopyOnWriteArrayList<Check>()
        fun schedule(delayNanos: Long, task: Runnable): ScheduledFuture<*> = Check(delayNanos, task).also { checks.add(it) }

        /** Runs the one pending idle check. */
        fun fire() {
            val check = checks.single { !it.isCancelled }
            checks.remove(check)
            check.task.run()
        }
    }

    /** Waits for [workers] to exit (the hand-over to a stopping worker is asynchronous; its exit is what is awaited). */
    private fun awaitExit(workers: List<Thread>) {
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }
        assertTrue(workers.none { it.isAlive }, "the worker must end its read transaction and exit")
    }

    @Test
    fun `the default idle timeout is short`() {
        assertTrue(JenaRepository.DEFAULT_VIEW_IDLE_TIMEOUT <= Duration.ofSeconds(1), "${JenaRepository.DEFAULT_VIEW_IDLE_TIMEOUT}")
    }

    @Test
    @Timeout(120)
    fun `an idle tdb2 inference view releases its worker after the configured timeout`() {
        val idle = Duration.ofMillis(100)
        val timer = ManualTimer()
        var now = 0L
        JenaRepository.Tdb2RepositoryWithInference(tmp.resolve("idle").toString(), idle).use { repo ->
            repo.hooks.schedule = timer::schedule
            repo.hooks.clock = { now }
            useView(repo)
            val view = assertNotNull(repo.currentInferenceView())
            val workers = repo.inferenceWorkerThreads()
            assertEquals(1, workers.size, "the view has one worker")
            assertEquals(idle.toNanos(), timer.checks.single().delayNanos, "one idle check, due after the idle timeout")

            // Not idle for long enough yet: the check re-arms itself for the time that is left and the view stays.
            now = idle.toNanos() / 2
            timer.fire()
            assertEquals(idle.toNanos() / 2, timer.checks.single().delayNanos)
            assertTrue(workers.single().isAlive)
            assertEquals(workers, repo.inferenceWorkerThreads())
            assertEquals(view, repo.currentInferenceView())

            // Idle for the whole timeout: the view is released and its worker ends.
            now = idle.toNanos()
            timer.fire()
            assertNull(repo.currentInferenceView(), "an idle view is no longer handed out")
            awaitExit(workers)
            assertTrue(repo.inferenceWorkerThreads().isEmpty(), "the repository has no worker left")

            useView(repo) // a later read opens a fresh view
            assertEquals(1, repo.inferenceWorkerThreads().size)
            assertFalse(repo.inferenceWorkerThreads().single() in workers)
        }
    }

    @Test
    @Timeout(120)
    fun `closing the repository ends inference workers before close returns`() {
        for (repo in listOf(
            JenaRepository.Tdb2RepositoryWithInference(tmp.resolve("close").toString(), Duration.ofMinutes(10)),
            JenaRepository.MemoryRepositoryWithInference(Duration.ofMinutes(10)),
        )) {
            useView(repo)
            val workers = repo.inferenceWorkerThreads()
            assertEquals(1, workers.size, "the view has a worker while the repository is open")
            assertTrue(workers.single().isAlive)
            repo.close()
            // No waiting here: close() itself must have waited for the worker.
            assertTrue(workers.none { it.isAlive }, "no worker outlives close()")
            assertTrue(repo.inferenceWorkerThreads().isEmpty())
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
