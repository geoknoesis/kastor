package com.example.ontomapper.processor

import com.example.ontomapper.processor.model.ClassModel
import com.example.ontomapper.processor.model.PropertyModel
import com.example.ontomapper.processor.model.PropertyType
import com.example.ontomapper.processor.utils.QNameResolver
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PrefixMappingProcessorTest {

    private lateinit var logger: KSPLogger

    @BeforeEach
    fun setup() {
        logger = object : KSPLogger {
            override fun logging(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun info(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun warn(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun error(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun exception(e: Throwable) {}
        }
    }

    @Test
    fun `QNameResolver resolves QNames correctly`() {
        val prefixMappings = mapOf(
            "dcat" to "http://www.w3.org/ns/dcat#",
            "dcterms" to "http://purl.org/dc/terms/",
            "foaf" to "http://xmlns.com/foaf/0.1/"
        )

        // Test QName resolution
        assertEquals("http://www.w3.org/ns/dcat#Catalog", QNameResolver.resolveQName("dcat:Catalog", prefixMappings))
        assertEquals("http://purl.org/dc/terms/title", QNameResolver.resolveQName("dcterms:title", prefixMappings))
        assertEquals("http://xmlns.com/foaf/0.1/name", QNameResolver.resolveQName("foaf:name", prefixMappings))

        // Test full IRI passthrough
        val fullIri = "http://www.w3.org/ns/dcat#Catalog"
        assertEquals(fullIri, QNameResolver.resolveQName(fullIri, prefixMappings))

        // Test unknown prefix
        assertThrows(IllegalArgumentException::class.java) {
            QNameResolver.resolveQName("unknown:Catalog", prefixMappings)
        }
    }

    @Test
    fun `QNameResolver identifies QNames correctly`() {
        // Valid QNames
        assertTrue(QNameResolver.isQName("dcat:Catalog"))
        assertTrue(QNameResolver.isQName("dcterms:title"))
        assertTrue(QNameResolver.isQName("ex:customProperty"))

        // Not QNames (full IRIs)
        assertFalse(QNameResolver.isQName("http://www.w3.org/ns/dcat#Catalog"))
        assertFalse(QNameResolver.isQName("https://example.org/resource"))
        assertFalse(QNameResolver.isQName("urn:uuid:12345678-1234-1234-1234-123456789012"))

        // Edge cases
        assertFalse(QNameResolver.isQName(""))
        assertFalse(QNameResolver.isQName("simpleName"))
        assertFalse(QNameResolver.isQName(":localName"))
        assertFalse(QNameResolver.isQName("prefix:"))
    }

    @Test
    fun `ClassModel includes classIri`() {
        val classModel = ClassModel(
            qualifiedName = "com.example.Catalog",
            simpleName = "Catalog",
            packageName = "com.example",
            classIri = "http://www.w3.org/ns/dcat#Catalog",
            properties = emptyList()
        )

        assertEquals("com.example.Catalog", classModel.qualifiedName)
        assertEquals("Catalog", classModel.simpleName)
        assertEquals("com.example", classModel.packageName)
        assertEquals("http://www.w3.org/ns/dcat#Catalog", classModel.classIri)
        assertTrue(classModel.properties.isEmpty())
    }

    @Test
    fun `PropertyModel works with resolved IRIs`() {
        val propertyModel = PropertyModel(
            name = "title",
            kotlinType = "String",
            predicateIri = "http://purl.org/dc/terms/title",
            type = PropertyType.LITERAL
        )

        assertEquals("title", propertyModel.name)
        assertEquals("String", propertyModel.kotlinType)
        assertEquals("http://purl.org/dc/terms/title", propertyModel.predicateIri)
        assertEquals(PropertyType.LITERAL, propertyModel.type)
    }

    @Test
    fun `prefix mappings work with complex scenarios`() {
        val prefixMappings = mapOf(
            "dcat" to "http://www.w3.org/ns/dcat#",
            "dcterms" to "http://purl.org/dc/terms/",
            "foaf" to "http://xmlns.com/foaf/0.1/",
            "skos" to "http://www.w3.org/2004/02/skos/core#",
            "ex" to "http://example.org/vocab#",
            "schema" to "http://schema.org/",
            "geo" to "http://www.w3.org/2003/01/geo/wgs84_pos#"
        )

        // Test various QName resolutions
        val testCases = mapOf(
            "dcat:Catalog" to "http://www.w3.org/ns/dcat#Catalog",
            "dcterms:title" to "http://purl.org/dc/terms/title",
            "foaf:name" to "http://xmlns.com/foaf/0.1/name",
            "skos:altLabel" to "http://www.w3.org/2004/02/skos/core#altLabel",
            "ex:Location" to "http://example.org/vocab#Location",
            "schema:Person" to "http://schema.org/Person",
            "geo:lat" to "http://www.w3.org/2003/01/geo/wgs84_pos#lat"
        )

        testCases.forEach { (qname, expectedIri) ->
            assertEquals(expectedIri, QNameResolver.resolveQName(qname, prefixMappings))
        }
    }

    @Test
    fun `prefix mappings handle case sensitivity`() {
        val prefixMappings = mapOf(
            "DCAT" to "http://www.w3.org/ns/dcat#",
            "dcat" to "http://www.w3.org/ns/dcat#",
            "DCTERMS" to "http://purl.org/dc/terms/"
        )

        // Case-sensitive matching
        assertEquals("http://www.w3.org/ns/dcat#Catalog", QNameResolver.resolveQName("DCAT:Catalog", prefixMappings))
        assertEquals("http://www.w3.org/ns/dcat#Catalog", QNameResolver.resolveQName("dcat:Catalog", prefixMappings))
        assertEquals("http://purl.org/dc/terms/title", QNameResolver.resolveQName("DCTERMS:title", prefixMappings))

        // Case-sensitive failure
        assertThrows(IllegalArgumentException::class.java) {
            QNameResolver.resolveQName("Dcat:Catalog", prefixMappings)
        }
    }

    @Test
    fun `prefix mappings handle special characters`() {
        val prefixMappings = mapOf(
            "ex" to "http://example.org/vocab#",
            "dc" to "http://purl.org/dc/elements/1.1/",
            "rdf" to "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        )

        // Test QNames with special characters
        val testCases = mapOf(
            "ex:Property-Name" to "http://example.org/vocab#Property-Name",
            "ex:Property_Name" to "http://example.org/vocab#Property_Name",
            "ex:Property.Name" to "http://example.org/vocab#Property.Name",
            "dc:title" to "http://purl.org/dc/elements/1.1/title",
            "rdf:type" to "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
        )

        testCases.forEach { (qname, expectedIri) ->
            assertEquals(expectedIri, QNameResolver.resolveQName(qname, prefixMappings))
        }
    }

    @Test
    fun `prefix mappings handle empty and edge cases`() {
        val emptyMappings = emptyMap<String, String>()
        val singleMapping = mapOf("ex" to "http://example.org/vocab#")

        // Empty mappings
        assertThrows(IllegalArgumentException::class.java) {
            QNameResolver.resolveQName("ex:Property", emptyMappings)
        }

        // Single mapping
        assertEquals("http://example.org/vocab#Property", QNameResolver.resolveQName("ex:Property", singleMapping))

        // Empty string (not a QName, should return as-is)
        assertEquals("", QNameResolver.resolveQName("", singleMapping))

        // QName without colon
        assertEquals("simpleName", QNameResolver.resolveQName("simpleName", singleMapping))
    }
}
