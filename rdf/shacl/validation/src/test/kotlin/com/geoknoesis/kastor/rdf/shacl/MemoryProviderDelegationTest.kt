@file:Suppress("DEPRECATION")

package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.providers.MemoryShaclValidator
import com.geoknoesis.kastor.rdf.shacl.providers.MemoryShaclValidatorProvider
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidatorProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The legacy `memory` provider used to be a stub (no targets, four constraint kinds, shape lists always valid) that
 * nevertheless claimed SHACL Core and was preferred over the native engine under [EnginePreference.BRIDGE_FIRST]
 * when no bridge was installed. It is now an alias of the native engine, so no resolution path can return silently
 * wrong validation.
 */
class MemoryProviderDelegationTest {

    private val data = Rdf.parse(
        """
        @prefix ex: <http://example.org/> .
        ex:alice a ex:Person ; ex:code "AB12" ; ex:status ex:active .
        ex:bob a ex:Person ; ex:code "bad code" ; ex:status ex:unknown .
        ex:widget a ex:Product ; ex:code "not a person code" ; ex:status ex:whatever .
        """.trimIndent(),
        RdfFormat.TURTLE,
    )

    private val shapes = Rdf.parse(
        """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
        ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
          sh:property [ sh:path ex:code ; sh:pattern "^[A-Z]{2}[0-9]{2}$" ] ;
          sh:property [ sh:path ex:status ; sh:in ( ex:active ex:inactive ) ] .
        """.trimIndent(),
        RdfFormat.TURTLE,
    )

    private fun <T> withProviders(vararg providers: ShaclValidatorProvider, block: () -> T): T {
        ValidatorRegistry.clear()
        providers.forEach(ValidatorRegistry::register)
        try {
            return block()
        } finally {
            ValidatorRegistry.clear()
            ValidatorRegistry.discoverProviders()
        }
    }

    private fun assertOnlyBobFails(report: ValidationReport) {
        assertFalse(report.isValid)
        assertEquals(
            setOf(ConstraintType.PATTERN, ConstraintType.IN),
            report.violations.map { it.constraint.constraintType }.toSet(),
            report.violations.toString(),
        )
        assertEquals(2, report.violations.size, report.violations.toString())
        assertEquals(setOf<Any>(Iri("http://example.org/bob")), report.violations.map { it.focusNode }.toSet())
    }

    @Test
    fun `BRIDGE_FIRST without a bridge falls back to the native engine`() {
        withProviders(MemoryShaclValidatorProvider(), NativeShaclValidatorProvider()) {
            for (preference in EnginePreference.values()) {
                val config = ValidationConfig(enginePreference = preference)
                assertEquals("kastor", ValidatorRegistry.resolveProvider(config).getType(), preference.name)
                assertOnlyBobFails(ValidatorRegistry.createValidator(config).validate(data, shapes))
            }
        }
    }

    @Test
    fun `the memory provider validates targets and every core constraint like the native engine`() {
        assertOnlyBobFails(MemoryShaclValidatorProvider().createValidator(ValidationConfig()).validate(data, shapes))
        assertOnlyBobFails(MemoryShaclValidator(ValidationConfig()).validate(data, shapes))
        withProviders(MemoryShaclValidatorProvider(), NativeShaclValidatorProvider()) {
            assertOnlyBobFails(ValidatorRegistry.createValidator(ValidationConfig(providerId = "memory")).validate(data, shapes))
        }
    }

    @Test
    fun `the memory provider reports the capabilities of the engine it delegates to`() {
        val memory = MemoryShaclValidatorProvider()
        val native = NativeShaclValidatorProvider()
        assertEquals("memory", memory.getType())
        assertEquals(native.getCapabilities(), memory.getCapabilities())
        assertEquals(native.getSupportedProfiles(), memory.getSupportedProfiles())
        assertFalse(memory.getCapabilities().supportsParallelValidation)
        assertTrue(memory.priority() > native.priority(), "the alias never outranks the native provider")
    }

    @Test
    fun `shape lists and constraint lists are rejected instead of being reported valid`() {
        val constraint = ShaclConstraint(ConstraintType.MIN_COUNT, path = "http://example.org/missing", parameters = mapOf("value" to 5))
        val shape = ShaclShape(shapeUri = "http://example.org/S", targetClass = "http://example.org/Person", constraints = listOf(constraint))
        for (validator in listOf(MemoryShaclValidatorProvider().createValidator(ValidationConfig()), NativeShaclValidatorProvider().createValidator(ValidationConfig()))) {
            // The module's domain exception, with a message that says what to do instead.
            val shapes = assertThrows(UnsupportedShaclOperationException::class.java) { validator.validate(data, listOf(shape)) }
            assertTrue(shapes.message.orEmpty().contains("validate(graph, shapesGraph)"), shapes.message)
            val constraints = assertThrows(UnsupportedShaclOperationException::class.java) { validator.validateConstraints(data, listOf(constraint)) }
            assertTrue(constraints.message.orEmpty().contains("shapes"), constraints.message)
            // Empty lists validate against an empty shapes graph.
            assertTrue(validator.validate(data, emptyList<ShaclShape>()).isValid)
            assertTrue(validator.validateConstraints(data, emptyList()).isValid)
        }
    }

    @Test
    fun `unsupported execution modes are rejected with the domain exception`() {
        val providers = listOf(MemoryShaclValidatorProvider(), NativeShaclValidatorProvider())
        for (provider in providers) {
            val parallel = assertThrows(UnsupportedShaclOperationException::class.java) { provider.createValidator(ValidationConfig(parallelValidation = true)) }
            assertTrue(parallel.message.orEmpty().contains("parallelValidation"), parallel.message)
            val streaming = assertThrows(UnsupportedShaclOperationException::class.java) { provider.createValidator(ValidationConfig(streamingMode = true)) }
            assertTrue(streaming.message.orEmpty().contains("streamingMode"), streaming.message)
        }
        assertThrows(UnsupportedShaclOperationException::class.java) { MemoryShaclValidator(ValidationConfig(parallelValidation = true)) }
        // A ShaclValidationException like every other failure of the module.
        assertTrue(ShaclValidationException::class.java.isAssignableFrom(UnsupportedShaclOperationException::class.java))
    }
}
