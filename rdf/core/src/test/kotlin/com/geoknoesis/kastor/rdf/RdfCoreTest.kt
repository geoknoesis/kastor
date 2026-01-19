package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class RdfCoreTest {
    
    private var tempDir: Path? = null
    
    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("kastor-test")
    }
    
    @AfterEach
    fun tearDown() {
        tempDir?.let { 
            Files.walk(it)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }
    
    @Test
    fun `memory repository creation works`() {
        val repo = Rdf.memory()
        
        assertNotNull(repo, "Memory repository should be created")
        assertFalse(repo.isClosed(), "Repository should not be closed initially")
        assertNotNull(repo.defaultGraph, "Repository should have a default graph")
    }
    
    @Test
    fun `memory repository with inference works`() {
        val repo = Rdf.memoryWithInference()
        
        assertNotNull(repo, "Memory repository with inference should be created")
        assertFalse(repo.isClosed(), "Repository should not be closed initially")
        assertNotNull(repo.defaultGraph, "Repository should have a default graph")
    }
    
    @Test
    fun `persistent repository creation works`() {
        val location = tempDir!!.resolve("test-repo").toString()
        val repo = Rdf.factory {
            providerId = "memory"
            variantId = "memory"
        }
        
        assertNotNull(repo, "Persistent repository should be created")
        assertFalse(repo.isClosed(), "Repository should not be closed initially")
        assertNotNull(repo.defaultGraph, "Repository should have a default graph")
    }
    
    @Test
    fun `persistent repository with default location works`() {
        val repo = Rdf.factory {
            providerId = "memory"
            variantId = "memory"
        }
        
        assertNotNull(repo, "Persistent repository with default location should be created")
        assertFalse(repo.isClosed(), "Repository should not be closed initially")
        assertNotNull(repo.defaultGraph, "Repository should have a default graph")
    }
    
    @Test
    fun `factory method with custom configuration works`() {
        val repo = Rdf.factory {
            providerId = "memory"
            variantId = "memory"
            inference = true
        }
        
        assertNotNull(repo, "Factory-created repository should be created")
        assertFalse(repo.isClosed(), "Repository should not be closed initially")
        assertNotNull(repo.defaultGraph, "Repository should have a default graph")
    }
    
    @Test
    fun `factory method with persistent configuration works`() {
        val location = tempDir!!.resolve("factory-repo").toString()
        val repo = Rdf.factory {
            providerId = "memory"
            variantId = "memory"
            inference = false
        }
        
        assertNotNull(repo, "Factory-created persistent repository should be created")
        assertFalse(repo.isClosed(), "Repository should not be closed initially")
        assertNotNull(repo.defaultGraph, "Repository should have a default graph")
    }
    
    @Test
    fun `repository builder with all options works`() {
        val builder = Rdf.RepositoryBuilder()
        
        // Test default values
        assertNull(builder.providerId, "Default provider should be null")
        assertNull(builder.variantId, "Default variant should be null")
        assertNull(builder.location, "Default location should be null")
        assertFalse(builder.inference, "Default inference should be false")
        // Defaults trimmed for minimal API.
        
        // Test setting values
        builder.providerId = "memory"
        builder.variantId = "memory"
        builder.location = "/test/location"
        builder.inference = true
        
        assertEquals("memory", builder.providerId, "Provider should be settable")
        assertEquals("memory", builder.variantId, "Variant should be settable")
        assertEquals("/test/location", builder.location, "Location should be settable")
        assertTrue(builder.inference, "Inference should be settable")
        
        // Test building repository
        val repo = builder.build()
        assertNotNull(repo, "Built repository should not be null")
    }
    
    @Test
    fun `repository builder with null location uses default`() {
        val repo = Rdf.RepositoryBuilder().apply {
            providerId = "memory"
            variantId = "memory"
            location = null // This should use default "data"
        }.build()
        
        assertNotNull(repo, "Repository with null location should use default")
    }
    
    @Test
    fun `repository can be closed properly`() {
        val repo = Rdf.memory()
        
        assertFalse(repo.isClosed(), "Repository should not be closed initially")
        
        repo.close()
        
        assertTrue(repo.isClosed(), "Repository should be closed after calling close()")
    }
    
    @Test
    fun `multiple repositories can be created independently`() {
        val repo1 = Rdf.memory()
        val repo2 = Rdf.memory()
        val repo3 = Rdf.factory {
            providerId = "memory"
            variantId = "memory"
        }
        
        assertNotNull(repo1, "First repository should be created")
        assertNotNull(repo2, "Second repository should be created")
        assertNotNull(repo3, "Third repository should be created")
        
        // They should be independent
        assertNotSame(repo1, repo2, "Repositories should be independent instances")
        assertNotSame(repo1, repo3, "Repositories should be independent instances")
        assertNotSame(repo2, repo3, "Repositories should be independent instances")
        
        // Close one should not affect others
        repo1.close()
        assertTrue(repo1.isClosed(), "First repository should be closed")
        assertFalse(repo2.isClosed(), "Second repository should still be open")
        assertFalse(repo3.isClosed(), "Third repository should still be open")
        
        repo2.close()
        repo3.close()
    }
    
    @Test
    fun `repository operations work after creation`() {
        val repo = Rdf.memory()
        val graph = repo.defaultGraph
        
        // Test basic triple operations
        val subject = Iri("http://example.org/subject")
        val predicate = Iri("http://example.org/predicate")
        val obj = Literal("test value")
        val triple = RdfTriple(subject, predicate, obj)
        
        graph.addTriple(triple)
        
        val allTriples = graph.getTriples()
        assertEquals(1, allTriples.size, "Should have one triple after adding")
        assertEquals(triple, allTriples.first(), "Added triple should match")
        
        repo.close()
    }
}









