package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RdfExceptionsTest {
    
    @Test
    fun `RdfException is sealed and can be instantiated as subclasses`() {
        val queryException = RdfQueryException("Query failed", query = "SELECT ?s WHERE { ?s ?p ?o }")
        assertEquals("Query failed", queryException.message)
        
        val transactionException = RdfTransactionException("Transaction failed")
        assertEquals("Transaction failed", transactionException.message)
        
        val providerException = RdfProviderException("Provider failed")
        assertEquals("Provider failed", providerException.message)
        
        val validationException = RdfValidationException("Validation failed")
        assertEquals("Validation failed", validationException.message)
        
        val formatException = RdfFormatException("Format failed")
        assertEquals("Format failed", formatException.message)
        
        val repositoryException = RdfRepositoryException("Repository failed")
        assertEquals("Repository failed", repositoryException.message)
        
        val graphException = RdfGraphException("Graph failed")
        assertEquals("Graph failed", graphException.message)
        
        val federationException = RdfFederationException("Federation failed")
        assertEquals("Federation failed", federationException.message)
        
        val inferenceException = RdfInferenceException("Inference failed")
        assertEquals("Inference failed", inferenceException.message)
        
        val configurationException = RdfConfigurationException("Configuration failed")
        assertEquals("Configuration failed", configurationException.message)
    }
    
    @Test
    fun `RdfException can have a cause`() {
        val cause = IllegalArgumentException("Root cause")
        val exception = RdfQueryException("Query failed", cause)
        
        assertEquals("Query failed", exception.message)
        assertEquals(cause, exception.cause)
        assertTrue(exception.cause is IllegalArgumentException)
    }
    
    @Test
    fun `RdfException can be thrown and caught`() {
        assertThrows(RdfQueryException::class.java) {
            throw RdfQueryException("Query failed")
        }
        
        assertThrows(RdfTransactionException::class.java) {
            throw RdfTransactionException("Transaction failed")
        }
        
        assertThrows(RdfProviderException::class.java) {
            throw RdfProviderException("Provider failed")
        }
        
        assertThrows(RdfValidationException::class.java) {
            throw RdfValidationException("Validation failed")
        }
        
        assertThrows(RdfFormatException::class.java) {
            throw RdfFormatException("Format failed")
        }
        
        assertThrows(RdfRepositoryException::class.java) {
            throw RdfRepositoryException("Repository failed")
        }
        
        assertThrows(RdfGraphException::class.java) {
            throw RdfGraphException("Graph failed")
        }
        
        assertThrows(RdfFederationException::class.java) {
            throw RdfFederationException("Federation failed")
        }
        
        assertThrows(RdfInferenceException::class.java) {
            throw RdfInferenceException("Inference failed")
        }
        
        assertThrows(RdfConfigurationException::class.java) {
            throw RdfConfigurationException("Configuration failed")
        }
    }
    
    @Test
    fun `RdfException can be caught as base type`() {
        val exceptions = listOf(
            RdfQueryException("Query failed"),
            RdfTransactionException("Transaction failed"),
            RdfProviderException("Provider failed"),
            RdfValidationException("Validation failed"),
            RdfFormatException("Format failed"),
            RdfRepositoryException("Repository failed"),
            RdfGraphException("Graph failed"),
            RdfFederationException("Federation failed"),
            RdfInferenceException("Inference failed"),
            RdfConfigurationException("Configuration failed")
        )
        
        exceptions.forEach { exception ->
            try {
                throw exception
            } catch (e: RdfException) {
                assertNotNull(e.message, "Exception should have a message")
            }
        }
    }
}

