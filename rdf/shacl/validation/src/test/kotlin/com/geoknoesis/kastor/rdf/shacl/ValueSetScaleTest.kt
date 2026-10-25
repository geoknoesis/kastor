package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.shacl.native.graphFromTriples
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test

/**
 * `sh:in` and `sh:languageIn` look values up in their lists instead of scanning them: a long list and many values
 * cost their sum, not their product. Their semantics (RDF term equality; basic language range filtering) are kept.
 */
class ValueSetScaleTest {

    private val ex = "http://example.org/"
    private fun ex(local: String) = Iri(ex + local)

    private fun list(head: String, members: List<RdfTerm>): List<RdfTriple> {
        val cells = members.indices.map { BlankNode("$head$it") }
        return members.flatMapIndexed { i, m ->
            listOf(RdfTriple(cells[i], RDF.first, m), RdfTriple(cells[i], RDF.rest, cells.getOrElse(i + 1) { RDF.nil }))
        }
    }

    private fun shapes(parameter: Iri, members: List<RdfTerm>): List<RdfTriple> = list("m", members) + listOf(
        RdfTriple(ex("S"), RDF.type, SHACL.NodeShape),
        RdfTriple(ex("S"), SHACL.targetSubjectsOf, ex("p")),
        RdfTriple(ex("S"), SHACL.`property`, BlankNode("ps")),
        RdfTriple(BlankNode("ps"), SHACL.path, ex("p")),
        RdfTriple(BlankNode("ps"), parameter, BlankNode("m0")),
    )

    /** A distinct alphabetic language subtag for [i] (`z` and base-26 letters). */
    private fun tag(i: Int): String {
        var n = i
        val out = StringBuilder("z")
        do { out.append('a' + n % 26); n /= 26 } while (n > 0)
        return out.toString()
    }

    private fun validate(data: List<RdfTriple>, shapes: List<RdfTriple>) =
        NativeShaclValidator(ValidationConfig.default()).validate(graphFromTriples(data), graphFromTriples(shapes))

    @Test
    fun `a long sh in list and many values cost their sum`() {
        val members = (0 until 50_000).map { ex("v$it") }
        // 100,000 values, all near the end of the list: a scan per value would make 5 * 10^9 comparisons.
        val data = (0 until 20).flatMap { f -> (0 until 5_000).map { RdfTriple(ex("f$f"), ex("p"), ex("v${49_999 - it}")) } }
        assertTimeoutPreemptively(Duration.ofSeconds(60)) {
            assertEquals(0, validate(data, shapes(SHACL.`in`, members)).violations.size)
        }
        // Equality is still RDF term equality: a literal of another datatype is not in the list.
        val literals = listOf(TypedLiteral("1", XSD.integer), LangString("a", "EN"))
        val bad = listOf(RdfTriple(ex("f"), ex("p"), TypedLiteral("1", XSD.string)), RdfTriple(ex("f"), ex("p"), LangString("a", "en")))
        assertEquals(listOf<RdfTerm?>(TypedLiteral("1", XSD.string)), validate(bad, shapes(SHACL.`in`, literals)).violations.map { it.value })
    }

    @Test
    fun `a long sh languageIn list and many values cost their sum`() {
        val ranges = (0 until 50_000).map { TypedLiteral(tag(it), XSD.string) } + TypedLiteral("en-*", XSD.string) + TypedLiteral("DE", XSD.string)
        val data = (0 until 20).flatMap { f -> (0 until 5_000).map { RdfTriple(ex("f$f"), ex("p"), LangString("v$it", tag(49_999 - it) + "-a")) } }
        assertTimeoutPreemptively(Duration.ofSeconds(60)) {
            assertEquals(0, validate(data, shapes(SHACL.languageIn, ranges)).violations.size)
        }
        // Basic filtering: a range matches the tag itself and the tags it is a prefix of, at a subtag boundary.
        val values = listOf("en", "EN-gb", "en-gb-oed", "de", "de-CH", "deu", "fr")
        val report = validate(values.map { RdfTriple(ex("f"), ex("p"), LangString("t", it)) }, shapes(SHACL.languageIn, ranges))
        assertEquals(setOf("deu", "fr"), report.violations.map { (it.value as LangString).lang }.toSet())
    }
}
