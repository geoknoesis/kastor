package com.example.ontomapper.validation.jena

import com.example.ontomapper.runtime.ShaclValidation
import com.example.ontomapper.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class JenaValidationTest {

    @Test
    fun `valid node passes validation`() {
        val validation = JenaValidation()
        
        val repo = Rdf.memory()
        val catalog = Iri("http://example.org/catalog")
        val personClass = Iri("http://example.org/Person")
        addNameShape(repo, personClass)
        
        repo.add {
            catalog - com.geoknoesis.kastor.rdf.vocab.RDF.type - personClass
            catalog - FOAF.name - "Test Person"
        }
        
        val result = validation.validate(repo.defaultGraph, catalog)
        assertEquals(ValidationResult.Ok, result)
    }
    
    @Test
    fun `invalid node triggers exception`() {
        val validation = JenaValidation()
        
        val repo = Rdf.memory()
        val catalog = Iri("http://example.org/catalog")
        val personClass = Iri("http://example.org/Person")
        addNameShape(repo, personClass)
        
        // Missing required name property
        repo.add {
            catalog - com.geoknoesis.kastor.rdf.vocab.RDF.type - personClass
        }
        
        val result = validation.validate(repo.defaultGraph, catalog)
        assertTrue(result is ValidationResult.Violations)
    }
    
    @Test
    fun `validation registry works correctly`() {
        val validation = JenaValidation()
        
        // Should be registered automatically
        val currentValidation = ShaclValidation.current()
        assertSame(validation, currentValidation)
    }

    private fun addNameShape(repo: com.geoknoesis.kastor.rdf.RdfRepository, targetClass: Iri) {
        val sh = "http://www.w3.org/ns/shacl#"
        val xsd = "http://www.w3.org/2001/XMLSchema#"
        val shape = Iri("urn:shape:PersonShape")
        val nameShape = bnode("nameShape")

        val shNodeShape = Iri("${sh}NodeShape")
        val shTargetClass = Iri("${sh}targetClass")
        val shProperty = Iri("${sh}property")
        val shPath = Iri("${sh}path")
        val shDatatype = Iri("${sh}datatype")
        val shMinCount = Iri("${sh}minCount")
        val shMessage = Iri("${sh}message")

        repo.add {
            shape - com.geoknoesis.kastor.rdf.vocab.RDF.type - shNodeShape
            shape - shTargetClass - targetClass
            shape - shProperty - nameShape

            nameShape - shPath - FOAF.name
            nameShape - shDatatype - Iri("${xsd}string")
            nameShape - shMinCount - 1
            nameShape - shMessage - "Name is required"
        }
    }
}












