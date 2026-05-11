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
        
        val formatException = RdfFormatException.Generic("Format failed")
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
        val exception = RdfQueryException("Query failed", cause = cause)
        
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
            throw RdfFormatException.Generic("Format failed")
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
            RdfFormatException.Generic("Format failed"),
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
    
    @Test
    fun `RdfException has error code`() {
        val queryException = RdfQueryException("Query failed")
        assertEquals(RdfErrorCode.QUERY_EXECUTION_ERROR, queryException.errorCode)
        
        val formatException = RdfFormatException.Generic("Format failed", RdfErrorCode.FORMAT_PARSE_ERROR)
        assertEquals(RdfErrorCode.FORMAT_PARSE_ERROR, formatException.errorCode)
        
        val unsupportedFormat = RdfFormatException.UnsupportedFormat("X", listOf())
        assertEquals(RdfErrorCode.FORMAT_UNSUPPORTED, unsupportedFormat.errorCode)
    }
    
    @Test
    fun `Error codes can be used for programmatic handling`() {
        val exception = RdfQueryException(
            "Query failed",
            errorCode = RdfErrorCode.QUERY_SYNTAX_ERROR
        )
        
        when (exception.errorCode) {
            RdfErrorCode.QUERY_SYNTAX_ERROR -> {
                // Handle syntax errors
                assertTrue(true)
            }
            RdfErrorCode.QUERY_EXECUTION_ERROR -> {
                // Handle execution errors
                fail("Should not reach here")
            }
            else -> fail("Should not reach here")
        }
    }
    
    @Test
    fun `Error code context is included in exception context`() {
        val exception = RdfQueryException("Query failed", query = "SELECT ?s")
        val context = exception.context
        
        assertNotNull(context)
        assertEquals(RdfErrorCode.QUERY_EXECUTION_ERROR.code, context?.get("errorCode"))
        assertEquals("SELECT ?s", context?.get("query"))
    }
}

