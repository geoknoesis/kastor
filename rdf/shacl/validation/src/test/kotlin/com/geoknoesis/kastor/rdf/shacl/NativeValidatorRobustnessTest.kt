package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.shacl.native.ValidationBudget
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Robustness of the native validator that does not depend on the machine: the run-wide pattern cap, clocks with a
 * negative origin, early exit of conformance checks, shared validators, the stack of the validating thread.
 * Nothing here relies on wall-clock budgets or on the timing of threads: clocks are injected, threads are ordered
 * by latches, and every wait has a generous timeout that only a hang can reach.
 */
class NativeValidatorRobustnessTest {

    private val prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <http://example.org/> .\n"
    private fun graph(turtle: String): RdfGraph = Rdf.parse(prefixes + turtle, RdfFormat.TURTLE)
    private val frozenClock: () -> Long = { 0L }
    private val waitSeconds = 120L

    private val catastrophic = "^(.*a){30}$"
    private val hostile = "a".repeat(60) + "!"
    private val patternShapes =
        graph("""ex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:property [ sh:path ex:p ; sh:pattern "$catastrophic" ] .""")
    private val linearShapes =
        graph("""ex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:property [ sh:path ex:p ; sh:pattern "^[ab]+$" ] .""")

    // --- run-wide pattern cap ----------------------------------------------------------------------------------------

    @Test
    fun `many distinct hostile values cost a bounded number of pattern steps per run`() {
        // 1 ms of patternTimeout is 50,000 engine steps per value; the clock is frozen, so only steps decide.
        val validator = NativeShaclValidator(ValidationConfig(patternTimeout = Duration.ofMillis(1)))
        validator.patternClock = frozenClock
        val values = (0 until 40).joinToString(" , ") { "\"$hostile$it\"" }
        val report = validator.validate(graph("""ex:a a ex:T ; ex:p $values , "b" ."""), patternShapes)

        val undecided = report.violations.filter { it.isPatternTimeout }
        assertEquals(40, undecided.size, "every hostile value is reported undecided")
        // The ordinary value is still decided (and does not match): the cap shrinks the budget, it does not give up.
        assertEquals(1, report.violations.count { !it.isUndecided && it.constraint.constraintType == ConstraintType.PATTERN })
        // Without the cap, 40 values use 40 x ~50,000 steps (2,000,000); with it 16 do and 24 use ~1,000 each.
        assertTrue(validator.patternSteps < 1_000_000L, "pattern steps: ${validator.patternSteps}")
    }

    @Test
    fun `a run without hostile values never reaches the pattern cap`() {
        val validator = NativeShaclValidator(ValidationConfig(patternTimeout = Duration.ofMillis(1)))
        validator.patternClock = frozenClock
        // Each value reads about 20,000 characters: far more than the degraded budget, within the full one.
        val values = (0 until 40).joinToString(" , ") { "\"" + "ab".repeat(10_000) + "a".repeat(it + 1) + "\"" }
        assertTrue(validator.validate(graph("""ex:a a ex:T ; ex:p $values ."""), linearShapes).isValid)
    }

    /** 20 distinct hostile values on ex:p of focus node [focus]: more than enough to reach the run-wide cap. */
    private fun hostileFocus(focus: String) =
        "$focus a ex:T ; ex:p " + (0 until 20).joinToString(" , ") { "\"$hostile$it\"" } + " .\n"

