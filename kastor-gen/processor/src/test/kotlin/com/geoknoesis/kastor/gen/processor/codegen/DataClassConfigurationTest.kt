package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.geoknoesis.kastor.gen.processor.api.exceptions.InvalidConfigurationException
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.core.GenerationCoordinator
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.gen.processor.testing.prop
import com.google.devtools.ksp.processing.CodeGenerator
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `NestedMode.IRI_ONLY` types object references as IRI strings, so data classes cannot override interface members
 * typed with the referenced interfaces: the combination with `dataClassImplementsInterface = true` is rejected at
 * generation time with guidance instead of producing code that does not compile.
 */
class DataClassConfigurationTest {

    private val emptyContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())

    private val unusedCodeGenerator = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CodeGenerator::class.java)) { _, method, _ ->
        error("CodeGenerator.${method.name} must not be called")
    } as CodeGenerator

    private fun people() = OntologyModel(
        listOf(ShaclShape(EX + "PersonShape", EX + "Person", listOf(prop("name"), prop("knows", targetClass = EX + "Person", maxCount = null)))),
        emptyContext,
    )

    @Test
    fun `IRI_ONLY data classes implementing interfaces with object members are rejected with guidance`() {
        val e = assertFailsWith<InvalidConfigurationException> {
            GenerationCoordinator(RecordingLogger(), unusedCodeGenerator).generateFromOntology(
                model = people(), packageName = "gen.irionly", generateInterfaces = true, generateWrappers = true,
                validationMode = ValidationMode.NONE, validationAnnotations = ValidationAnnotations.NONE, externalValidatorClass = null,
                generateDataClass = true, dataClassImplementsInterface = true, nestedMode = NestedMode.IRI_ONLY,
            )
        }
        val message = e.message.orEmpty()
        assertTrue("IRI_ONLY" in message && "Person.knows" in message && "dataClassImplementsInterface" in message, message)

        assertFailsWith<InvalidConfigurationException> {
            DataClassGenerator(RecordingLogger(), "Record", NestedMode.IRI_ONLY, true, ValidationAnnotations.NONE)
                .generateDataClasses(people(), "gen.irionly")
        }
    }

    @Test
    fun `IRI_ONLY data classes compile when they do not implement interfaces or have no object members`() {
        val logger = RecordingLogger()
        val tags = OntologyModel(listOf(ShaclShape(EX + "TagShape", EX + "Tag", listOf(prop("label")))), emptyContext)
        val implementing = InterfaceGenerator(logger, ValidationAnnotations.NONE).generateInterfaces(tags, "gen.irionly.tags").values +
            DataClassGenerator(logger, "Record", NestedMode.IRI_ONLY, true, ValidationAnnotations.NONE).generateDataClasses(tags, "gen.irionly.tags").values
        val standalone = DataClassGenerator(logger, "Record", NestedMode.IRI_ONLY, false, ValidationAnnotations.NONE)
            .generateDataClasses(people(), "gen.irionly.people").values
        KotlinSourceCompiler.compile(implementing + standalone).assertOk()
    }
}
