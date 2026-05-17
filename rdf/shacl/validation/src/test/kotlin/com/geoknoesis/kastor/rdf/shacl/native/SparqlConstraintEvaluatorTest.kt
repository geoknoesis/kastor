package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import org.junit.jupiter.api.Assertions.assertEquals
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
}
