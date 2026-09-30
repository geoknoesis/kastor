package com.geoknoesis.kastor.rdf.rdf4j.reasoning

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.reasoning.*
import com.geoknoesis.kastor.rdf.reasoning.providers.MemoryReasoner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

/** [ReasonerConfig] options are honoured (or clearly rejected) by the RDF4J reasoner. */
class Rdf4jReasonerConfigTest {
    private val ex = "http://example.org/"
    private fun iri(local: String) = Iri(ex + local)
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
    private val domain = Iri("http://www.w3.org/2000/01/rdf-schema#domain")

    private val schema = MemoryGraph(listOf(
        RdfTriple(iri("A"), subClassOf, iri("B")),
        RdfTriple(iri("p"), domain, iri("D")),
        RdfTriple(iri("x"), type, iri("A")),
        RdfTriple(iri("x"), iri("p"), iri("y")),
    ))

    private fun chain(classes: Int, instances: Int) = MemoryGraph(
        (0 until classes).map { RdfTriple(iri("C$it"), subClassOf, iri("C${it + 1}")) } +
            (0 until instances).map { RdfTriple(iri("i$it"), type, iri("C0")) },
    )

    @Test
    fun `rule subsets are rejected because the RDF4J inferencer cannot select rules`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            Rdf4jReasoner(ReasonerConfig(reasonerType = ReasonerType.RDFS, enabledRules = setOf(ReasoningRule.RDFS_SUBCLASS)))
        }
        assertTrue(error.message!!.contains("enabledRules"), error.message)
        // The complete RDFS rule set (also inside a larger set) is accepted.
        Rdf4jReasoner(ReasonerConfig.default())
        Rdf4jReasoner(ReasonerConfig.rdfs())
    }

    @Test
    fun `exactly the axiomatic triples are dropped by default`() {
        val inferred = Rdf4jReasoner(ReasonerConfig.rdfs()).getInferredTriples(schema).toSet()
        assertTrue(RdfTriple(iri("x"), type, iri("B")) in inferred)
        assertTrue(RdfTriple(iri("x"), type, iri("D")) in inferred)

        val includeAxioms = ReasonerConfig.rdfs().copy(parameters = mapOf("includeAxiomaticTriples" to true))
        val withAxioms = Rdf4jReasoner(includeAxioms).getInferredTriples(schema).toSet()
        val axioms = Rdf4jReasoner(includeAxioms).getInferredTriples(MemoryGraph(emptyList())).toSet()
        assertTrue(axioms.isNotEmpty())
        assertEquals(withAxioms - axioms, inferred, "only the closure of the empty store is filtered")

        // Parity with the memory reasoner: every memory entailment is produced; RDF4J's inferencer adds only the RDFS
        // typing and reflexive entailments that the memory reasoner does not implement.
        val memory = MemoryReasoner(ReasonerConfig.rdfs()).getInferredTriples(schema).toSet()
        assertTrue(inferred.containsAll(memory), "missing: ${memory - inferred}")
        val rdfsTyping = setOf(
            "http://www.w3.org/2000/01/rdf-schema#Resource", "http://www.w3.org/2000/01/rdf-schema#Class",
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#Property", "http://www.w3.org/2000/01/rdf-schema#Datatype",
            "http://www.w3.org/2000/01/rdf-schema#Literal",
        )
        val reflexive = setOf(subClassOf, Iri("http://www.w3.org/2000/01/rdf-schema#subPropertyOf"))
        val unexplained = (inferred - memory).filterNot { t ->
            (t.predicate == type && (t.obj as? Iri)?.value in rdfsTyping) ||
                (t.predicate in reflexive && (t.subject == t.obj || (t.obj as? Iri)?.value in rdfsTyping))
        }
        assertTrue(unexplained.isEmpty(), "RDF4J-only entailments beyond RDFS typing: $unexplained")
    }

    @Test
    fun `inferences about vocabulary terms are kept`() {
        val seeAlso = Iri("http://www.w3.org/2000/01/rdf-schema#seeAlso")
        val subPropertyOf = Iri("http://www.w3.org/2000/01/rdf-schema#subPropertyOf")
        val graph = MemoryGraph(listOf(
            RdfTriple(seeAlso, subPropertyOf, iri("link")),
            RdfTriple(iri("link"), subPropertyOf, iri("related")),
            RdfTriple(iri("a"), seeAlso, iri("b")),
        ))
        val inferred = Rdf4jReasoner(ReasonerConfig.rdfs()).getInferredTriples(graph).toSet()
        assertTrue(RdfTriple(seeAlso, subPropertyOf, iri("related")) in inferred, "$inferred")
        assertTrue(RdfTriple(iri("a"), iri("related"), iri("b")) in inferred, "$inferred")
    }

    @Test
    fun `materializationThreshold bounds the inferred triples`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            Rdf4jReasoner(ReasonerConfig.rdfs().copy(materializationThreshold = 10)).reason(chain(10, 10))
        }
        assertTrue(error.message!!.contains("materializationThreshold"), error.message)
    }

    @Test
    fun `timeout uses the injected clock`() {
        val now = java.util.concurrent.atomic.AtomicLong()
        val clock = { now.addAndGet(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1)) }
        val error = assertThrows(IllegalStateException::class.java) {
            Rdf4jReasoner(ReasonerConfig.rdfs().copy(timeout = Duration.ofMillis(30), materializationThreshold = Long.MAX_VALUE), clock).reason(chain(300, 300))
        }
        assertTrue(error.message!!.contains("timed out"), error.message)
    }

    @Test
    fun `an uninterruptible inferencer commit is abandoned at the deadline and abandoned work is capped`() {
        val release = java.util.concurrent.CountDownLatch(1)
        val started = java.util.concurrent.atomic.AtomicInteger()
        // Emulates the inferencer's commit(), which computes the closure and ignores interrupts until released.
        val stuckCommit: (org.eclipse.rdf4j.repository.RepositoryConnection) -> Unit = { connection ->
            started.incrementAndGet()
            while (true) {
                try {
                    if (release.await(10, java.util.concurrent.TimeUnit.SECONDS)) break
                } catch (_: InterruptedException) {
                    // uninterruptible, like SchemaCachingRDFSInferencer's commit
                }
            }
            connection.commit()
        }
        val cap = 2
        val permits = java.util.concurrent.Semaphore(cap)
        val reasoner = Rdf4jReasoner(ReasonerConfig.rdfs().copy(timeout = Duration.ofMillis(200)), System::nanoTime, stuckCommit, permits)
        fun liveWorkers() = Thread.getAllStackTraces().keys.count { it.name == "kastor-rdf4j-inference" && it.isAlive }
        val graph = chain(3, 3)
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(30)) {
                repeat(cap) {
                    val error = assertThrows(IllegalStateException::class.java) { reasoner.getInferredTriples(graph) }
                    assertTrue(error.message!!.contains("timed out"), error.message)
                }
                repeat(5) {
                    val error = assertThrows(IllegalStateException::class.java) { reasoner.getInferredTriples(graph) }
                    assertTrue(error.message!!.contains("too many RDFS inferences"), error.message)
                    assertTrue(liveWorkers() <= cap, "abandoned inferences must not pile up: ${liveWorkers()}")
                }
            }
            assertEquals(cap, started.get(), "rejected calls must not start more inferences")
        } finally {
            release.countDown()
        }
        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            while (permits.availablePermits() < cap || liveWorkers() > 0) Thread.onSpinWait()
        }
        val inferred = Rdf4jReasoner(ReasonerConfig.rdfs(), System::nanoTime, { it.commit() }, permits).getInferredTriples(graph)
        assertTrue(RdfTriple(iri("i0"), type, iri("C3")) in inferred, "permits are released")
    }

    @Test
    fun `waiting for the inferencer commit uses the injected clock`() {
        val now = java.util.concurrent.atomic.AtomicLong()
        // The injected clock is already past the deadline once the commit starts: the caller must not wait for it.
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blockingCommit: (org.eclipse.rdf4j.repository.RepositoryConnection) -> Unit = {
            started.countDown()
            release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            it.commit()
        }
        val clock = { if (started.count == 0L) now.get() + Duration.ofHours(1).toNanos() else now.get() }
        val reasoner = Rdf4jReasoner(ReasonerConfig.rdfs().copy(timeout = Duration.ofHours(1)), clock, blockingCommit, java.util.concurrent.Semaphore(1))
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                val error = assertThrows(IllegalStateException::class.java) { reasoner.getInferredTriples(chain(3, 3)) }
                assertTrue(error.message!!.contains("timed out"), error.message)
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `OWL Micro is rejected with a clear error`() {
        val provider = Rdf4jReasonerProvider()
        assertFalse(provider.isSupported(ReasonerType.OWL_MICRO))
        val error = assertThrows(IllegalArgumentException::class.java) { provider.createReasoner(ReasonerConfig(reasonerType = ReasonerType.OWL_MICRO)) }
        assertTrue(error.message!!.contains("OWL_MICRO"), error.message)
    }
}
