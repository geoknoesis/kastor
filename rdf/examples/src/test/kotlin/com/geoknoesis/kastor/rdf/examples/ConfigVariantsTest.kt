package com.geoknoesis.kastor.rdf.examples

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ConfigVariantsTest {
    
    @Test
    fun `all configuration variants are supported`() {
        val allSupportedTypes = RdfProviderRegistry.getSupportedTypes()
        
        println("All supported configuration variants:")
        allSupportedTypes.forEach { type ->
            println("  - $type")
        }
        
        // Test that each type is supported
        allSupportedTypes.forEach { type ->
            val parts = type.split(":", limit = 2)
            assertEquals(2, parts.size, "Type $type should include provider and variant")
            assertTrue(
                RdfProviderRegistry.supportsVariant(parts[0], parts[1]),
                "Type $type should be supported"
            )
        }
        
        // With Jena, RDF4J, and SPARQL on the test classpath, ServiceLoader must
        // discover all three providers in addition to the built-in memory provider.
        val expectedTypes = listOf(
            "memory:memory",
            "jena:memory",
            "rdf4j:memory",
            "sparql:sparql"
        )
        
        expectedTypes.forEach { expectedType ->
            assertTrue(
                allSupportedTypes.contains(expectedType),
                "Expected type $expectedType should be in supported types: $allSupportedTypes"
            )
        }
    }
    
    @Test
    fun `jena variants work correctly`() {
        val tempDir = Files.createTempDirectory("kastor-jena-tdb2").toFile()
        try {
            val jenaVariants = listOf(
                "memory",
                "memory-inference",
                "tdb2",
                "tdb2-inference"
            )

            jenaVariants.forEach { variant ->
                println("Testing Jena variant: jena:$variant")

                val options = if (variant.contains("tdb2")) {
                    mapOf("location" to java.io.File(tempDir, variant).absolutePath)
                } else {
                    emptyMap()
                }
                val config = RdfConfig(
                    providerId = "jena",
                    variantId = variant,
                    options = options
                )

                val repo = RdfProviderRegistry.create(config)
                try {
                    assertNotNull(repo, "Repository should be created for jena:$variant")
                    assertFalse(repo.isClosed(), "Repository should not be closed")
                    assertNotNull(repo.defaultGraph, "Repository should have a default graph")
                } finally {
                    repo.close()
                }

                println("  ✓ Successfully created repository for jena:$variant")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    
    @Test
    fun `rdf4j variants work correctly`() {
        val tempDir = Files.createTempDirectory("kastor-rdf4j-native").toFile()
        try {
            val rdf4jVariants = listOf(
                "memory",
                "native",
                "memory-star",
                "native-star",
                "memory-rdfs",
                "native-rdfs",
                "memory-shacl",
                "native-shacl"
            )

            rdf4jVariants.forEach { variant ->
                println("Testing RDF4J variant: rdf4j:$variant")

                val options = if (variant.contains("native")) {
                    mapOf("location" to java.io.File(tempDir, variant).absolutePath)
                } else {
                    emptyMap()
                }
                val config = RdfConfig(
                    providerId = "rdf4j",
                    variantId = variant,
                    options = options
                )

                val repo = RdfProviderRegistry.create(config)
                try {
                    assertNotNull(repo, "Repository should be created for rdf4j:$variant")
                    assertFalse(repo.isClosed(), "Repository should not be closed")
                    assertNotNull(repo.defaultGraph, "Repository should have a default graph")
                } finally {
                    repo.close()
                }

                println("  ✓ Successfully created repository for rdf4j:$variant")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    
    @Test
    fun `sparql variant constructs a remote endpoint repository`() {
        // Construction is purely local; we never hit the network in this test.
        val config = RdfConfig(
            providerId = "sparql",
            variantId = "sparql",
            options = mapOf("location" to "http://example.org/sparql")
        )
        val repo = RdfProviderRegistry.create(config)
        try {
            assertNotNull(repo, "Repository should be created for sparql")
            assertFalse(repo.isClosed(), "Repository should not be closed")
            assertNotNull(repo.defaultGraph, "Repository should have a default graph")
        } finally {
            repo.close()
        }
    }
    
    @Test
    fun `memory variant still works`() {
        val repo = RdfProviderRegistry.create(RdfConfig(providerId = "memory", variantId = "memory"))
        
        try {
            assertNotNull(repo, "Memory repository should be created")
            assertFalse(repo.isClosed(), "Repository should not be closed")
            assertNotNull(repo.defaultGraph, "Repository should have a default graph")
            
            // Test basic operations
            val s = Iri("urn:test:s")
            val p = Iri("urn:test:p")
            val o = Literal("test")
            
            repo.editDefaultGraph().addTriple(RdfTriple(s, p, o))
            
            assertTrue(repo.defaultGraph.hasTriple(RdfTriple(s, p, o)))
        } finally {
            repo.close()
        }
    }
}
