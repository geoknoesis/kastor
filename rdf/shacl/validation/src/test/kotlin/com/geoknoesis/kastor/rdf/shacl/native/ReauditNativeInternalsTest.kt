package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReauditNativeInternalsTest {

    private fun xsd(local: String) = Iri(XSD.namespace + local)
    private fun ex(local: String) = Iri("http://example.org/$local")

    // --- path evaluation: deadline and literal starts ---

    @Test fun `path closures consult the validation deadline`() {
        var now = 0L
        val budget = ValidationBudget(Duration.ofSeconds(1)) { now }
        val data = Rdf.graph { for (i in 0 until 5_000) ex("c$i") - ex("next") - ex("c${i + 1}") }
        val index = DataGraphIndex(data, budget)
        now = Duration.ofSeconds(5).toNanos()
        assertThrows(ShaclValidationException::class.java) {
            PathEvaluator.evaluate(ex("c0"), ShaclPath.ZeroOrMore(ShaclPath.Predicate(ex("next"))), index)
        }
    }

    @Test fun `zeroOrMore and zeroOrOne paths from a literal yield the literal`() {
        val index = DataGraphIndex(Rdf.graph { ex("a") - ex("p") - ex("b") })
        val literal = TypedLiteral("x", XSD.string)
        assertEquals(listOf(literal), PathEvaluator.evaluate(literal, ShaclPath.ZeroOrMore(ShaclPath.Predicate(ex("p"))), index))
        assertEquals(listOf(literal), PathEvaluator.evaluate(literal, ShaclPath.ZeroOrOne(ShaclPath.Predicate(ex("p"))), index))
    }

    // --- term equality and comparison ---

    @Test fun `language tags compare case-insensitively`() {
        assertTrue(shaclRdfTermEquals(LangString("chat", "EN-gb"), LangString("chat", "en-GB")))
        assertEquals(shaclRdfTermFingerprint(LangString("chat", "EN")), shaclRdfTermFingerprint(LangString("chat", "en")))
    }

    @Test fun `24 00 00 xsd time equals midnight`() {
        assertEquals(0, tryCompareLiterals(TypedLiteral("24:00:00", XSD.time), TypedLiteral("00:00:00", XSD.time)))
        assertTrue(literalLess(TypedLiteral("24:00:00", XSD.time), TypedLiteral("00:00:01", XSD.time)))
    }

    // --- XSD lexical spaces ---

    @Test fun `XML name token and URI datatypes are lexically checked`() {
        fun valid(lex: String, dt: String) = literalLexicallyValid(TypedLiteral(lex, xsd(dt)))
        assertTrue(valid("_a-b.c", "NCName")); assertFalse(valid("1abc", "NCName")); assertFalse(valid("a:b", "NCName")); assertFalse(valid("", "NCName"))
        for (dt in listOf("ID", "IDREF", "ENTITY")) { assertTrue(valid("abc", dt)); assertFalse(valid("a b", dt)) }
        assertTrue(valid(":a", "Name")); assertFalse(valid("1a", "Name"))
        assertTrue(valid("1a", "NMTOKEN")); assertFalse(valid("a b", "NMTOKEN"))
        assertTrue(valid("a b", "NMTOKENS")); assertFalse(valid("", "NMTOKENS"))
        assertTrue(valid("x y", "IDREFS")); assertFalse(valid("1x", "IDREFS"))
        assertTrue(valid("p:local", "QName")); assertFalse(valid("a:b:c", "QName"))
        assertTrue(valid("http://example.org/a#b", "anyURI")); assertFalse(valid("http://example.org/%zz", "anyURI"))
        // xsd:string accepts any Unicode string (W3C singleLine-001 uses U+000B); stricter string types require XML chars.
        assertTrue(valid("a\tb", "string")); assertTrue(valid("a\u000Bb", "string"))
        assertFalse(valid("a\u0001b", "normalizedString")); assertFalse(valid("a\uFFFEb", "token"))
    }

    // --- compile cache ---

    @Test fun `waiters receive the owner's deterministic compile failure without recompiling`() {
        val cache = NativeCompileCache()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val owner = thread {
            runCatching {
                cache.getOrCompile("k") { calls.incrementAndGet(); started.countDown(); release.await(); throw ShapeCompileException("boom") }
            }
        }
        assertTrue(started.await(60, TimeUnit.SECONDS))
        // The owner may only fail once the waiter holds the owner's attempt: a waiter that arrives after the failure
        // would rightly compile again. The cache counts a hit when it hands the attempt to the waiter, and the waiter
        // consults its budget (this clock) between that and waiting, so the latch opens exactly then - no sleep.
        val joined = CountDownLatch(1)
        val waiterBudget = ValidationBudget(Duration.ofMinutes(10)) {
            if (cache.statistics().hits >= 1L) joined.countDown()
            System.nanoTime()
        }
        val waiter = CompletableFuture.supplyAsync {
            runCatching { cache.getOrCompile("k", waiterBudget) { calls.incrementAndGet(); error("recompiled") } }
        }
        assertTrue(joined.await(60, TimeUnit.SECONDS), "the waiter joined the owner's compilation")
        release.countDown()
        owner.join()
        val result = waiter.get(60, TimeUnit.SECONDS)
        assertTrue(result.exceptionOrNull() is ShapeCompileException, result.toString())
        assertEquals(1, calls.get())
    }

    // --- digest memo ---

    @Test fun `clearCache also clears the shapes digest memo`() {
        val data = Rdf.parse("<http://example.org/a> <http://example.org/p> 1 .", RdfFormat.TURTLE)
        val shapes = Rdf.parse(
            "<http://example.org/S> a <http://www.w3.org/ns/shacl#NodeShape> ; <http://www.w3.org/ns/shacl#targetNode> <http://example.org/a> .",
            RdfFormat.TURTLE,
        )
        val validator = NativeShaclValidator(ValidationConfig.default())
        validator.validate(data, shapes)
        validator.validate(data, shapes)
        assertEquals(1L, validator.digestMemoHits)
        validator.clearCache()
        validator.validate(data, shapes)
        assertEquals(1L, validator.digestMemoHits)
    }
}