    @Test
    fun `a value decided before the cap stays decided when it is asked again after it`() {
        // ^(.*a){3}$ reads ~36,000 characters of this 41-character value: within the full budget (50,000 steps at
        // 1 ms), far beyond the reduced one. ex:a asks before the hostile values of ex:b reach the cap, ex:c after.
        val expensive = "a".repeat(40) + "b"
        val shapes = graph(
            """
            ex:S a sh:NodeShape ; sh:targetClass ex:T ;
              sh:property [ sh:path ex:p ; sh:pattern "$catastrophic" ] ;
              sh:property [ sh:path ex:q ; sh:pattern "^(.*a){3}${'$'}" ] .
            """,
        )
        val data = graph("ex:a a ex:T ; ex:q \"$expensive\" .\n" + hostileFocus("ex:b") + "ex:c a ex:T ; ex:q \"$expensive\" .")
        val validator = NativeShaclValidator(ValidationConfig(patternTimeout = Duration.ofMillis(1)))
        validator.patternClock = frozenClock
        val report = validator.validate(data, shapes)

        val onQ = report.violations.filter { it.path == listOf(Iri("http://example.org/q")) }
        assertEquals(2, onQ.size, onQ.toString())
        assertTrue(onQ.none { it.isUndecided }, "the same value has the same answer for every focus node: $onQ")
        assertEquals(20, report.violations.count { it.isPatternTimeout })
    }

    @Test
    fun `a long ordinary value is still decided after many hostile values`() {
        // 10 ms of patternTimeout is 500,000 steps; the reduced budget is a thousandth of that, far less than this
        // 100,000-character value needs (one read per character). The reduced budget must grow with the value.
        val base64Like = "QUJD".repeat(25_000)
        val shapes = graph(
            """
            ex:S a sh:NodeShape ; sh:targetClass ex:T ;
              sh:property [ sh:path ex:p ; sh:pattern "$catastrophic" ] ;
              sh:property [ sh:path ex:q ; sh:pattern "^[A-Za-z0-9+/]*={0,2}${'$'}" ] .
            """,
        )
        val data = graph(hostileFocus("ex:a") + "ex:z a ex:T ; ex:q \"$base64Like\" , \"$base64Like!\" .")
        val validator = NativeShaclValidator(ValidationConfig(patternTimeout = Duration.ofMillis(10)))
        validator.patternClock = frozenClock
        val report = validator.validate(data, shapes)

        val onQ = report.violations.filter { it.path == listOf(Iri("http://example.org/q")) }
        assertTrue(onQ.none { it.isUndecided }, onQ.joinToString { it.message })
        assertEquals(1, onQ.size, "only the value ending in '!' violates the pattern")
        assertEquals(20, report.violations.count { it.isPatternTimeout })
    }

    // --- clocks with a negative origin -------------------------------------------------------------------------------

    @Test
    fun `the pattern wall-clock backstop does not fire early on a clock with a negative origin`() {
        // A patternTimeout this long saturates the backstop; System.nanoTime may be negative, so a clock reading -5
        // must be an instant like any other. Comparing instants (now - deadline) overflowed here and ended every
        // evaluation at its first clock check.
        val validator = NativeShaclValidator(ValidationConfig(patternTimeout = Duration.ofDays(30_000)))
        validator.patternClock = { -5L }
        val value = "ab".repeat(5_000)
        val report = validator.validate(graph("""ex:a a ex:T ; ex:p "$value" ."""), linearShapes)
        assertTrue(report.isValid, report.violations.toString())
    }

    // --- early exit of conformance checks ----------------------------------------------------------------------------

    @Test
    fun `a conformance check stops at the first failing value`() {
        val shapes = graph(
            """
            ex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:property [ sh:path ex:p ; sh:node ex:Code ] .
            ex:Code sh:property [ sh:path ex:q ; sh:pattern "^a$" ] .
            """,
        )
        val data = graph("""ex:a a ex:T ; ex:p ex:n . ex:n ex:q "b" , "c" , "d" , "e" .""")
        val validator = NativeShaclValidator(ValidationConfig())
        val report = validator.validate(data, shapes)
        assertFalse(report.isValid)
        assertEquals(1, report.violations.size, report.violations.toString())
        assertEquals(1L, validator.patternEvaluations, "the first failing value decides sh:node; the others are not matched")
    }

