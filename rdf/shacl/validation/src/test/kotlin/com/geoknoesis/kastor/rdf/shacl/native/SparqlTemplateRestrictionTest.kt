package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.SparqlPreBindingRestrictionException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pre-binding restriction check of [SparqlQueryTemplate.compileRestricted] on hostile or unusual query text. */
class SparqlTemplateRestrictionTest {

    private val d = "$"
    private val escapedI = "\\u0069"

    /** [escapedI] in a Turtle string, which decodes escapes of its own. */
    private val escapedInTurtle = "\\\\u0069"

    @Test
    fun `a pre-bound variable spelled with a codepoint escape is rejected`() {
        // The template is built from the text as written, which has no ?this; RDF4J decodes the escape and would
        // see ?this unbound, matching every subject.
        for (body in listOf(
            "SELECT ?th${escapedI}s WHERE { ?th${escapedI}s <http://example.org/p> 1 }",
            "SELECT ${d}th\\U00000069s WHERE { ${d}th\\U00000069s <http://example.org/p> 1 }",
            // An escaped quote that ends the string early, so that ?this appears in the decoded text only.
            "SELECT * WHERE { ?x <http://example.org/p> \"a\\u0022 . ?this <http://example.org/q> \\u0022b\" }",
        )) {
            val error = assertThrows(SparqlPreBindingRestrictionException::class.java, { SparqlQueryTemplate.compileRestricted("", body, setOf("this"), "q") }, body)
            assertTrue("codepoint escape" in error.message.orEmpty(), error.message)
        }
        // Escapes that spell no pre-bound variable are fine, in strings and IRIs alike.
        val fine = "SELECT ${d}this WHERE { ${d}this <http://example.org/\\u0070> ?o FILTER (?o != \"\\u0041\") }"
        assertEquals(setOf("this"), SparqlQueryTemplate.compileRestricted("", fine, setOf("this"), "q").preBound)
    }

    @Test
    fun `an escaped pre-bound variable fails validation on RDF4J instead of matching every subject`() {
        val prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <http://example.org/> .\n"
        val shapes = prefixes +
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ sh:select '''SELECT ${d}th${escapedInTurtle}s WHERE { ${d}th${escapedInTurtle}s <http://example.org/p> 1 }''' ] ."
        val data = prefixes + "ex:b ex:p 1 ."
        val validator = NativeShaclValidator(ValidationConfig.default()) { Rdf4jProvider().createRepository("memory", RdfConfig()) }
        val error = assertThrows(ShaclValidationException::class.java) {
            validator.validate(Rdf.parse(data, RdfFormat.TURTLE), Rdf.parse(shapes, RdfFormat.TURTLE))
        }
        val restriction = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<SparqlPreBindingRestrictionException>().firstOrNull()
        assertTrue(restriction != null || error is SparqlPreBindingRestrictionException, "$error")
    }

    @Test
    fun `many VALUES words are checked in linear time`() {
        // Each VALUES used to scan forward to the next '{', at the end of the query: quadratic in the query length,
        // in six views, without ticking the budget. 200 000 words would take minutes that way.
        val query = "SELECT * WHERE { " + "VALUES ".repeat(200_000) + "}"
        assertTimeoutPreemptively(Duration.ofSeconds(60)) {
            assertNull(SparqlQueryTemplate.restrictionViolation(query, setOf("this")))
        }
        // The check itself is unchanged: a VALUES clause naming a pre-bound variable before its data block.
        assertEquals("VALUES for the pre-bound variable ${d}this", SparqlQueryTemplate.restrictionViolation("SELECT * WHERE { VALUES ?x ${d}this { (1 2) } }", setOf("this")))
        assertNull(SparqlQueryTemplate.restrictionViolation("SELECT * WHERE { VALUES ?x { 1 } ${d}this ?p ?x }", setOf("this")))
    }
    @Test
    fun `one long word is checked in linear time`() {
        // Each position of a run of name characters used to rescan the rest of the run looking for a ':' (prefixed
        // name): quadratic, so a million letters took about 18 minutes per view. One pass now finds the boundaries.
        val word = "a".repeat(1_000_000)
        assertTimeoutPreemptively(Duration.ofSeconds(60)) {
            assertNull(SparqlQueryTemplate.restrictionViolation("SELECT * WHERE { ?s ?p $word }", setOf("this")))
            assertNull(SparqlQueryTemplate.restrictionViolation("SELECT * WHERE { ?s ?p ${"a.".repeat(500_000)}b }", setOf("this")))
            SparqlQueryTemplate.compileRestricted("", "SELECT * WHERE { ?s ?p $word }", setOf("this"), "q")
        }
        // The forbidden keywords are still found in every bare part of a word, and never inside a prefixed name.
        assertEquals("SERVICE", SparqlQueryTemplate.restrictionViolation("SELECT * WHERE { ?s ?p ${word}SERVICE }", setOf("this")))
        assertEquals("MINUS", SparqlQueryTemplate.restrictionViolation("SELECT * WHERE { ?s ?p 1MINUS }", setOf("this")))
        assertEquals("MINUS", SparqlQueryTemplate.restrictionViolation("SELECT * WHERE { ?s ?p ex:a%zzMINUS }", setOf("this")))
        assertNull(SparqlQueryTemplate.restrictionViolation("SELECT * WHERE { ?s ?p ex:MINUS }", setOf("this")))
        assertNull(SparqlQueryTemplate.restrictionViolation("SELECT * WHERE { ?s ?p a.b.SERVICE:x }", setOf("this")))
        assertNull(SparqlQueryTemplate.restrictionViolation("SELECT * WHERE { ?s ?p _:SERVICE }", setOf("this")))
        assertEquals("SERVICE", SparqlQueryTemplate.restrictionViolation("SELECT * WHERE { ?s ?p a.SERVICE. }", setOf("this")))
    }

    @Test
    fun `the restriction check and the template compilation consume the budget`() {
        val query = "SELECT * WHERE { " + "?s ?p ?o . ".repeat(10_000) + "}"
        var expired = false
        val budget = ValidationBudget(Duration.ofSeconds(1)) { if (expired) 1_000_000_000_000L else 0L }
        expired = true
        val e = assertThrows(ShaclValidationException::class.java) {
            SparqlQueryTemplate.compileRestricted("", query, setOf("this"), "q", budget)
        }
        assertTrue("timed out" in e.message.orEmpty(), e.message)
        assertThrows(ShaclValidationException::class.java) { SparqlQueryTemplate.restrictionViolation(query, setOf("this"), budget) }
        assertThrows(ShaclValidationException::class.java) { SparqlQueryTemplate.compile(query, setOf("this"), budget) }
    }
}
