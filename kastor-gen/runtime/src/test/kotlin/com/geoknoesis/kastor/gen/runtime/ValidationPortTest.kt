package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.add
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.vocab.FOAF
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ValidationContextTest {

    @Test
    fun `validation context can return violations`() {
        val validator = object : ValidationContext {
            override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
                return ValidationResult.Violations(listOf(ShaclViolation(null, "Validation failed")))
            }
        }

        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")

        repo.add {
            subject - FOAF.name - "John Doe"
        }

        val result = validator.validate(repo.defaultGraph, subject)
        assertTrue(result is ValidationResult.Violations)
    }

    @Test
    fun `validation context receives correct parameters`() {
        val receivedData = mutableListOf<Pair<RdfGraph, RdfTerm>>()

        val validator = object : ValidationContext {
            override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
                receivedData.add(Pair(data, focus))
                return ValidationResult.Ok
            }
        }

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












