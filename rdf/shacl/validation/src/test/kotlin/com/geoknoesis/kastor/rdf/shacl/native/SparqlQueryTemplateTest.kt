package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.string
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SparqlQueryTemplateTest {

    private val d = '$'

    @Test
    fun `parameter names are pre-bound like this`() {
        val template = SparqlQueryTemplate.compile("SELECT ?x WHERE { ?x <p> ${d}lang }", setOf("this", "lang"))
        val text = template.bind(mapOf("lang" to string("de"))).first
        assertTrue(text.contains("\"de\"^^<http://www.w3.org/2001/XMLSchema#string>"), text)
        assertFalse(text.contains("${d}lang"), text)
    }

    @Test
    fun `AS rebinding any pre-bound name is rejected`() {
        assertNotNull(SparqlQueryTemplate.restrictionViolation("ASK { BIND (true AS ?value) }", setOf("this", "value")))
        assertNull(SparqlQueryTemplate.restrictionViolation("ASK { BIND (true AS ?value) }", setOf("this")))
    }

    @Test
    fun `sub-select not projecting this is allowed`() {
        val query = "SELECT ${d}this WHERE { { SELECT ?y WHERE { ${d}this <p> ?y } } }"
        assertNull(SparqlQueryTemplate.restrictionViolation(query, setOf("this")))
    }

    @Test
    fun `MINUS SERVICE and VALUES stay rejected`() {
        for (body in listOf("MINUS { ?a <p> ?b }", "SERVICE <http://e/s> { ?a <p> ?b }", "VALUES ?a { 1 }")) {
            assertNotNull(SparqlQueryTemplate.restrictionViolation("ASK { $body }", setOf("this")), body)
        }
    }

    @Test
    fun `PATH with either sigil is substituted and stays unbound without a path`() {
        val q = "?a ?PATH ?b . ?a ${d}PATH ?c"
        assertEquals("?a <p> ?b . ?a <p> ?c", SparqlQueryTemplate.substitutePath(q) { "<p>" })
        assertEquals(q, SparqlQueryTemplate.substitutePath(q, null))
    }

    @Test
    fun `ask becomes a select`() {
        assertEquals(
            "PREFIX ex: <e:>\nSELECT (1 AS ?__kastor_ask) { FILTER (true) }",
            SparqlQueryTemplate.askAsSelect("PREFIX ex: <e:>\nASK { FILTER (true) }"),
        )
        assertEquals("SELECT (1 AS ?__kastor_ask) WHERE {}", SparqlQueryTemplate.askAsSelect("ASK WHERE {}"))
        val commented = "# ASK\nASK {}"
        assertEquals("# ASK\nSELECT (1 AS ?__kastor_ask) {}", SparqlQueryTemplate.askAsSelect(commented))
        val onlyComment = "SELECT * { } # ASK"
        assertEquals(onlyComment, SparqlQueryTemplate.askAsSelect(onlyComment))
    }

    @Test
    fun `projected variables of the outermost select`() {
        assertEquals(listOf("fullName"), SparqlQueryTemplate.projectedVariables("SELECT ?fullName WHERE { ?x <p> ?fullName }"))
        assertEquals(listOf("a", "b"), SparqlQueryTemplate.projectedVariables("SELECT DISTINCT ?a ?b { ?a <p> ?b }"))
        assertEquals(listOf("result"), SparqlQueryTemplate.projectedVariables("SELECT (STRLEN(STR(${d}this)) AS ?result) WHERE {}"))
        assertEquals(
            listOf("result"),
            SparqlQueryTemplate.projectedVariables("SELECT (COUNT(?label) AS ?result) WHERE { { SELECT ?z {} } }"),
        )
        assertNull(SparqlQueryTemplate.projectedVariables("SELECT * {}"))
    }

    @Test
    fun `local names and var names`() {
        assertEquals("lang", SparqlQueryTemplate.localName(Iri("http://e/ns#lang")))
        assertEquals("test1", SparqlQueryTemplate.localName(Iri("urn:x:test1")))
        assertTrue(SparqlQueryTemplate.isVarName("arg0"))
        assertTrue(SparqlQueryTemplate.isVarName("0a"))
        assertFalse(SparqlQueryTemplate.isVarName("0a-"))
        assertFalse(SparqlQueryTemplate.isVarName(""))
        assertFalse(SparqlQueryTemplate.isVarName("\u00B7a"))
    }
}
