package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.MutableRdfGraph
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormatException
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTriple
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.lang.ref.Cleaner
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Lifecycle of Jena's background triple stream, and batched dataset loads into foreign repositories. */
class JenaTripleStreamLifecycleTest {
    /**
     * An input that remembers which threads read it: the parser thread of a stream is the background thread that
     * reads **its** input, so the tests never look at other parser threads of the JVM.
     */
    private class TracedInput(text: String) : java.io.FilterInputStream(text.byteInputStream()) {
        private val readers = java.util.concurrent.ConcurrentHashMap.newKeySet<Thread>()
        override fun read(): Int {
            readers.add(Thread.currentThread())
            return super.read()
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            readers.add(Thread.currentThread())
            return super.read(b, off, len)
        }

        fun parserThread(): Thread = readers.single { it !== Thread.currentThread() }
    }

    private val document = (0 until 200_000).joinToString("\n") { "<http://example.org/s$it> <http://example.org/p> \"$it\" ." }

    @Test
    @Timeout(60)
    fun `the cleanup of an abandoned unclosed stream stops its parser thread`() {
        var cleanup: Runnable? = null
        val registrar: (Any, Runnable) -> Cleaner.Cleanable = { _, action ->
            cleanup = action
            Cleaner.Cleanable { action.run() }
        }
        fun openAndAbandon(): Thread {
            val input = TracedInput(document)
            val stream = JenaProvider().openTripleStreamWithBase(input, "N-TRIPLES", null, registrar)
            stream.iterator().next() // never closed, never fully consumed
            return input.parserThread()
        }
        val parser = openAndAbandon()
        assertTrue(parser.isAlive, "the parser thread blocks on its full queue while nobody reads")

        // The cleaner's action must not reach the stream, otherwise an abandoned stream never becomes unreachable.
        val action = assertNotNull(cleanup)
        assertTrue(action is JenaProvider.StreamResources)
        assertTrue(action.javaClass.declaredFields.none { JenaProvider.JenaTripleStream::class.java.isAssignableFrom(it.type) })

        action.run() // what the Cleaner does once the stream is unreachable
        parser.join(10_000)
        assertTrue(!parser.isAlive, "parser thread must terminate")
    }

    @Test
    @Timeout(60)
    fun `streams are registered with the shared cleaner and close stops the parser`() {
        val input = TracedInput(document)
        val stream = JenaProvider().openTripleStream(input, "N-TRIPLES")
        stream.iterator().next()
        val parser = input.parserThread()
        assertTrue(parser.isAlive, "the parser thread blocks on its full queue while nobody reads")
        stream.close()
        parser.join(10_000)
        assertTrue(!parser.isAlive)
        assertFailsWith<IllegalStateException> { stream.iterator() }
        stream.close() // idempotent
    }

    /** Records the size of every batch added to the wrapped repository's graphs. */
    private class RecordingRepository(private val delegate: RdfRepository) : RdfRepository by delegate {
        val batches = mutableListOf<Int>()
        private fun recording(graph: MutableRdfGraph): MutableRdfGraph = object : MutableRdfGraph by graph {
            override fun addTriples(triples: Collection<RdfTriple>) {
                batches.add(triples.size)
                graph.addTriples(triples)
            }
        }
        override fun editDefaultGraph(): MutableRdfGraph = recording(delegate.editDefaultGraph())
        override fun editGraph(name: Iri): MutableRdfGraph = recording(delegate.editGraph(name))
        override fun transaction(operations: RdfRepository.() -> Unit) = delegate.transaction { operations(this@RecordingRepository) }
    }

    private fun trig(perGraph: Int, broken: Boolean = false) = buildString {
        appendLine("PREFIX ex: <http://example.org/>")
        appendLine("ex:g1 {")
        for (i in 0 until perGraph) appendLine("ex:a$i ex:p $i .")
        appendLine("}")
        appendLine("{")
        for (i in 0 until perGraph) appendLine("ex:b$i ex:p $i .")
        if (broken) appendLine("ex:broken ex:p")
        appendLine("}")
    }

    @Test
    fun `parseDataset streams a large dataset into a foreign repository in bounded batches`() {
        val recording = RecordingRepository(Rdf.memory())
        recording.use { repo ->
            JenaProvider().parseDataset(repo, trig(5_000).byteInputStream(), "TRIG")
            assertEquals(5_000, repo.getGraph(Iri("http://example.org/g1")).size())
            assertEquals(5_000, repo.defaultGraph.size())
            assertTrue(recording.batches.size >= 10, "batches: ${recording.batches}")
            assertTrue(recording.batches.all { it <= JenaProvider.DATASET_BATCH_SIZE }, "batches: ${recording.batches}")
            assertEquals(10_000, recording.batches.sum())
        }
    }

    @Test
    fun `a parse failure while streaming into a foreign repository rolls the load back`() {
        Rdf.memory().use { repo ->
            val calls = AtomicInteger()
            assertFailsWith<RdfFormatException> {
                JenaProvider().parseDataset(RecordingRepository(repo).also { calls.set(0) }, trig(3_000, broken = true).byteInputStream(), "TRIG")
            }
            assertEquals(0, repo.defaultGraph.size(), "no partial default-graph data")
            assertEquals(0, repo.getGraph(Iri("http://example.org/g1")).size(), "no partial named-graph data")
        }
    }
}
