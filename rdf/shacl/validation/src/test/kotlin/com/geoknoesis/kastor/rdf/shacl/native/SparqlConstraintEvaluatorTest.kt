package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.iri
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SparqlConstraintEvaluatorTest {

    @Test
    fun bindThis_rewritesShaclThisVariableForIriFocus() {
        val q = "SELECT ?x WHERE { FILTER (\$this = \$this) }"
        val out = SparqlConstraintEvaluator.bindThis(q, Iri("http://example.org/a"))
        assertEquals(
            "SELECT ?x WHERE { FILTER (<http://example.org/a> = <http://example.org/a>) }",
            out,
        )
    }

    @Test
    fun bindThis_doesNotRewriteThisInTheSelectProjection() {
        // The standard SHACL idiom `SELECT $this WHERE { ... }` must stay valid SPARQL: $this in the
        // projection is a variable and must NOT be replaced by the focus term (which would yield the
        // illegal `SELECT <iri> WHERE ...`). Only occurrences in the query body are pre-bound.
        val q = "SELECT \$this WHERE { \$this <http://example.org/p> \$this }"
        val out = SparqlConstraintEvaluator.bindThis(q, Iri("http://example.org/a"))
        assertEquals(
            "SELECT \$this WHERE { <http://example.org/a> <http://example.org/p> <http://example.org/a> }",
            out,
        )
    }

    @Test
    fun selectReturnsRows_executesShaclThisProjectingConstraint() {
        val g = Rdf.graph {
            iri("http://example.org/a") - iri("http://example.org/p") - iri("http://example.org/b")
        }
        val query = "SELECT \$this WHERE { \$this <http://example.org/p> ?o }"

        // Focus node `a` has the property -> the constraint query returns a row.
        assertTrue(SparqlConstraintEvaluator.selectReturnsRows(query, g, iri("http://example.org/a")))
        // Focus node `c` does not -> no rows (and pre-binding scopes correctly to the focus).
        assertFalse(SparqlConstraintEvaluator.selectReturnsRows(query, g, iri("http://example.org/c")))
    }
}
