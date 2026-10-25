package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `shnex:nodesMatching` (Node Expressions §4.5.2) does not depend on the focus node: outside the recursion solver its
 * outputs are computed once per shape and run, not once per focus node (a scan of every node of the data graph and a
 * sort each time).
 */
class NodesMatchingCacheTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .
        @prefix ex: <http://example.com/ns#> .
    """.trimIndent()

    private fun g(ttl: String) = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)

    @Test
    fun `the outputs of nodesMatching are computed once per shape and run`() {
        // Every target gets the two ex:Flagged nodes as extra value nodes; ex:b is no ex:C.
        val shapes = """
            ex:M a sh:NodeShape ; sh:property [ sh:path ex:flag ; sh:minCount 1 ] .
            ex:S a sh:NodeShape ; sh:targetSubjectsOf ex:id ;
              sh:property [ sh:path ex:link ; sh:values [ shnex:nodesMatching ex:M ] ; sh:class ex:C ] .
        """
        val targets = (1..20).joinToString(" ") { "ex:t$it ex:id $it ." }
        val data = "$targets ex:a ex:flag 1 ; a ex:C . ex:b ex:flag 2 ."
        val validator = NativeShaclValidator(ValidationConfig.default()) { JenaProvider().createRepository("memory", RdfConfig()) }
        val report = validator.validate(g(data), g(shapes))
        assertEquals(20, report.violations.size, report.violations.toString())
        assertEquals(setOf(Iri("http://example.com/ns#b")), report.violations.map { it.value }.toSet())
        assertEquals(1L, validator.nodesMatchingScans)
    }
}
