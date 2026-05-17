package com.geoknoesis.kastor.rdf.reasoning

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BasicReasoningTest {
    
    @Test
    fun `reasoner registry discovers providers`() {
        val providers = ReasonerRegistry.discoverProviders()
        assertTrue(providers.isNotEmpty(), "Should discover at least the memory reasoner")
        
        val memoryProvider = providers.find { it.getType() == "memory" }
        assertNotNull(memoryProvider, "Should find memory reasoner provider")
        assertEquals("Memory RDFS Reasoner", memoryProvider!!.name)
    }
    
    @Test
    fun `memory reasoner performs basic RDFS inference`() {
        val graph = createSampleGraph()
        val reasoner = RdfReasoning.reasoner(ReasonerType.RDFS)
        
        val result = reasoner.reason(graph)
        
        assertNotNull(result)
        assertTrue(result.inferredTriples.isNotEmpty(), "Should infer some triples")
        assertTrue(result.consistencyCheck.isConsistent, "Graph should be consistent")
        assertNotNull(result.classification, "Should provide classification")
        assertTrue(result.reasoningTime.toMillis() >= 0, "Should have valid reasoning time")
    }
    
    @Test
    fun `reasoner configuration presets work`() {
        val graph = createSampleGraph()
        
        val configs = listOf(
            ReasonerConfig.default(),
            ReasonerConfig.rdfs(),
            ReasonerConfig.forLargeGraphs(),
            ReasonerConfig.forMemoryConstrained()
        )
        
        configs.forEach { config ->
                    val reasoner = RdfReasoning.reasoner(config)
            val result = reasoner.reason(graph)
            
            assertNotNull(result)
            assertTrue(result.consistencyCheck.isConsistent)
        }
    }
    
    @Test
    fun `reasoner capabilities are correctly reported`() {
        val providers = ReasonerRegistry.getProviders()
        
        providers.forEach { provider ->
            val capabilities = provider.getCapabilities()
            assertNotNull(capabilities)
            assertTrue(capabilities.supportedTypes.isNotEmpty())
            assertNotNull(capabilities.typicalPerformance)
        }
    }
    
    @Test
    fun `reasoner supports different types`() {
        val supportedTypes = ReasonerRegistry.getSupportedTypes()
        assertTrue(supportedTypes.isNotEmpty(), "Should support at least RDFS")
        
        assertTrue(ReasonerRegistry.isSupported(ReasonerType.RDFS), "Should support RDFS")
    }
    
    @Test
    fun `classification result contains expected information`() {
        val graph = createSampleGraph()
        val reasoner = RdfReasoning.reasoner(ReasonerConfig.rdfs())
        
        val result = reasoner.reason(graph)
        val classification = result.classification
        
        assertNotNull(classification, "Should provide classification")
        
        // Should have class hierarchy information
        assertTrue(classification!!.classHierarchy.isNotEmpty() || 
                  classification.instanceClassifications.isNotEmpty() ||
                  classification.propertyHierarchy.isNotEmpty(),
                  "Should have some classification information")
    }
    
    @Test
    fun `reasoner statistics are meaningful`() {
        val graph = createSampleGraph()
        val reasoner = RdfReasoning.reasoner(ReasonerType.RDFS)
        
        val result = reasoner.reason(graph)
        val stats = result.statistics
        
        assertTrue(stats.totalTriples > 0, "Should have original triples")
        assertTrue(stats.inferredTriples >= 0, "Should have non-negative inferred triples")
        assertTrue(stats.memoryUsage > 0, "Should report memory usage")
        assertTrue(stats.cpuTime.toMillis() >= 0, "Should have valid CPU time")
    }
    
    @Test
    fun `consistency checking works`() {
        val graph = createSampleGraph()
        val reasoner = RdfReasoning.reasoner(ReasonerType.RDFS)
        
        val isConsistent = reasoner.isConsistent(graph)
        assertTrue(isConsistent, "Sample graph should be consistent")
        
        val result = reasoner.reason(graph)
        assertEquals(isConsistent, result.consistencyCheck.isConsistent,
                    "Direct consistency check should match result consistency")
    }
    
    @Test
    fun `validation report is generated`() {
        val graph = createSampleGraph()
        val reasoner = RdfReasoning.reasoner(ReasonerType.RDFS)
        
        val report = reasoner.validateOntology(graph)
        
        assertNotNull(report)
        assertTrue(report.isValid, "Sample graph should be valid")
        assertTrue(report.violations.isEmpty(), "Should have no violations")
        assertNotNull(report.statistics)
        assertTrue(report.statistics.validationTime.toMillis() >= 0)
    }
    
    private fun createSampleGraph(): RdfGraph {
        return Rdf.graph {
            prefixes {
                put("ex", "http://example.org/")
            }
            
            // Define class hierarchy
            val person = Iri("ex:Person")
            val student = Iri("ex:Student")
            val teacher = Iri("ex:Teacher")
            
            person - RDFS.subClassOf - Iri("rdfs:Resource")
            student - RDFS.subClassOf - person
            teacher - RDFS.subClassOf - person
            
            // Define property hierarchy
            val knows = Iri("ex:knows")
            val teaches = Iri("ex:teaches")
            
            knows - RDFS.subPropertyOf - Iri("rdfs:seeAlso")
            teaches - RDFS.subPropertyOf - knows
            
            // Domain and range
            knows - RDFS.domain - person
            knows - RDFS.range - person
            
            // Instances
            val alice = Iri("ex:alice")
            val bob = Iri("ex:bob")
            
            alice - RDF.type - student
            bob - RDF.type - teacher
            alice - teaches - bob
        }
    }
}









