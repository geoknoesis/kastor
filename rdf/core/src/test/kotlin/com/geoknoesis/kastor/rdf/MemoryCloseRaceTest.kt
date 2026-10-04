package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock

class MemoryCloseRaceTest {
    @TestFactory
    fun `queued operations reject a repository closed before they acquire its lock`() =
        listOf("create", "remove", "clear", "read transaction", "write transaction")
            .map { operation ->
                dynamicTest(operation) {
                    val repo = MemoryRepository(RdfConfig())
                    val name = Iri("urn:graph")
                    assertClosedWhileQueued(repo) {
                        when (operation) {
                            "create" -> repo.createGraph(name)
                            "remove" -> repo.removeGraph(name)
                            "clear" -> repo.clear()
                            "read transaction" -> repo.readTransaction { fail<Unit>("Closed transaction ran") }
                            else -> repo.transaction { fail<Unit>("Closed transaction ran") }
                        }
                    }
                    val graphs = MemoryRepository::class.java.getDeclaredField("graphs").apply { isAccessible = true }
                    assertTrue((graphs.get(repo) as Map<*, *>).isEmpty(), "A queued operation recreated a graph")
                }
            }

    @TestFactory
    fun `queued graph reads and writes reject closure`() =
        listOf(false, true).flatMap { named ->
            listOf("add", "add batch", "remove", "remove batch", "clear", "contains", "triples", "sequence", "find", "size")
                .map { operation ->
                    dynamicTest("${if (named) "named" else "default"}: $operation") {
                        val repo = MemoryRepository(RdfConfig())
                        val graph = if (named) repo.editGraph(Iri("urn:graph")) else repo.editDefaultGraph()
                        val triple = RdfTriple(Iri("urn:s"), Iri("urn:p"), string("value"))
                        graph.addTriple(triple)
                        assertClosedWhileQueued(repo) {
                            when (operation) {
                                "add" -> graph.addTriple(triple)
                                "add batch" -> graph.addTriples(listOf(triple))
                                "remove" -> graph.removeTriple(triple)
                                "remove batch" -> graph.removeTriples(listOf(triple))
                                "clear" -> graph.clear()
                                "contains" -> graph.hasTriple(triple)
                                "triples" -> graph.getTriples()
                                "sequence" -> graph.getTriplesSequence().toList()
                                "find" -> graph.find()
                                else -> graph.size()
                            }
                        }
                    }
                }
        }

    private fun assertClosedWhileQueued(repo: MemoryRepository, operation: () -> Any?) {
        // Hold the actual repository lock to force the precheck/close/acquire ordering without sleeps.
        val field = MemoryRepository::class.java.getDeclaredField("lock").apply { isAccessible = true }
        val lock = field.get(repo) as ReentrantReadWriteLock
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try { operation() } catch (error: Throwable) { failure.set(error) }
        }.apply { isDaemon = true }
        try {
            lock.writeLock().lock()
            try {
                worker.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!lock.hasQueuedThread(worker) && worker.isAlive && System.nanoTime() < deadline) {
                    Thread.yield()
                }
                assertTrue(lock.hasQueuedThread(worker), "Operation did not wait for the lock")
                repo.close()
            } finally {
                lock.writeLock().unlock()
            }
            worker.join(5_000)
            assertFalse(worker.isAlive, "Operation did not finish")
            assertInstanceOf(IllegalStateException::class.java, failure.get())
            assertEquals("Repository is closed", failure.get()?.message)
        } finally {
            worker.interrupt()
            repo.close()
        }
    }
}
