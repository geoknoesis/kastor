package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.gen.processor.testing.prop
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A generated `EXTERNAL` validator that names `Rdf4jValidation` creates it with its no-arg constructor, which
 * validates the shapes embedded in the data on the Kastor SHACL validator: it still reports violations, and still
 * refuses shapes past the caps.
 */
class ExternalRdf4jValidationTest {
    private val probe = """
        package gen.extrdf4j

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*

        private val prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <https://example.test/> .\n"

        private fun check(turtle: String): String {
            val g = Rdf.parse(prefixes + turtle, "TURTLE")
            val p = OntoMapper.materialize(RdfRef(Iri("https://example.test/p"), g), Person::class.java) as PersonWrapper
            return try {
                when (val r = p.validate()) {
                    ValidationResult.Ok -> "ok"
                    is ValidationResult.Violations -> r.items.joinToString(",") { it.constraintIri.value.substringAfter('#') }
                }
            } catch (e: EmbeddedShapesRefusedException) { "refused" }
        }

        fun probe(): String {
            val shape = "ex:S a sh:NodeShape ; sh:targetClass ex:Person ; sh:property [ sh:path ex:name ; sh:minCount 1 ] .\n"
            val chain = "ex:C0 a sh:NodeShape ; sh:targetNode ex:p .\n" +
                (0 until 20).joinToString("\n") { "ex:C${'$'}it sh:or ( ex:C${'$'}{it + 1} ex:C${'$'}{it + 1} ) ." } + "\n"
            val out = listOf(
                check(shape + "ex:p a ex:Person ."),
                check(shape + "ex:p a ex:Person ; ex:name \"n\" ."),
                check(chain + "ex:p a ex:Person ."),
            ).joinToString("|")
            SharedValidators.closeAll()
            return out
        }
    """.trimIndent()

    @Test
    fun `an EXTERNAL validator naming Rdf4jValidation validates embedded shapes`() {
        val logger = RecordingLogger()
        val model = OntologyModel(listOf(ShaclShape(EX + "PersonShape", EX + "Person", listOf(prop("name")))), JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap()))
        val files = InterfaceGenerator(logger, ValidationAnnotations.NONE).generateInterfaces(model, "gen.extrdf4j").values +
            OntologyWrapperGenerator(logger, ValidationMode.EXTERNAL, "com.geoknoesis.kastor.gen.validation.rdf4j.Rdf4jValidation")
                .generateWrappers(model, "gen.extrdf4j").values
        val result = KotlinSourceCompiler.compile(files, mapOf("gen/extrdf4j/Probe.kt" to probe))
        result.assertOk()
        assertEquals("MinCountConstraintComponent|ok|refused", result.classLoader().loadClass("gen.extrdf4j.ProbeKt").getMethod("probe").invoke(null))
    }
}
