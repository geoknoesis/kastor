package com.example.ontomapper.validation.rdf4j

import com.example.ontomapper.runtime.ValidationRegistry
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class Rdf4jValidationTest {

    @Test
    fun `valid node passes validation`() {
        val validation = Rdf4jValidation()
        
        val repo = Rdf.memory()
        val catalog = iri("http://example.org/catalog")
        
        repo.add {
            catalog - RDF.type - FOAF.Person
            catalog - FOAF.name - "Test Person"
            catalog - DCTERMS.description - "A test person"
        }
        
        // Should not throw
        assertDoesNotThrow {
            validation.validateOrThrow(repo.defaultGraph, catalog)
        }
    }
    
    @Test
    fun `invalid node triggers exception`() {
        val validation = Rdf4jValidation()
        
        val repo = Rdf.memory()
        val catalog = iri("http://example.org/catalog")
        
        // Missing required name property
        repo.add {
            catalog - RDF.type - FOAF.Person
            catalog - DCTERMS.description - "A test person"
        }
        
        // Should throw validation exception
        assertThrows(ValidationException::class.java) {
            validation.validateOrThrow(repo.defaultGraph, catalog)
        }
    }
    
    @Test
    fun `validation registry works correctly`() {
        val validation = Rdf4jValidation()
        
        // Should be registered automatically
        val currentValidation = ValidationRegistry.current()
        assertSame(validation, currentValidation)
    }
}
