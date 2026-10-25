package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.gen.processor.testing.prop
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Interfaces and data classes generated with Bean Validation annotations (`JAKARTA`, the `@Rdf` default, and
 * `JAVAX`) must compile. `JAKARTA` compiles against the real `jakarta.validation-api` jar on the test class path
 * (the API that kastor-gen-runtime gives consumers at compile time), so a wrong annotation name, target or member
 * fails. The javax API is not a dependency of the build, so `JAVAX` uses minimal source stubs under the real
 * package name, which still fail when a generator names a class that does not exist.
 */
class ValidationAnnotationsCompilationTest {

    private val emptyContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())

    private fun model() = OntologyModel(
        listOf(
            ShaclShape(
                EX + "PersonShape", EX + "Person",
                listOf(
                    prop("name", minCount = 1),
                    prop("nickname", maxCount = 1),
                    prop("tags", minCount = 1, maxCount = 3),
                    prop("aliases", maxCount = null),
                ),
            )
        ),
        emptyContext,
    )

    private fun stubs(pkg: String): Map<String, String> = mapOf(
        "${pkg.replace('.', '/')}/constraints/Stubs.kt" to """
            package $pkg.constraints

            annotation class NotNull
            annotation class NotEmpty
            annotation class Size(val min: Int = 0, val max: Int = Int.MAX_VALUE)
        """.trimIndent(),
    )

    @Test
    fun `generated interfaces and data classes compile with JAKARTA annotations`() = assertCompiles(ValidationAnnotations.JAKARTA)

    @Test
    fun `generated interfaces and data classes compile with JAVAX annotations`() = assertCompiles(ValidationAnnotations.JAVAX)

    private fun assertCompiles(flavour: ValidationAnnotations) {
        val pkg = if (flavour == ValidationAnnotations.JAKARTA) "jakarta.validation" else "javax.validation"
        val logger = RecordingLogger()
        val interfaces = InterfaceGenerator(logger, flavour).generateInterfaces(model(), "gen.va")
        val records = DataClassGenerator(logger, "Record", NestedMode.IRI_ONLY, false, flavour)
            .generateDataClasses(model(), "gen.va")

        val stubs = if (flavour == ValidationAnnotations.JAKARTA) emptyMap() else stubs(pkg)
        val result = KotlinSourceCompiler.compile(interfaces.values + records.values, stubs)
        result.assertOk()

        val record = records.values.single().toString()
        assertTrue("import $pkg.constraints.NotNull" in record, record)
        assertTrue("@field:NotNull" in record && "@field:NotEmpty" in record && "@field:Size(" in record, record)
        assertFalse("import $pkg.constraints\n" in record, record)
    }
}
