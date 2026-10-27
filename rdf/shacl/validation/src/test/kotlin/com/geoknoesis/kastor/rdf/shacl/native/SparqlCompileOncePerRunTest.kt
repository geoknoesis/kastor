package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A SPARQL constraint's query is compiled into its template, and checked against the pre-binding restrictions, once
 * per validation run: the work per focus node is only the binding of its value. Counted, not timed.
 */
class SparqlCompileOncePerRunTest {

    private val d = "$"

    private val shapes = Rdf.parse(
        """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
        ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:min ] ;
          sh:validator [ a sh:SPARQLAskValidator ; sh:ask '''ASK { FILTER (STRLEN(STR(${d}value)) >= ${d}min) }''' ] .
        ex:S a sh:NodeShape ; sh:targetClass ex:T ;
          sh:sparql [ sh:select '''SELECT ${d}this ?v WHERE { ${d}this <http://example.org/p> ?v . FILTER (REGEX(?v, "^[\\\\p{Lu}]")) }''' ] ;
          sh:property [ sh:path ex:p ; ex:min 2 ] .
        """.trimIndent(),
        RdfFormat.TURTLE,
    )

    private fun data(n: Int) = Rdf.parse(
        "@prefix ex: <http://example.org/> .\n" + (0 until n).joinToString("\n") { "ex:n$it a ex:T ; ex:p \"Value $it\", \"lower $it\" ." },
        RdfFormat.TURTLE,
    )

    /** (templates compiled, restricted compilations, violations) of one validation of [n] focus nodes. */
    private fun run(n: Int): Triple<Long, Long, Int> {
        val compiled = SparqlQueryTemplate.compilations.get()
        val restricted = SparqlQueryTemplate.restrictedCompilations.get()
        val report = NativeShaclValidator(ValidationConfig.default()).validate(data(n), shapes)
        return Triple(
            SparqlQueryTemplate.compilations.get() - compiled,
            SparqlQueryTemplate.restrictedCompilations.get() - restricted,
            report.violations.size,
        )
    }

    @Test
    fun `templates are compiled once per run whatever the number of focus nodes`() {
        val small = run(3)
        val large = run(60)
        assertEquals(3, small.third)
        assertEquals(60, large.third)
        // One sh:sparql constraint and one ASK validator: two restricted compilations, independent of the focus nodes.
        assertEquals(2L, large.second, "restricted compilations for 60 focus nodes")
        assertEquals(small.first, large.first, "template compilations for 3 and for 60 focus nodes")
    }
}
