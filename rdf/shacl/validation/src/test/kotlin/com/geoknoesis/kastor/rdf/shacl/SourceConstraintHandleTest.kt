package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.shacl.native.CompiledShapeGraph
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The run-wide handle a result's `sh:sourceConstraint` deep copy is written from ([SourceConstraintTriples]). */
class SourceConstraintHandleTest {

    @Test
    fun `the handle of a validation run does not retain the compiled shapes graph`() {
        val shapes = Rdf.parse(
            """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .
            @prefix sparql: <http://www.w3.org/ns/sparql#> .
            @prefix ex: <http://example.com/ns#> .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; sh:expression [ sparql:less-than ( [ shnex:var "value" ] 5 ) ] ] .
            """.trimIndent(),
            RdfFormat.TURTLE,
        )
        val data = Rdf.parse("<http://example.com/ns#a> <http://example.com/ns#p> 7 .", RdfFormat.TURTLE)
        val report = NativeShaclValidator(ValidationConfig.default()) { JenaProvider().createRepository("memory", RdfConfig()) }
            .validate(data, shapes)
        val handle = report.violations.single().context[SOURCE_CONSTRAINT_TRIPLES_CONTEXT_KEY] as SourceConstraintTriples
        val describe = SourceConstraintTriples::class.java.getDeclaredField("describe").apply { isAccessible = true }.get(handle)
        val captured = describe.javaClass.declaredFields.map { it.apply { isAccessible = true }.get(describe) }
        assertTrue(captured.none { it is CompiledShapeGraph }, "the describe function captures ${captured.map { it?.javaClass }}")
    }
}
