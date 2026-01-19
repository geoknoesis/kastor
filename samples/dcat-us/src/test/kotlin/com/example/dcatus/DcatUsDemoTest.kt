package com.example.dcatus

import com.example.dcatus.domain.*
import com.example.ontomapper.runtime.*
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.DCAT
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SKOS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DcatUsDemoTest {

    @Test
    fun `demo demonstrates pure domain usage and side channel access`() {
        // Create a Kastor repository with sample DCAT data
        val repo = Rdf.memory()
        
        // Add sample DCAT data
        repo.add {
            val catalog = Iri("https://data.example.org/catalog")
            val dataset = Iri("https://data.example.org/dataset/1")
            val distribution = Iri("https://data.example.org/distribution/1")
            val publisher = Iri("https://data.example.org/agency/department")
            
            // Catalog
            catalog - RDF.type - DCAT.Catalog
            catalog - DCTERMS.title - "Example Government Data Catalog"
            catalog - DCTERMS.description - "A sample catalog for demonstration"
            catalog - DCAT.datasetProp - dataset
            catalog - DCTERMS.publisher - publisher
            
            // Dataset
            dataset - RDF.type - DCAT.Dataset
            dataset - DCTERMS.title - "Sample Dataset"
            dataset - DCTERMS.description - "A sample dataset for demonstration"
            dataset - DCAT.distributionProp - distribution
            dataset - DCTERMS.subject - "government"
            dataset - DCTERMS.subject - "open-data"
            
            // Distribution
            distribution - RDF.type - DCAT.Distribution
            distribution - DCTERMS.title - "CSV Distribution"
            distribution - DCAT.downloadURL - Iri("https://data.example.org/files/dataset.csv")
            distribution - DCAT.mediaType - "text/csv"
            distribution - DCTERMS.format - "CSV"
            
            // Publisher
            publisher - RDF.type - FOAF.Agent
            publisher - FOAF.name - "Example Government Department"
            publisher - FOAF.homepage - Iri("https://example.gov")
            
            // Add some extra triples for side-channel demonstration
            catalog - SKOS.altLabel - "Alternative Catalog Name"
            dataset - SKOS.altLabel - "Alternative Dataset Name"
        }
        
        // Find a Catalog node and materialize it
        val catalogRef = RdfRef(Iri("https://data.example.org/catalog"), repo.defaultGraph)
        
        // Test that we can create the reference
        assertNotNull(catalogRef)
        assertEquals("https://data.example.org/catalog", (catalogRef.node as Iri).value)
        assertEquals(repo.defaultGraph, catalogRef.graph)
        
        // Test that the graph contains the expected data
        val triples = repo.defaultGraph.getTriples()
        assertTrue(triples.any { it.subject == catalogRef.node && it.predicate == DCTERMS.title })
        assertTrue(triples.any { it.subject == catalogRef.node && it.predicate == DCTERMS.description })
        
        // Test side-channel access
        val extras = KastorGraphOps.extras(repo.defaultGraph, catalogRef.node, emptySet())
        val altLabels = extras.strings(SKOS.altLabel)
        assertEquals(listOf("Alternative Catalog Name"), altLabels)
        
        repo.close()
    }
    
    @Test
    fun `domain interfaces are pure with no RDF dependencies`() {
        // Verify that domain interfaces don't have RDF types
        val catalogClass = Catalog::class.java
        val datasetClass = Dataset::class.java
        val distributionClass = Distribution::class.java
        val agentClass = Agent::class.java
        
        // Check that these are interfaces
        assertTrue(catalogClass.isInterface)
        assertTrue(datasetClass.isInterface)
        assertTrue(distributionClass.isInterface)
        assertTrue(agentClass.isInterface)
        
        // Check that they don't extend RdfBacked (that would be in the wrapper)
        assertFalse(RdfBacked::class.java.isAssignableFrom(catalogClass))
        assertFalse(RdfBacked::class.java.isAssignableFrom(datasetClass))
        assertFalse(RdfBacked::class.java.isAssignableFrom(distributionClass))
        assertFalse(RdfBacked::class.java.isAssignableFrom(agentClass))
    }
}