    @Test
    fun `a SPARQL constraint asked for its answer only reads its first solution`() {
        val pulled = java.util.concurrent.atomic.AtomicInteger()
        val factory: () -> com.geoknoesis.kastor.rdf.RdfRepository = {
            val real = com.geoknoesis.kastor.rdf.jena.JenaProvider().createRepository("memory", com.geoknoesis.kastor.rdf.RdfConfig())
            object : com.geoknoesis.kastor.rdf.RdfRepository by real {
                override fun <T> withSelectRows(
                    query: com.geoknoesis.kastor.rdf.SparqlSelect,
                    bindings: Map<String, com.geoknoesis.kastor.rdf.RdfTerm>,
                    timeout: Duration,
                    consume: (Sequence<com.geoknoesis.kastor.rdf.BindingSet>) -> T,
                ): T = real.withSelectRows(query, bindings, timeout) { rows -> consume(rows.onEach { pulled.incrementAndGet() }) }
            }
        }
        val d = '$'
        // sh:not only needs to know whether the inner shape conforms: one solution answers that.
        val shapes = graph(
            """
            ex:S a sh:NodeShape ; sh:targetClass ex:T ;
              sh:not [ sh:sparql [ sh:select '''SELECT ${d}this ?value WHERE { ${d}this <http://example.org/p> ?value }''' ] ] .
            """,
        )
        val data = graph("ex:a a ex:T ; ex:p " + (1..50).joinToString(" , ") + " .")
        val report = NativeShaclValidator(ValidationConfig(), factory).validate(data, shapes)
        assertTrue(report.isValid, report.violations.toString())
        assertEquals(1, pulled.get(), "solutions read")
    }

    @Test
    fun `a failure solution after an ordinary one fails a nested SPARQL check`() {
        val d = '$'
        // The first solution alone would answer sh:not; the third reports ?failure, which must still fail the run.
        val shapes = graph(
            """
            ex:S a sh:NodeShape ; sh:targetClass ex:T ;
              sh:not [ sh:sparql [ sh:select '''
                SELECT ${d}this ?value ?failure WHERE { ${d}this <http://example.org/p> ?value . BIND (?value = 3 AS ?failure) }
                ORDER BY ?value''' ] ] .
            """,
        )
        val data = graph("ex:a a ex:T ; ex:p 1 , 2 , 3 .")
        val error = org.junit.jupiter.api.assertThrows<ShaclValidationException> {
            NativeShaclValidator(ValidationConfig()).validate(data, shapes)
        }
        assertTrue(error.message.orEmpty().contains("?failure"), error.message)
    }

    // --- shared validator --------------------------------------------------------------------------------------------

