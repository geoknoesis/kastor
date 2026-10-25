package com.geoknoesis.kastor.gen.processor.parsers

import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.internal.parsers.JsonLdContextParser
import com.geoknoesis.kastor.rdf.Iri
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `@base`, relative `@vocab` and base-relative term IRIs are resolved with RFC 3986 reference resolution, as
 * JSON-LD 1.1 context processing (§4.1.3, §4.1.2) requires, not by string concatenation.
 */
class JsonLdIriResolutionTest {

    private val logger = object : KSPLogger {
        override fun logging(message: String, symbol: KSNode?) {}
        override fun info(message: String, symbol: KSNode?) {}
        override fun warn(message: String, symbol: KSNode?) {}
        override fun error(message: String, symbol: KSNode?) {}
        override fun exception(e: Throwable) {}
    }

    private fun parse(context: String): JsonLdContext = JsonLdContextParser(logger).parseContextContent("""{ "@context": $context }""")

    @Test
    fun `a term without vocabulary is resolved against a base that does not end with a slash`() {
        val context = parse("""{ "@base": "http://ex.org/a/b", "Thing": "c", "Up": "../d", "Frag": "#e" }""")
        assertEquals(Iri("http://ex.org/a/c"), context.typeMappings["Thing"])
        assertEquals(Iri("http://ex.org/d"), context.typeMappings["Up"])
        assertEquals(Iri("http://ex.org/a/b#e"), context.typeMappings["Frag"])
        assertEquals(Iri("http://ex.org/a/b"), context.baseIri)
    }

    @Test
    fun `query-only and over-climbing references follow RFC 3986, not RFC 2396`() {
        val context = parse("""{ "@base": "http://a/b/c/d;p?q", "Query": "?y", "Climb": "../../../g", "Seg": "g;x=1/../y" }""")
        assertEquals(Iri("http://a/b/c/d;p?y"), context.typeMappings["Query"])
        assertEquals(Iri("http://a/g"), context.typeMappings["Climb"])
        assertEquals(Iri("http://a/b/c/y"), context.typeMappings["Seg"])
    }

    @Test
    fun `a very long chain of term references is an error, not a stack overflow`() {
        val terms = (0 until 5_000).joinToString(", ") { "\"t$it\": \"t${it + 1}\"" }
        val e = assertThrows(IllegalArgumentException::class.java) {
            parse("""{ "@vocab": "http://ex.org/", $terms, "t5000": "http://ex.org/end" }""")
        }
        assertTrue("more than 256" in e.message!!, e.message)
        val short = (0 until 100).joinToString(", ") { "\"t$it\": \"t${it + 1}\"" }
        assertEquals(Iri("http://ex.org/end"), parse("""{ $short, "t100": "http://ex.org/end" }""").typeMappings["t0"])
    }

    @Test
    fun `a relative vocabulary is resolved against the base`() {
        val fragment = parse("""{ "@base": "http://ex.org/doc", "@vocab": "#", "Person": {} }""")
        assertEquals(Iri("http://ex.org/doc#"), fragment.vocabIri)
        assertEquals(Iri("http://ex.org/doc#Person"), fragment.typeMappings["Person"])

        val path = parse("""{ "@base": "http://ex.org/a/doc", "@vocab": "terms/", "Person": {} }""")
        assertEquals(Iri("http://ex.org/a/terms/Person"), path.typeMappings["Person"])
    }

    @Test
    fun `a relative base is resolved against the previous base and a base without a path gets one`() {
        val context = parse("""[{ "@base": "http://ex.org/a/b" }, { "@base": "sub/", "Thing": "c" }]""")
        assertEquals(Iri("http://ex.org/a/sub/"), context.baseIri)
        assertEquals(Iri("http://ex.org/a/sub/c"), context.typeMappings["Thing"])
        assertEquals(Iri("http://ex.org/c"), parse("""{ "@base": "http://ex.org", "Thing": "c" }""").typeMappings["Thing"])
    }

    @Test
    fun `a relative base or vocabulary without an absolute base is rejected`() {
        val base = assertThrows(IllegalArgumentException::class.java) { parse("""{ "@base": "relative/", "Thing": "c" }""") }
        assertTrue("@base" in base.message!! && "relative/" in base.message!!, base.message)
        val vocab = assertThrows(IllegalArgumentException::class.java) { parse("""{ "@vocab": "#", "Person": {} }""") }
        assertTrue("@vocab" in vocab.message!! && "#" in vocab.message!!, vocab.message)
    }
}
