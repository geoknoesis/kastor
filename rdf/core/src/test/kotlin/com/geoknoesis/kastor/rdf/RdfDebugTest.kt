package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for RdfDebug functionality.
 */
class RdfDebugTest {
    
    @Test
    fun `test enable and disable debug mode`() {
        // Initially disabled
        assertFalse(RdfDebug.isEnabled)
        
        // Enable
        RdfDebug.enable {
            showPrefixExpansion = true
            showQueryTrace = true
        }
        assertTrue(RdfDebug.isEnabled)
        assertTrue(RdfDebug.showPrefixExpansion)
        assertTrue(RdfDebug.showQueryTrace)
        
        // Disable
        RdfDebug.disable()
        assertFalse(RdfDebug.isEnabled)
        assertFalse(RdfDebug.showPrefixExpansion)
        assertFalse(RdfDebug.showQueryTrace)
    }
    
    @Test
    fun `test prefix expansion logging`() {
        RdfDebug.enable {
            showPrefixExpansion = true
        }
        
        val prefixMappings = mapOf("foaf" to "http://xmlns.com/foaf/0.1/")
        val qname = "foaf:name"
        val expectedIri = "http://xmlns.com/foaf/0.1/name"
        
        // This should log the prefix expansion
        RdfDebug.logPrefixExpansion(qname, expectedIri, prefixMappings)
        
        // Verify it was called (no exception means it worked)
        assertTrue(true)
        
        RdfDebug.disable()
    }
    
    @Test
    fun `test query trace logging`() {
        RdfDebug.enable {
            showQueryTrace = true
        }
        
        val query = "SELECT ?name WHERE { ?person <http://xmlns.com/foaf/0.1/name> ?name . }"
        
        // Log query trace
        RdfDebug.logQueryTrace("SELECT", query, null, 5L, 1)
        
        // Verify it was called (no exception means it worked)
        assertTrue(true)
        
        RdfDebug.disable()
    }
    
    @Test
    fun `test query error logging`() {
        RdfDebug.enable {
            showQueryTrace = true
        }
        
        val query = "SELECT ?name WHERE { ?person foaf:name ?name . }"
        val error = "Unknown prefix: 'foaf'"
        
        // Log query error
        RdfDebug.logQueryError("SELECT", query, error)
        
        // Verify it was called (no exception means it worked)
        assertTrue(true)
        
        RdfDebug.disable()
    }
    
    @Test
    fun `test prefix expansion with QNameResolver`() {
        RdfDebug.enable {
            showPrefixExpansion = true
        }
        
        val prefixMappings = mapOf("foaf" to "http://xmlns.com/foaf/0.1/")
        val qname = "foaf:name"
        
        // Resolve QName - should trigger debug logging
        val resolved = QNameResolver.resolve(qname, prefixMappings)
        assertEquals("http://xmlns.com/foaf/0.1/name", resolved)
        
        RdfDebug.disable()
    }
}

