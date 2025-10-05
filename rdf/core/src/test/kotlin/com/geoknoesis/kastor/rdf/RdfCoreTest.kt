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
            type = "memory"
        }
        
        assertNotNull(repo, "Persistent repository should be created")
        assertFalse(repo.isClosed(), "Repository should not be closed initially")
        assertNotNull(repo.defaultGraph, "Repository should have a default graph")
    }
    
    @Test
    fun `persistent repository with default location works`() {
        val repo = Rdf.factory {
            type = "memory"
        }
        
        assertNotNull(repo, "Persistent repository with default location should be created")
        assertFalse(repo.isClosed(), "Repository should not be closed initially")
        assertNotNull(repo.defaultGraph, "Repository should have a default graph")
    }
    
    @Test
    fun `factory method with custom configuration works`() {
        val repo = Rdf.factory {
            type = "memory"
            inference = true
            optimization = false
            cacheSize = 500
            maxMemory = "512MB"
        }
        
        assertNotNull(repo, "Factory-created repository should be created")
        assertFalse(repo.isClosed(), "Repository should not be closed initially")
        assertNotNull(repo.defaultGraph, "Repository should have a default graph")
    }
    
    @Test
    fun `factory method with persistent configuration works`() {
        val location = tempDir!!.resolve("factory-repo").toString()
        val repo = Rdf.factory {
            type = "memory"
            inference = false
            optimization = true
            cacheSize = 2000
            maxMemory = "2GB"
        }
        
        assertNotNull(repo, "Factory-created persistent repository should be created")
        assertFalse(repo.isClosed(), "Repository should not be closed initially")
        assertNotNull(repo.defaultGraph, "Repository should have a default graph")
    }
    
    @Test
    fun `repository manager creation works`() {
        val manager = Rdf.manager {
            // Test basic manager creation
        }
        
        assertNotNull(manager, "Repository manager should be created")
    }
    
    @Test
    fun `default provider management works`() {
        val originalProvider = Rdf.getDefaultProvider()
        
        try {
            // Test setting and getting default provider
            Rdf.setDefaultProvider("jena")
            assertEquals("jena", Rdf.getDefaultProvider(), "Default provider should be set to jena")
            
            Rdf.setDefaultProvider("rdf4j")
            assertEquals("rdf4j", Rdf.getDefaultProvider(), "Default provider should be set to rdf4j")
        } finally {
            // Restore original provider
            Rdf.setDefaultProvider(originalProvider)
        }
    }
    
    @Test
    fun `repository builder with all options works`() {
        val builder = Rdf.RepositoryBuilder()
        
        // Test default values
        assertEquals("memory", builder.type, "Default type should be memory")
        assertNull(builder.location, "Default location should be null")
        assertFalse(builder.inference, "Default inference should be false")
        assertTrue(builder.optimization, "Default optimization should be true")
        assertEquals(1000, builder.cacheSize, "Default cache size should be 1000")
        assertEquals("1GB", builder.maxMemory, "Default max memory should be 1GB")
        
        // Test setting values
        builder.type = "memory"
        builder.location = "/test/location"
        builder.inference = true
        builder.optimization = false
        builder.cacheSize = 1500
        builder.maxMemory = "3GB"
        
        assertEquals("memory", builder.type, "Type should be settable")
        assertEquals("/test/location", builder.location, "Location should be settable")
        assertTrue(builder.inference, "Inference should be settable")
        assertFalse(builder.optimization, "Optimization should be settable")
        assertEquals(1500, builder.cacheSize, "Cache size should be settable")
        assertEquals("3GB", builder.maxMemory, "Max memory should be settable")
        
        // Test building repository
        val repo = builder.build()
        assertNotNull(repo, "Built repository should not be null")
    }
    
    @Test
    fun `repository builder with null location uses default`() {
        val repo = Rdf.RepositoryBuilder().apply {
            type = "memory"
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
        val repo3 = Rdf.factory { type = "memory" }
        
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
        val subject = iri("http://example.org/subject")
        val predicate = iri("http://example.org/predicate")
        val obj = literal("test value")
        val triple = RdfTriple(subject, predicate, obj)
        
        graph.addTriple(triple)
        
        val allTriples = graph.getTriples()
        assertEquals(1, allTriples.size, "Should have one triple after adding")
        assertEquals(triple, allTriples.first(), "Added triple should match")
        
        repo.close()
    }
}
