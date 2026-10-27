package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.SparqlIriSubstitution
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
        val text = template.bind(mapOf("lang" to string("de"))).text
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
    fun `MINUS SERVICE and VALUES on pre-bound variables stay rejected`() {
        for (body in listOf("MINUS { ?a <p> ?b }", "SERVICE <http://e/s> { ?a <p> ?b }", "VALUES ?this { 1 }", "VALUES (?a ?lang) { (1 2) }")) {
            assertNotNull(SparqlQueryTemplate.restrictionViolation("ASK { $body }", setOf("this", "lang")), body)
        }
    }

    @Test
    fun `VALUES without pre-bound variables is allowed`() {
        for (query in listOf("ASK { VALUES ?a { 1 } }", "ASK { VALUES (?a ?b) { (1 UNDEF) } }", "SELECT ?a WHERE { ?a <p> ?b } VALUES ?a { <x> }")) {
            assertNull(SparqlQueryTemplate.restrictionViolation(query, setOf("this", "lang")), query)
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

    private fun restricted(body: String, preBound: Set<String> = setOf("this")) =
        SparqlQueryTemplate.compileRestricted("PREFIX ex: <http://example.org/> ", body, preBound, "the test query")

    @Test
    fun `an IRI focus node is offered as a template the text was written from`() {
        val template = restricted("SELECT ${d}this ?o WHERE { ${d}this ex:p ?o FILTER (?o != ?this) FILTER bound(${d}this) }")
        for (focus in listOf("http://example.org/a", "urn:b")) {
            val bound = template.bind(mapOf("this" to Iri(focus)))
            val substitution = bound.substitution!!
            assertEquals(mapOf("__kastor_iri_this" to Iri(focus)), substitution.iris)
            assertFalse(substitution.template.contains(focus), substitution.template)
            // The text is the template with the IRI written where the placeholder stands, whichever sigil it has.
            val written = substitution.template.replace("${d}__kastor_iri_this", "<$focus>").replace("?__kastor_iri_this", "<$focus>")
            assertEquals(bound.text, written)
        }
        assertEquals(
            template.bind(mapOf("this" to Iri("urn:x"))).substitution!!.template,
            template.bind(mapOf("this" to Iri("urn:y"))).substitution!!.template,
        )
    }

    @Test
    fun `no template for a literal, an unrestricted query or a GROUP BY slot`() {
        val template = restricted("SELECT ?o WHERE { ${d}this ex:p ?o }")
        assertNull(template.bind(mapOf("this" to string("a"))).substitution)
        assertNull(SparqlQueryTemplate.compile("SELECT ?o WHERE { ${d}this <urn:p> ?o }", setOf("this")).bind(mapOf("this" to Iri("urn:a"))).substitution)
        assertNull(restricted("SELECT (COUNT(?o) AS ?c) WHERE { ?o ex:p ?x } GROUP BY ${d}this").bind(mapOf("this" to Iri("urn:a"))).substitution)
        // An IRI and a literal: the literal would make every template distinct.
        val two = restricted("SELECT ?o WHERE { ${d}this ex:p ${d}value }", setOf("this", "value"))
        assertNull(two.bind(mapOf("this" to Iri("urn:a"), "value" to string("v"))).substitution)
        assertNotNull(two.bind(mapOf("this" to Iri("urn:a"), "value" to Iri("urn:v"))).substitution)
    }

    @Test
    fun `a blank node focus, bound by the provider, is offered as a template without IRIs, the text itself`() {
        val template = restricted("SELECT ${d}this ?o WHERE { ${d}this ex:p ?o FILTER NOT EXISTS { ?r ex:says <<( ${d}this ex:p ?o )>> } }")
        for (focus in listOf(BlankNode("b1"), BlankNode("b2"))) {
            val bound = template.bind(mapOf("this" to focus))
            assertEquals(mapOf("this" to focus), bound.providerBindings)
            assertEquals(SparqlIriSubstitution(bound.text, emptyMap()), bound.substitution)
        }
        assertEquals(template.bind(mapOf("this" to BlankNode("b1"))).text, template.bind(mapOf("this" to BlankNode("b2"))).text)
        assertEquals(SparqlIriSubstitution(template.bind(emptyMap()).text, emptyMap()), template.bind(emptyMap()).substitution)
    }

    @Test
    fun `the placeholder is a variable the query does not use`() {
        val template = restricted("SELECT ?__kastor_iri_this WHERE { ${d}this ex:p ?__kastor_iri_this }")
        val substitution = template.bind(mapOf("this" to Iri("urn:a"))).substitution!!
        assertEquals(setOf("__kastor_iri1_this"), substitution.iris.keys)
    }
}
