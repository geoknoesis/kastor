package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfFormatException
import com.geoknoesis.kastor.rdf.TripleStream
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lifecycle of the background-thread RDF4J triple stream. */
class Rdf4jTripleStreamTest {
    private val producerName = "kastor-rdf4j-stream-parser"

    /** An input that delivers [prefix] and then blocks until closed. */
    private class BlockingInput(prefix: String) : InputStream() {
        private val data = prefix.toByteArray()
        private var position = 0
        private val closed = CountDownLatch(1)
        val reading = CountDownLatch(1)
        override fun read(): Int {
            if (position < data.size) return data[position++].toInt()
            reading.countDown()
            closed.await()
            throw IOException("closed")
        }
        override fun close() = closed.countDown()
    }


    @Test
    fun `close from another thread wakes a consumer blocked waiting for the next triple`() {
        val input = BlockingInput("<http://example.org/s> <http://example.org/p> <http://example.org/o> .\n")
        val stream = Rdf4jProvider().openTripleStream(input, "N-TRIPLES")
        val consumerDone = CountDownLatch(1)
        val outcome = arrayOfNulls<Any>(1)
        val consumer = thread {
            try {
                val iterator = stream.iterator()
                iterator.next()
                outcome[0] = iterator.hasNext() // blocks: the producer waits for more input
            } catch (t: Throwable) {
                outcome[0] = t
            } finally {
                consumerDone.countDown()
            }
        }
        assertTrue(input.reading.await(10, TimeUnit.SECONDS))
        awaitParked(consumer) // the consumer is blocked waiting for the next triple
        stream.close()
        assertTrue(consumerDone.await(10, TimeUnit.SECONDS), "consumer must not hang after close()")
        consumer.join(5_000)
        assertTrue(outcome[0] is IllegalStateException, "closed stream reports closure, got ${outcome[0]}")
    }

    @Test
    fun `a producer error that is not an exception still reaches the consumer`() {
        val failing = object : InputStream() {
            override fun read(): Int = throw OutOfMemoryError("simulated")
        }
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            Rdf4jProvider().openTripleStream(failing, "N-TRIPLES").use { stream ->
                val error = runCatching { stream.toList() }.exceptionOrNull()
                assertTrue(error is OutOfMemoryError || error is RdfFormatException, "got $error")
            }
        }
    }

    /** Waits (condition-based) until [thread] is parked, e.g. blocked on a queue. */
    private fun awaitParked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (thread.state != Thread.State.WAITING && thread.state != Thread.State.TIMED_WAITING) {
            assertTrue(System.nanoTime() < deadline, "thread ${thread.name} never blocked (state ${thread.state})")
            assertTrue(thread.isAlive, "thread ${thread.name} ended")
            Thread.onSpinWait()
        }
    }

    private fun producers(): Set<Thread> = Thread.getAllStackTraces().keys.filter { it.name == producerName && it.isAlive }.toSet()

    @Test
    fun `the cleanup of an abandoned unclosed stream lets its producer thread exit`() {
        val before = producers()
        val lines = (0 until 50_000).joinToString("\n") { "<http://example.org/s$it> <http://example.org/p> \"$it\" ." }
        var cleanup: Runnable? = null
        val registrar: (Any, Runnable) -> java.lang.ref.Cleaner.Cleanable = { _, action ->
            cleanup = action
            java.lang.ref.Cleaner.Cleanable { action.run() }
        }
        fun openAndAbandon(): Thread {
            val stream: TripleStream = Rdf4jFormatSupport.openTripleStream(lines.byteInputStream(), "N-TRIPLES", null, registrar)
            stream.iterator().next() // never closed, never fully consumed
            return (producers() - before).single()
        }
        val producer = openAndAbandon()
        awaitParked(producer)

        // The cleaner's action must not reach the stream, otherwise an abandoned stream never becomes unreachable.
        val action = checkNotNull(cleanup)
        assertTrue(action.javaClass.declaredFields.none { TripleStream::class.java.isAssignableFrom(it.type) }, "cleanup action references the stream")

        action.run() // what the Cleaner does once the stream is unreachable
        producer.join(10_000)
        assertTrue(!producer.isAlive, "producer thread of an abandoned stream must terminate")
    }

    @Test
    fun `closing an exhausted or unconsumed stream stops the producer`() {
        val before = producers()
        val stream = Rdf4jProvider().openTripleStream(BlockingInput(""), "N-TRIPLES")
        val producer = (producers() - before).single()
        stream.close()
        assertThrows(IllegalStateException::class.java) { stream.iterator() }
        producer.join(10_000)
        assertTrue(!producer.isAlive, "producer must stop after close()")
    }

    @Test
    fun `base IRI helpers resolve relative IRIs`() {
        val turtle = "<s> <p> <o> ."
        val streamed = Rdf4jProvider().openTripleStreamWithBase(turtle.byteInputStream(), "TURTLE", "http://example.org/").use { it.toList() }
        assertEquals(Iri("http://example.org/s"), streamed.single().subject)
        val eager = Rdf4jProvider().parseStreamingWithBase(turtle.byteInputStream(), "TURTLE", "http://example.org/").toList()
        assertEquals(Iri("http://example.org/o"), eager.single().obj)
        assertThrows(RdfFormatException::class.java) {
            Rdf4jProvider().parseStreamingWithBase(turtle.byteInputStream(), "TURTLE", null).toList()
        }
    }

    @Test
    fun `provider openTripleStream with a base IRI streams a relative Turtle document lazily`() {
        val document = (0 until 200_000).joinToString("\n") { "<s$it> <p> \"$it\" ." }.toByteArray()
        // Read by the producer thread and checked by the test thread, hence atomic.
        val bytesRead = java.util.concurrent.atomic.AtomicLong()
        val counting = object : java.io.FilterInputStream(java.io.ByteArrayInputStream(document)) {
            override fun read(): Int = super.read().also { if (it >= 0) bytesRead.incrementAndGet() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) bytesRead.addAndGet(it.toLong()) }
        }
        val provider: com.geoknoesis.kastor.rdf.RdfProvider = Rdf4jProvider()
        val before = producers()
        provider.openTripleStream(counting, "TURTLE", "http://example.org/base/").use { stream ->
            val first = stream.iterator().next()
            assertEquals(Iri("http://example.org/base/s0"), first.subject)
            assertEquals(Iri("http://example.org/base/p"), first.predicate)
            // Condition-based: wait until the producer is parked on its full queue and the byte count stops moving.
            val producer = (producers() - before).single()
            var last = -1L
            var stable = 0
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (stable < 5) {
                assertTrue(System.nanoTime() < deadline && producer.isAlive, "producer never blocked: read ${bytesRead.get()} of ${document.size} bytes")
                awaitParked(producer)
                val now = bytesRead.get()
                stable = if (now == last) stable + 1 else 0
                last = now
                Thread.onSpinWait()
            }
            assertTrue(bytesRead.get() < document.size / 2, "must not materialise the document: read ${bytesRead.get()} of ${document.size} bytes")
        }
        val eager = provider.parseStreaming("<s> <p> <o> .".byteInputStream(), "TURTLE", "http://example.org/base/").toList()
        assertEquals(Iri("http://example.org/base/o"), eager.single().obj)
    }
}
