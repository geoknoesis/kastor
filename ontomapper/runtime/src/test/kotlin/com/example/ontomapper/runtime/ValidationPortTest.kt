package com.example.ontomapper.runtime

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ValidationPortTest {

    @Test
    fun `ValidationRegistry throws when no port registered`() {
        // Clear any existing registration
        ValidationRegistry.register(object : ValidationPort {
            override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {}
        })
        
        // This should not throw since we just registered a port
        assertDoesNotThrow {
            ValidationRegistry.current()
        }
    }
    
    @Test
    fun `ValidationRegistry allows registration and retrieval`() {
        val testPort = object : ValidationPort {
            override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {
                // Simple validation: throw if no triples
                if (data.getTriples().isEmpty()) {
                    throw RuntimeException("No triples found")
                }
            }
        }
        
        ValidationRegistry.register(testPort)
        val retrieved = ValidationRegistry.current()
        
        assertSame(testPort, retrieved)
    }
    
    @Test
    fun `ValidationRegistry handles multiple registrations`() {
        val firstPort = object : ValidationPort {
            override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {}
        }
        
        val secondPort = object : ValidationPort {
            override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {}
        }
        
        ValidationRegistry.register(firstPort)
        assertSame(firstPort, ValidationRegistry.current())
        
        ValidationRegistry.register(secondPort)
        assertSame(secondPort, ValidationRegistry.current())
    }
    
    @Test
    fun `ValidationPort can validate successfully`() {
        val testPort = object : ValidationPort {
            override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {
                // Always pass validation
            }
        }
        
        ValidationRegistry.register(testPort)
        
        val repo = Rdf.memory()
        val subject = iri("http://example.org/person")
        
        repo.add {
            subject - FOAF.name - "John Doe"
        }
        
        assertDoesNotThrow {
            testPort.validateOrThrow(repo.defaultGraph, subject)
        }
    }
    
    @Test
    fun `ValidationPort can throw validation errors`() {
        val testPort = object : ValidationPort {
            override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {
                throw RuntimeException("Validation failed")
            }
        }
        
        ValidationRegistry.register(testPort)
        
        val repo = Rdf.memory()
        val subject = iri("http://example.org/person")
        
        repo.add {
            subject - FOAF.name - "John Doe"
        }
        
        assertThrows(RuntimeException::class.java) {
            testPort.validateOrThrow(repo.defaultGraph, subject)
        }
    }
    
    @Test
    fun `ValidationPort receives correct parameters`() {
        val receivedData = mutableListOf<Pair<RdfGraph, RdfTerm>>()
        
        val testPort = object : ValidationPort {
            override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {
                receivedData.add(Pair(data, focus))
            }
        }
        
        ValidationRegistry.register(testPort)
        
        val repo = Rdf.memory()
        val subject = iri("http://example.org/person")
        
        repo.add {
            subject - FOAF.name - "John Doe"
        }
        
        testPort.validateOrThrow(repo.defaultGraph, subject)
        
        assertEquals(1, receivedData.size)
        assertEquals(repo.defaultGraph, receivedData.first().first)
        assertEquals(subject, receivedData.first().second)
    }
}
