package com.example.ontomapper.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.add
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.vocab.FOAF
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ShaclValidatorTest {

    @Test
    fun `ShaclValidation throws when no validator registered`() {
        ShaclValidation.register(null)

        assertThrows(IllegalStateException::class.java) {
            ShaclValidation.current()
        }
    }

    @Test
    fun `ShaclValidation allows registration and retrieval`() {
        val validator = object : ShaclValidator {
            override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
                return ValidationResult.Ok
            }
        }

        ShaclValidation.register(validator)
        val retrieved = ShaclValidation.current()

        assertSame(validator, retrieved)
    }

    @Test
    fun `ShaclValidation handles multiple registrations`() {
        val first = object : ShaclValidator {
            override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult = ValidationResult.Ok
        }
        val second = object : ShaclValidator {
            override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult = ValidationResult.Ok
        }

        ShaclValidation.register(first)
        assertSame(first, ShaclValidation.current())

        ShaclValidation.register(second)
        assertSame(second, ShaclValidation.current())
    }

    @Test
    fun `validator can return violations`() {
        val validator = object : ShaclValidator {
            override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
                return ValidationResult.Violations(listOf(ShaclViolation(null, "Validation failed")))
            }
        }

        ShaclValidation.register(validator)

        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")

        repo.add {
            subject - FOAF.name - "John Doe"
        }

        val result = validator.validate(repo.defaultGraph, subject)
        assertTrue(result is ValidationResult.Violations)
    }

    @Test
    fun `validator receives correct parameters`() {
        val receivedData = mutableListOf<Pair<RdfGraph, RdfTerm>>()

        val validator = object : ShaclValidator {
            override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
                receivedData.add(Pair(data, focus))
                return ValidationResult.Ok
            }
        }

        ShaclValidation.register(validator)

        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")

        repo.add {
            subject - FOAF.name - "John Doe"
        }

        validator.validate(repo.defaultGraph, subject)

        assertEquals(1, receivedData.size)
        assertEquals(repo.defaultGraph, receivedData.first().first)
        assertEquals(subject, receivedData.first().second)
    }
}