    @Test
    fun `runs on a shared validator lose no counts`() {
        val shapes = graph("""ex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:property [ sh:path ex:p ; sh:pattern "^a$" ] .""")
        val data = graph("""ex:a a ex:T ; ex:p "b" . ex:b a ex:T ; ex:p "a" .""")
        val single = NativeShaclValidator(ValidationConfig()).also { it.validate(data, shapes) }
        val perRun = single.shapeEvaluations
        assertTrue(perRun > 0)

        val threads = 4
        val runsPerThread = 25
        val shared = NativeShaclValidator(ValidationConfig())
        val start = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = (0 until threads).map {
                pool.submit<List<Boolean>> {
                    start.await(waitSeconds, TimeUnit.SECONDS)
                    (0 until runsPerThread).map { shared.validate(data, shapes).isValid }
                }
            }
            val results = futures.flatMap { it.get(waitSeconds, TimeUnit.SECONDS) }
            assertTrue(results.none { it }, "ex:a always fails")
        } finally {
            pool.shutdownNow()
        }
        assertEquals(perRun * threads * runsPerThread, shared.shapeEvaluations)
    }

    /** A list whose first traversal parks until released: stands for a very large shapes snapshot that is slow to hash. */
    private class ParkingList(private val delegate: List<RdfTriple>, val entered: CountDownLatch, val release: CountDownLatch) :
        AbstractList<RdfTriple>() {
        override val size: Int get() = delegate.size
        override fun get(index: Int): RdfTriple = delegate[index]
        override fun iterator(): Iterator<RdfTriple> {
            entered.countDown()
            release.await(120, TimeUnit.SECONDS)
            return delegate.iterator()
        }
    }

    @Test
    fun `hashing a shapes snapshot does not hold the digest memo lock`() {
        val validator = NativeShaclValidator(ValidationConfig())
        val triples = patternShapes.getTriples()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val slow = pool.submit<String> { validator.digestOf(ParkingList(triples, entered, release), ValidationBudget.NONE) }
            assertTrue(entered.await(waitSeconds, TimeUnit.SECONDS))
            // The first run is parked inside its O(n) traversal of the snapshot; a second run must not wait for it.
            val other = pool.submit<String> { validator.digestOf(triples.reversed(), ValidationBudget.NONE) }
            try {
                other.get(waitSeconds, TimeUnit.SECONDS)
            } finally {
                release.countDown()
            }
            assertEquals(validator.digestOf(triples, ValidationBudget.NONE), slow.get(waitSeconds, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    // --- stack of the validating thread ------------------------------------------------------------------------------

    @Test
    fun `a maxRecursionDepth beyond the validating thread's stack fails the validation instead of the thread`() {
        val depth = 4_000
        val shapes = graph(
            buildString {
                append("ex:S0 a sh:NodeShape ; sh:targetNode ex:x ; sh:node ex:S1 .\n")
                for (i in 1 until depth) append("ex:S$i a sh:NodeShape ; sh:node ex:S${i + 1} .\n")
                append("ex:S$depth a sh:NodeShape ; sh:class ex:Missing .\n")
            },
        )
        val data = graph("ex:x ex:p 1 .")
        val outcome = AtomicReference<Throwable?>()
        val thread = Thread(null, {
            try {
                NativeShaclValidator(ValidationConfig(maxRecursionDepth = Int.MAX_VALUE)).validate(data, shapes)
            } catch (t: Throwable) {
                outcome.set(t)
            }
        }, "small-stack-validation", 512L * 1024)
        thread.start()
        thread.join(waitSeconds * 1000)
        assertFalse(thread.isAlive, "validation did not finish")
        val error = outcome.get() ?: fail("the deep nesting did not exhaust a 512 KiB stack; raise the depth of the test")
        assertTrue(error is ShaclValidationException, error.toString())
        assertTrue(error.message.orEmpty().contains("maxRecursionDepth"), error.message)
    }
    // --- message templates -------------------------------------------------------------------------------------------

    @Test
    fun `message substitution is bounded and marks what it left out`() {
        // A message of many {?v} placeholders times a long data value would otherwise be their product per result.
        val placeholders = "{?v}".repeat(1_000)
        val shapes = graph("ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ sh:message \"$placeholders\" ; " +
            "sh:select \"SELECT \$this ?v WHERE { \$this ex:p ?v }\" ; sh:prefixes ex: ] . ex: sh:declare [ sh:prefix \"ex\" ; sh:namespace \"http://example.org/\"^^<http://www.w3.org/2001/XMLSchema#anyURI> ] .")
        val long = "x".repeat(30_000)
        val report = NativeShaclValidator(ValidationConfig.default()).validate(graph("ex:a ex:p \"$long\" ."), shapes)
        val message = report.violations.single().resultMessages.single().lexical
        assertTrue(message.length < 2 * 65_536 + placeholders.length, "${message.length}")
        assertTrue(message.startsWith(long + long + "{?v}"), message.take(80))
        assertTrue(message.endsWith("[message truncated: substituted values exceed 65536 characters]"), message.takeLast(120))
        // A short message is substituted in full.
        val short = NativeShaclValidator(ValidationConfig.default()).validate(graph("ex:a ex:p \"v\" ."), shapes)
        assertEquals("v".repeat(1_000), short.violations.single().resultMessages.single().lexical)
    }
}
