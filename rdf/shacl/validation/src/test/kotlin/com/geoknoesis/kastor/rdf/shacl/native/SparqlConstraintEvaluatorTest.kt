package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** `sh:sparql` constraints run through the engine's own pre-binding ([SparqlQueryTemplate]), sub-selects included. */
class SparqlConstraintEvaluatorTest {

    private val prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <http://example.org/> .\n"

    private fun violatingFocusNodes(select: String): List<Any?> {
        val shapes = prefixes + "ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:c ; sh:sparql [ sh:select \"\"\"$select\"\"\" ] ."
        val data = prefixes + "ex:a ex:p ex:b ."
        return NativeShaclValidator(ValidationConfig.default())
            .validate(Rdf.parse(data, RdfFormat.TURTLE), Rdf.parse(shapes, RdfFormat.TURTLE))
            .violations.map { it.focusNode }
    }

    @Test
    fun `a SPARQL constraint is evaluated with this pre-bound to each focus node`() {
        // Only ex:a has ex:p, so only its query returns a row.
        assertEquals(listOf(Iri("http://example.org/a")), violatingFocusNodes("SELECT \$this WHERE { \$this <http://example.org/p> ?o }"))
    }

    @Test
    fun `this is pre-bound inside a sub-select that does not project it`() {
        // As provider initial bindings, \$this would stay unbound inside the sub-select and match ex:a for every focus.
        val query = "SELECT \$this WHERE { { SELECT ?o WHERE { \$this <http://example.org/p> ?o } } }"
        assertEquals(listOf(Iri("http://example.org/a")), violatingFocusNodes(query))
    }
}
