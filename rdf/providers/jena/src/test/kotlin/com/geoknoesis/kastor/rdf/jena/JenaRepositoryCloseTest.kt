package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfErrorCode
import com.geoknoesis.kastor.rdf.RdfRepositoryException
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `close()`: how long it waits for readers is configurable, and concurrent calls all return after the store is closed. */
class JenaRepositoryCloseTest {
    private val ex = "http://example.org/"
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
    private val a = Iri(ex + "A")
    private val b = Iri(ex + "B")

    private fun load(repo: JenaRepository) {
        repo.editDefaultGraph().addTriples(listOf(RdfTriple(a, subClassOf, b)) + (0 until 200).map { RdfTriple(Iri(ex + "i$it"), type, a) })
    }

    @Test
    @Timeout(120)
    fun `concurrent close calls all return only after the store is closed`() {
        for (repo in listOf(JenaRepository.MemoryRepository(), JenaRepository.MemoryRepositoryWithInference())) {
            load(repo)
            val firstInClose = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            val events = Collections.synchronizedList(mutableListOf<String>())
            repo.hooks.onCloseWaiting = {
                firstInClose.countDown()
                proceed.await(60, TimeUnit.SECONDS)
            }
            repo.hooks.onStoreClosed = { events.add("store closed") }
            val executor = Executors.newFixedThreadPool(3)
            try {
                val first = executor.submit { repo.close(); events.add("first returned") }
                assertTrue(firstInClose.await(30, TimeUnit.SECONDS))
                assertTrue(repo.isClosed(), "the repository refuses new work as soon as close() started")
                val others = (1..2).map { n -> executor.submit { repo.close(); events.add("close $n returned") } }
                for (other in others) {
                    assertFailsWith<TimeoutException>("a concurrent close() must not return while the store is still open") {
                        other.get(300, TimeUnit.MILLISECONDS)
                    }
                }
                proceed.countDown()
                first.get(60, TimeUnit.SECONDS)
                others.forEach { it.get(60, TimeUnit.SECONDS) }
                assertEquals("store closed", events.first(), "$events")
                assertEquals(1, events.count { it == "store closed" }, "$events")
                assertEquals(4, events.size, "$events")
            } finally {
                proceed.countDown()
                executor.shutdownNow()
            }
            repo.close() // closing a closed repository again returns at once
        }
    }

    /** A reader that holds an inference view until released, so that `close()` has something to wait for. */
    private class HoldingReader(repo: JenaRepository, cls: Iri) {
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val thread: Thread = thread {
            try {
                repo.withSelectRows(SparqlSelectQuery("SELECT ?s WHERE { ?s a <${cls.value}> }")) { rows ->
                    val iterator = rows.iterator()
                    iterator.next()
                    holding.countDown()
                    release.await(60, TimeUnit.SECONDS)
                    var n = 1
                    while (iterator.hasNext()) { iterator.next(); n++ }
                    n
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
    }

    private fun assertCloseGivesUpAfter(repo: JenaRepository, millis: Long) {
        load(repo)
        val reader = HoldingReader(repo, b)
        try {
            assertTrue(reader.holding.await(30, TimeUnit.SECONDS))
            val warnings = Collections.synchronizedList(mutableListOf<String>())
            repo.hooks.warn = { warnings.add(it) }
            repo.close()
            assertTrue(warnings.first().contains("still in use after $millis ms"), "$warnings")
        } finally {
            reader.release.countDown()
        }
        reader.thread.join(60_000)
        val failure = reader.failure.get()
        assertTrue(failure is RdfRepositoryException && failure.errorCode == RdfErrorCode.REPOSITORY_CLOSED, "$failure")
    }

    @Test
    @Timeout(120)
    fun `the time close waits for readers is a factory option`() {
        val idle = Duration.ofMinutes(10)
        assertCloseGivesUpAfter(JenaRepository.MemoryRepositoryWithInference(idle, Duration.ofMillis(40), Duration.ofMillis(500)), 40)
        assertCloseGivesUpAfter(JenaRepository.MemoryRepositoryWithInference(idle, Duration.ZERO, Duration.ZERO), 0)
    }

    @Test
    @Timeout(120)
    fun `the time close waits for readers is a provider option`() {
        val options = mapOf("viewIdleTimeoutMillis" to "600000", "closeTimeoutMillis" to "30", "closeGraceMillis" to "400")
        assertCloseGivesUpAfter(JenaProvider().createRepository("memory-inference", RdfConfig(options = options)) as JenaRepository, 30)
    }

    @Test
    fun `the close wait defaults are unchanged and invalid values are rejected`() {
        assertEquals(Duration.ofSeconds(10), JenaRepository.DEFAULT_CLOSE_TIMEOUT)
        assertEquals(Duration.ofSeconds(2), JenaRepository.DEFAULT_CLOSE_GRACE)
        JenaRepository.MemoryRepositoryWithInference().use { repo ->
            assertEquals(TimeUnit.SECONDS.toNanos(10), repo.hooks.closeWaitNanos)
            assertEquals(TimeUnit.SECONDS.toNanos(2), repo.hooks.closeGraceNanos)
        }
        (JenaProvider().createRepository("memory-inference", RdfConfig()) as JenaRepository).use { repo ->
            assertEquals(TimeUnit.SECONDS.toNanos(10), repo.hooks.closeWaitNanos)
            assertEquals(TimeUnit.SECONDS.toNanos(2), repo.hooks.closeGraceNanos)
        }
        val idle = JenaRepository.DEFAULT_VIEW_IDLE_TIMEOUT
        assertFailsWith<IllegalArgumentException> { JenaRepository.MemoryRepositoryWithInference(idle, Duration.ofMillis(-1), Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { JenaRepository.MemoryRepositoryWithInference(idle, Duration.ZERO, Duration.ofMillis(-1)) }
        for (bad in listOf("-1", "soon", "")) {
            for (option in listOf("closeTimeoutMillis", "closeGraceMillis")) {
                assertFailsWith<IllegalArgumentException>("$option=$bad") {
                    JenaProvider().createRepository("memory-inference", RdfConfig(options = mapOf(option to bad)))
                }
            }
        }
    }
}
