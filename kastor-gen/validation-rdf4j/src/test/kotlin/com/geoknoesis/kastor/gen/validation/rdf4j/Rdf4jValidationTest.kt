package com.geoknoesis.kastor.gen.validation.rdf4j

import com.geoknoesis.kastor.gen.runtime.ValidationException
import com.geoknoesis.kastor.gen.runtime.orThrow
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class Rdf4jValidationTest {

    @Test
    fun `valid node passes validation`() {
        val validation = Rdf4jValidation()
        
        val repo = Rdf.memory()
        val catalog = Iri("http://example.org/catalog")
        val personClass = Iri("http://example.org/Person")
        addNameShape(repo, personClass)
        
        repo.add {
            catalog - com.geoknoesis.kastor.rdf.vocab.RDF.type - personClass
            catalog - FOAF.name - "Test Person"
        }
        
        // Should not throw
        assertDoesNotThrow {
            validation.validate(repo.defaultGraph, catalog).orThrow()
        }
    }
    
    @Test
    fun `invalid node triggers exception`() {
        val validation = Rdf4jValidation()
        
        val repo = Rdf.memory()
        val catalog = Iri("http://example.org/catalog")
        val personClass = Iri("http://example.org/Person")
        addNameShape(repo, personClass)
        
        // Missing required name property
        repo.add {
            catalog - com.geoknoesis.kastor.rdf.vocab.RDF.type - personClass
        }
        
        // Should throw validation exception
        assertThrows(ValidationException::class.java) {
            validation.validate(repo.defaultGraph, catalog).orThrow()
        }
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












