package com.geoknoesis.kastor.gen.processor.utils

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class QNameResolverTest {

    @Test
    fun `resolveQName resolves QName to full IRI`() {
        val prefixMappings = mapOf(
            "dcat" to "http://www.w3.org/ns/dcat#",
            "dcterms" to "http://purl.org/dc/terms/",
            "foaf" to "http://xmlns.com/foaf/0.1/"
        )

        assertEquals("http://www.w3.org/ns/dcat#Catalog", QNameResolver.resolveQName("dcat:Catalog", prefixMappings))
        assertEquals("http://purl.org/dc/terms/title", QNameResolver.resolveQName("dcterms:title", prefixMappings))
        assertEquals("http://xmlns.com/foaf/0.1/name", QNameResolver.resolveQName("foaf:name", prefixMappings))
    }

    @Test
    fun `resolveQName returns full IRI as-is when not a QName`() {
        val prefixMappings = mapOf(
            "dcat" to "http://www.w3.org/ns/dcat#"
        )

        val fullIri = "http://www.w3.org/ns/dcat#Catalog"
        assertEquals(fullIri, QNameResolver.resolveQName(fullIri, prefixMappings))
    }

    @Test
    fun `resolveQName throws exception for unknown prefix`() {
        val prefixMappings = mapOf(
            "dcat" to "http://www.w3.org/ns/dcat#"
        )

        assertThrows(IllegalArgumentException::class.java) {
            QNameResolver.resolveQName("unknown:Catalog", prefixMappings)
        }
    }

    @Test
    fun `isQName correctly identifies QNames`() {
        assertTrue(QNameResolver.isQName("dcat:Catalog"))
        assertTrue(QNameResolver.isQName("dcterms:title"))
        assertTrue(QNameResolver.isQName("foaf:name"))
        assertTrue(QNameResolver.isQName("ex:customProperty"))
    }

    @Test
    fun `isQName correctly identifies non-QNames`() {
        assertFalse(QNameResolver.isQName("http://www.w3.org/ns/dcat#Catalog"))
        assertFalse(QNameResolver.isQName("https://example.org/resource"))
        assertFalse(QNameResolver.isQName("urn:uuid:12345678-1234-1234-1234-123456789012"))
        assertFalse(QNameResolver.isQName("file:///path/to/file"))
        assertFalse(QNameResolver.isQName("data:text/plain;base64,SGVsbG8="))
        assertFalse(QNameResolver.isQName("simpleName"))
        assertFalse(QNameResolver.isQName(""))
    }

    @Test
    fun `isQName handles edge cases`() {
        // Empty string
        assertFalse(QNameResolver.isQName(""))
        
        // Single colon
        assertTrue(QNameResolver.isQName("a:b"))
        
        // Multiple colons (should still be considered QName if not a full IRI)
        assertTrue(QNameResolver.isQName("prefix:local:name"))
        
        // Starts with colon
        assertFalse(QNameResolver.isQName(":localName"))
        
        // Ends with colon
        assertFalse(QNameResolver.isQName("prefix:"))
    }

    @Test
    fun `extractPrefixMappings extracts mappings from annotations`() {
        // This test would require mock annotation objects
        // For now, we'll test the logic with empty list
        val emptyMappings = QNameResolver.extractPrefixMappings(emptyList())
        assertTrue(emptyMappings.isEmpty())
    }

    @Test
    fun `resolveQName handles complex QNames`() {
        val prefixMappings = mapOf(
            "ex" to "http://example.org/vocab#",
            "schema" to "http://schema.org/",
            "geo" to "http://www.w3.org/2003/01/geo/wgs84_pos#"
        )

        assertEquals("http://example.org/vocab#Location", QNameResolver.resolveQName("ex:Location", prefixMappings))
        assertEquals("http://schema.org/Person", QNameResolver.resolveQName("schema:Person", prefixMappings))
        assertEquals("http://www.w3.org/2003/01/geo/wgs84_pos#lat", QNameResolver.resolveQName("geo:lat", prefixMappings))
    }

    @Test
    fun `resolveQName handles QNames with special characters`() {
        val prefixMappings = mapOf(
            "ex" to "http://example.org/vocab#",
            "dc" to "http://purl.org/dc/elements/1.1/"
        )

        assertEquals("http://example.org/vocab#Property-Name", QNameResolver.resolveQName("ex:Property-Name", prefixMappings))
        assertEquals("http://example.org/vocab#Property_Name", QNameResolver.resolveQName("ex:Property_Name", prefixMappings))
        assertEquals("http://purl.org/dc/elements/1.1/title", QNameResolver.resolveQName("dc:title", prefixMappings))
    }

    @Test
    fun `resolveQName preserves case sensitivity`() {
        val prefixMappings = mapOf(
            "DCAT" to "http://www.w3.org/ns/dcat#",
            "dcat" to "http://www.w3.org/ns/dcat#",
            "DCTERMS" to "http://purl.org/dc/terms/"
        )

        assertEquals("http://www.w3.org/ns/dcat#Catalog", QNameResolver.resolveQName("DCAT:Catalog", prefixMappings))
        assertEquals("http://www.w3.org/ns/dcat#Catalog", QNameResolver.resolveQName("dcat:Catalog", prefixMappings))
        assertEquals("http://purl.org/dc/terms/Title", QNameResolver.resolveQName("DCTERMS:Title", prefixMappings))
    }
}












