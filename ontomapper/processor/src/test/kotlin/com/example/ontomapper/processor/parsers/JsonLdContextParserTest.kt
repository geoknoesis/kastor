package com.example.ontomapper.processor.parsers

import com.example.ontomapper.processor.model.JsonLdContext
import com.example.ontomapper.processor.model.JsonLdProperty
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class JsonLdContextParserTest {

    private lateinit var logger: KSPLogger
    private lateinit var parser: JsonLdContextParser

    @BeforeEach
    fun setup() {
        logger = object : KSPLogger {
            override fun logging(msg: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun info(msg: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun warn(msg: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun error(msg: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun exception(e: Throwable) {}
        }
        parser = JsonLdContextParser(logger)
    }

    @Test
    fun `parseContext parses simple DCAT context`() {
        val contextContent = """
            {
              "@context": {
                "dcat": "http://www.w3.org/ns/dcat#",
                "dcterms": "http://purl.org/dc/terms/",
                "foaf": "http://xmlns.com/foaf/0.1/",
                "xsd": "http://www.w3.org/2001/XMLSchema#",
                
                "Catalog": "dcat:Catalog",
                "Dataset": "dcat:Dataset",
                "Distribution": "dcat:Distribution",
                "Agent": "foaf:Agent",
                
                "title": {
                  "@id": "dcterms:title",
                  "@type": "xsd:string"
                },
                "description": {
                  "@id": "dcterms:description",
                  "@type": "xsd:string"
                },
                "publisher": {
                  "@id": "dcterms:publisher",
                  "@type": "@id"
                },
                "dataset": {
                  "@id": "dcat:dataset",
                  "@type": "@id"
                }
              }
            }
        """.trimIndent()

        val context = parser.parseContextContent(contextContent)

        // Check prefixes
        assertEquals("http://www.w3.org/ns/dcat#", context.prefixes["dcat"])
        assertEquals("http://purl.org/dc/terms/", context.prefixes["dcterms"])
        assertEquals("http://xmlns.com/foaf/0.1/", context.prefixes["foaf"])
        assertEquals("http://www.w3.org/2001/XMLSchema#", context.prefixes["xsd"])

        // Check type mappings
        assertEquals("http://www.w3.org/ns/dcat#Catalog", context.typeMappings["Catalog"])
        assertEquals("http://www.w3.org/ns/dcat#Dataset", context.typeMappings["Dataset"])
        assertEquals("http://www.w3.org/ns/dcat#Distribution", context.typeMappings["Distribution"])
        assertEquals("http://xmlns.com/foaf/0.1/Agent", context.typeMappings["Agent"])

        // Check property mappings
        assertEquals("http://purl.org/dc/terms/title", context.propertyMappings["title"]!!.id)
        assertEquals("http://www.w3.org/2001/XMLSchema#string", context.propertyMappings["title"]!!.type)

        assertEquals("http://purl.org/dc/terms/description", context.propertyMappings["description"]!!.id)
        assertEquals("http://www.w3.org/2001/XMLSchema#string", context.propertyMappings["description"]!!.type)

        assertEquals("http://purl.org/dc/terms/publisher", context.propertyMappings["publisher"]!!.id)
        assertEquals("@id", context.propertyMappings["publisher"]!!.type)

        assertEquals("http://www.w3.org/ns/dcat#dataset", context.propertyMappings["dataset"]!!.id)
        assertEquals("@id", context.propertyMappings["dataset"]!!.type)
    }

    @Test
    fun `parseContext handles different datatypes`() {
        val contextContent = """
            {
              "@context": {
                "xsd": "http://www.w3.org/2001/XMLSchema#",
                
                "stringProp": {
                  "@id": "http://example.org/stringProp",
                  "@type": "xsd:string"
                },
                "intProp": {
                  "@id": "http://example.org/intProp",
                  "@type": "xsd:int"
                },
                "booleanProp": {
                  "@id": "http://example.org/booleanProp",
                  "@type": "xsd:boolean"
                },
                "doubleProp": {
                  "@id": "http://example.org/doubleProp",
                  "@type": "xsd:double"
                },
                "anyURIProp": {
                  "@id": "http://example.org/anyURIProp",
                  "@type": "xsd:anyURI"
                },
                "noTypeProp": {
                  "@id": "http://example.org/noTypeProp"
                }
              }
            }
        """.trimIndent()

        val context = parser.parseContextContent(contextContent)

        assertEquals("http://www.w3.org/2001/XMLSchema#string", context.propertyMappings["stringProp"]!!.type)
        assertEquals("http://www.w3.org/2001/XMLSchema#int", context.propertyMappings["intProp"]!!.type)
        assertEquals("http://www.w3.org/2001/XMLSchema#boolean", context.propertyMappings["booleanProp"]!!.type)
        assertEquals("http://www.w3.org/2001/XMLSchema#double", context.propertyMappings["doubleProp"]!!.type)
        assertEquals("http://www.w3.org/2001/XMLSchema#anyURI", context.propertyMappings["anyURIProp"]!!.type)
        assertNull(context.propertyMappings["noTypeProp"]!!.type)
    }

    @Test
    fun `parseContext handles object properties`() {
        val contextContent = """
            {
              "@context": {
                "dcat": "http://www.w3.org/ns/dcat#",
                "dcterms": "http://purl.org/dc/terms/",
                
                "dataset": {
                  "@id": "dcat:dataset",
                  "@type": "@id"
                },
                "publisher": {
                  "@id": "dcterms:publisher",
                  "@type": "@id"
                },
                "distribution": {
                  "@id": "dcat:distribution",
                  "@type": "@id"
                }
              }
            }
        """.trimIndent()

        val context = parser.parseContextContent(contextContent)

        assertEquals("http://www.w3.org/ns/dcat#dataset", context.propertyMappings["dataset"]!!.id)
        assertEquals("@id", context.propertyMappings["dataset"]!!.type)

        assertEquals("http://purl.org/dc/terms/publisher", context.propertyMappings["publisher"]!!.id)
        assertEquals("@id", context.propertyMappings["publisher"]!!.type)

        assertEquals("http://www.w3.org/ns/dcat#distribution", context.propertyMappings["distribution"]!!.id)
        assertEquals("@id", context.propertyMappings["distribution"]!!.type)
    }

    @Test
    fun `parseContext handles mixed prefix and property definitions`() {
        val contextContent = """
            {
              "@context": {
                "dcat": "http://www.w3.org/ns/dcat#",
                "dcterms": "http://purl.org/dc/terms/",
                
                "Catalog": "dcat:Catalog",
                "Dataset": "dcat:Dataset",
                
                "title": {
                  "@id": "dcterms:title",
                  "@type": "xsd:string"
                },
                "description": {
                  "@id": "dcterms:description"
                }
              }
            }
        """.trimIndent()

        val context = parser.parseContextContent(contextContent)

        // Should have prefixes
        assertEquals("http://www.w3.org/ns/dcat#", context.prefixes["dcat"])
        assertEquals("http://purl.org/dc/terms/", context.prefixes["dcterms"])

        // Should have type mappings
        assertEquals("http://www.w3.org/ns/dcat#Catalog", context.typeMappings["Catalog"])
        assertEquals("http://www.w3.org/ns/dcat#Dataset", context.typeMappings["Dataset"])

        // Should have property mappings
        assertEquals("http://purl.org/dc/terms/title", context.propertyMappings["title"]!!.id)
        assertEquals("http://purl.org/dc/terms/description", context.propertyMappings["description"]!!.id)
    }

    @Test
    fun `parseContext handles empty context`() {
        val contextContent = """
            {
              "@context": {}
            }
        """.trimIndent()

        val context = parser.parseContextContent(contextContent)

        assertTrue(context.prefixes.isEmpty())
        assertTrue(context.typeMappings.isEmpty())
        assertTrue(context.propertyMappings.isEmpty())
    }

    @Test
    fun `parseContext handles context with only prefixes`() {
        val contextContent = """
            {
              "@context": {
                "dcat": "http://www.w3.org/ns/dcat#",
                "dcterms": "http://purl.org/dc/terms/",
                "foaf": "http://xmlns.com/foaf/0.1/"
              }
            }
        """.trimIndent()

        val context = parser.parseContextContent(contextContent)

        assertEquals(3, context.prefixes.size)
        assertEquals("http://www.w3.org/ns/dcat#", context.prefixes["dcat"])
        assertEquals("http://purl.org/dc/terms/", context.prefixes["dcterms"])
        assertEquals("http://xmlns.com/foaf/0.1/", context.prefixes["foaf"])

        assertTrue(context.typeMappings.isEmpty())
        assertTrue(context.propertyMappings.isEmpty())
    }

    @Test
    fun `parseContext handles context with only type mappings`() {
        val contextContent = """
            {
              "@context": {
                "Catalog": "http://www.w3.org/ns/dcat#Catalog",
                "Dataset": "http://www.w3.org/ns/dcat#Dataset"
              }
            }
        """.trimIndent()

        val context = parser.parseContextContent(contextContent)

        assertTrue(context.prefixes.isEmpty())
        assertEquals(2, context.typeMappings.size)
        assertEquals("http://www.w3.org/ns/dcat#Catalog", context.typeMappings["Catalog"])
        assertEquals("http://www.w3.org/ns/dcat#Dataset", context.typeMappings["Dataset"])
        assertTrue(context.propertyMappings.isEmpty())
    }

    @Test
    fun `parseContext handles malformed JSON gracefully`() {
        val contextContent = """
            {
              "@context": {
                "dcat": "http://www.w3.org/ns/dcat#",
                "title": {
                  "@id": "dcterms:title"
                  // Missing comma and closing brace
                }
              }
            }
        """.trimIndent()

        assertThrows(Exception::class.java) {
            parser.parseContextContent(contextContent)
        }
    }

    @Test
    fun `parseContext handles missing @context`() {
        val contextContent = """
            {
              "someOtherProperty": "value"
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            parser.parseContextContent(contextContent)
        }
    }

    @Test
    fun `parseContext from input stream works`() {
        val contextContent = """
            {
              "@context": {
                "dcat": "http://www.w3.org/ns/dcat#",
                "dcterms": "http://purl.org/dc/terms/",
                "xsd": "http://www.w3.org/2001/XMLSchema#",
                "title": {
                  "@id": "dcterms:title",
                  "@type": "xsd:string"
                }
              }
            }
        """.trimIndent()

        val inputStream = ByteArrayInputStream(contextContent.toByteArray())
        val context = parser.parseContext(inputStream)

        assertEquals("http://www.w3.org/ns/dcat#", context.prefixes["dcat"])
        assertEquals("http://purl.org/dc/terms/title", context.propertyMappings["title"]!!.id)
        assertEquals("http://www.w3.org/2001/XMLSchema#string", context.propertyMappings["title"]!!.type)
    }

    @Test
    fun `parseContext handles complex nested properties`() {
        val contextContent = """
            {
              "@context": {
                "dcat": "http://www.w3.org/ns/dcat#",
                "dcterms": "http://purl.org/dc/terms/",
                "xsd": "http://www.w3.org/2001/XMLSchema#",
                
                "downloadURL": {
                  "@id": "dcat:downloadURL",
                  "@type": "xsd:anyURI"
                },
                "mediaType": {
                  "@id": "dcat:mediaType",
                  "@type": "xsd:string"
                },
                "format": {
                  "@id": "dcterms:format",
                  "@type": "xsd:string"
                },
                "keyword": {
                  "@id": "dcterms:keyword",
                  "@type": "xsd:string"
                }
              }
            }
        """.trimIndent()

        val context = parser.parseContextContent(contextContent)

        assertEquals("http://www.w3.org/ns/dcat#downloadURL", context.propertyMappings["downloadURL"]!!.id)
        assertEquals("http://www.w3.org/2001/XMLSchema#anyURI", context.propertyMappings["downloadURL"]!!.type)

        assertEquals("http://www.w3.org/ns/dcat#mediaType", context.propertyMappings["mediaType"]!!.id)
        assertEquals("http://www.w3.org/2001/XMLSchema#string", context.propertyMappings["mediaType"]!!.type)

        assertEquals("http://purl.org/dc/terms/format", context.propertyMappings["format"]!!.id)
        assertEquals("http://www.w3.org/2001/XMLSchema#string", context.propertyMappings["format"]!!.type)

        assertEquals("http://purl.org/dc/terms/keyword", context.propertyMappings["keyword"]!!.id)
        assertEquals("http://www.w3.org/2001/XMLSchema#string", context.propertyMappings["keyword"]!!.type)
    }
}
