package com.example.ontomapper.validation.rdf4j

import com.example.ontomapper.runtime.ValidationRegistry
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class Rdf4jValidationAdvancedTest {

    @Test
    fun `validation handles complex RDF graphs`() {
        val validation = Rdf4jValidation()
        ValidationRegistry.register(validation)

        val repo = Rdf.memory()
        val person = iri("http://example.org/person")
        val org = iri("http://example.org/org")

        repo.add {
            person - RDF.type - FOAF.Person
            person - FOAF.name - "John Doe"
            person - FOAF.age - 30
            person - FOAF.member - org
            person - DCTERMS.description - "A person"

            org - RDF.type - FOAF.Organization
            org - FOAF.name - "Example Corp"
        }

        // Should not throw for valid complex graph
        assertDoesNotThrow {
            validation.validateOrThrow(repo.defaultGraph, person)
        }
    }

    @Test
    fun `validation handles empty graphs gracefully`() {
        val validation = Rdf4jValidation()
        ValidationRegistry.register(validation)

        val repo = Rdf.memory()
        val person = iri("http://example.org/person")

        // Empty graph
        assertDoesNotThrow {
            validation.validateOrThrow(repo.defaultGraph, person)
        }
    }

    @Test
    fun `validation handles multiple validation rules`() {
        val validation = Rdf4jValidation()
        ValidationRegistry.register(validation)

        val repo = Rdf.memory()
        val person1 = iri("http://example.org/person1")
        val person2 = iri("http://example.org/person2")

        repo.add {
            // Valid person
            person1 - RDF.type - FOAF.Person
            person1 - FOAF.name - "Alice"

            // Invalid person (missing name)
            person2 - RDF.type - FOAF.Person
            person2 - FOAF.age - 25
        }

        // Valid person should pass
        assertDoesNotThrow {
            validation.validateOrThrow(repo.defaultGraph, person1)
        }

        // Invalid person should fail
        assertThrows(ValidationException::class.java) {
            validation.validateOrThrow(repo.defaultGraph, person2)
        }
    }

    @Test
    fun `validation handles non-Person types gracefully`() {
        val validation = Rdf4jValidation()
        ValidationRegistry.register(validation)

        val repo = Rdf.memory()
        val org = iri("http://example.org/org")

        repo.add {
            org - RDF.type - FOAF.Organization
            org - FOAF.name - "Example Corp"
        }

        // Should pass for non-Person types (no validation rules apply)
        assertDoesNotThrow {
            validation.validateOrThrow(repo.defaultGraph, org)
        }
    }

    @Test
    fun `validation handles malformed IRIs gracefully`() {
        val validation = Rdf4jValidation()
        ValidationRegistry.register(validation)

        val repo = Rdf.memory()
        val person = iri("http://example.org/person")

        repo.add {
            person - RDF.type - iri("invalid-iri")
            person - FOAF.name - "John"
        }

        // Should handle malformed IRIs gracefully
        assertDoesNotThrow {
            validation.validateOrThrow(repo.defaultGraph, person)
        }
    }

    @Test
    fun `validation handles blank nodes`() {
        val validation = Rdf4jValidation()
        ValidationRegistry.register(validation)

        val repo = Rdf.memory()
        val person = BlankNode("person1")

        repo.add {
            person - RDF.type - FOAF.Person
            person - FOAF.name - "Anonymous"
        }

        // Should work with blank nodes
        assertDoesNotThrow {
            validation.validateOrThrow(repo.defaultGraph, person)
        }
    }

    @Test
    fun `validation handles language-tagged literals`() {
        val validation = Rdf4jValidation()
        ValidationRegistry.register(validation)

        val repo = Rdf.memory()
        val person = iri("http://example.org/person")

        repo.add {
            person - RDF.type - FOAF.Person
            person - FOAF.name - LangString("Jean", "fr")
            person - FOAF.name - LangString("John", "en")
        }

        // Should handle language-tagged literals
        assertDoesNotThrow {
            validation.validateOrThrow(repo.defaultGraph, person)
        }
    }

    @Test
    fun `validation handles typed literals`() {
        val validation = Rdf4jValidation()
        ValidationRegistry.register(validation)

        val repo = Rdf.memory()
        val person = iri("http://example.org/person")

        repo.add {
            person - RDF.type - FOAF.Person
            person - FOAF.name - "John"
            person - FOAF.age - TypedLiteral("30", iri("http://www.w3.org/2001/XMLSchema#int"))
        }

        // Should handle typed literals
        assertDoesNotThrow {
            validation.validateOrThrow(repo.defaultGraph, person)
        }
    }

    @Test
    fun `validation error messages are informative`() {
        val validation = Rdf4jValidation()
        ValidationRegistry.register(validation)

        val repo = Rdf.memory()
        val person = iri("http://example.org/person")

        repo.add {
            person - RDF.type - FOAF.Person
            // Missing required name property
        }

        val exception = assertThrows(ValidationException::class.java) {
            validation.validateOrThrow(repo.defaultGraph, person)
        }

        assertTrue(exception.message!!.contains("FOAF Person must have a name property"))
    }

    @Test
    fun `validation registry integration works correctly`() {
        val validation = Rdf4jValidation()
        
        // Should be automatically registered
        val currentValidation = ValidationRegistry.current()
        assertSame(validation, currentValidation)

        val repo = Rdf.memory()
        val person = iri("http://example.org/person")

        repo.add {
            person - RDF.type - FOAF.Person
            person - FOAF.name - "John"
        }

        // Should work through registry
        assertDoesNotThrow {
            currentValidation.validateOrThrow(repo.defaultGraph, person)
        }
    }

    @Test
    fun `validation handles large graphs efficiently`() {
        val validation = Rdf4jValidation()
        ValidationRegistry.register(validation)

        val repo = Rdf.memory()
        val person = iri("http://example.org/person")

        // Add many triples
        repo.add {
            person - RDF.type - FOAF.Person
            person - FOAF.name - "John"
            
            // Add many properties to test performance
            repeat(100) { i ->
                person - iri("http://example.org/prop$i") - "value$i"
            }
        }

        // Should handle large graphs efficiently
        assertDoesNotThrow {
            validation.validateOrThrow(repo.defaultGraph, person)
        }
    }
}
