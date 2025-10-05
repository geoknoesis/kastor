package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QNameDslTest {
    
    @Test
    fun `TripleDsl resolves QNames with minus operator`() {
        val repo = Rdf.memory()
        
        repo.add {
            prefixes {
                put("foaf", "http://xmlns.com/foaf/0.1/")
                put("dcat", "http://www.w3.org/ns/dcat#")
            }
            
            val person = iri("http://example.org/person")
            person - "foaf:name" - "Alice"
            person - "foaf:age" - 30
            person - "dcat:keyword" - "test"
        }
        
        val triples = repo.getTriples()
        assertEquals(3, triples.size)
        
        assertTrue(triples.any { 
            it.predicate.value == "http://xmlns.com/foaf/0.1/name"
        })
        assertTrue(triples.any { 
            it.predicate.value == "http://xmlns.com/foaf/0.1/age"
        })
        assertTrue(triples.any { 
            it.predicate.value == "http://www.w3.org/ns/dcat#keyword"
        })
    }
    
    @Test
    fun `TripleDsl resolves QNames with bracket syntax`() {
        val repo = Rdf.memory()
        
        repo.add {
            prefix("foaf", "http://xmlns.com/foaf/0.1/")
            
            val person = iri("http://example.org/person")
            person["foaf:name"] = "Bob"
            person["foaf:age"] = 25
        }
        
        val triples = repo.getTriples()
        assertEquals(2, triples.size)
        
        assertTrue(triples.any { 
            it.predicate.value == "http://xmlns.com/foaf/0.1/name"
        })
    }
    
    @Test
    fun `TripleDsl resolves QNames with natural language syntax`() {
        val repo = Rdf.memory()
        
        repo.add {
            prefix("foaf", "http://xmlns.com/foaf/0.1/")
            
            val person = iri("http://example.org/person")
            person has "foaf:name" with "Charlie"
            person has "foaf:age" with 35
        }
        
        val triples = repo.getTriples()
        assertEquals(2, triples.size)
        
        assertTrue(triples.any { 
            it.predicate.value == "http://xmlns.com/foaf/0.1/name"
        })
    }
    
    @Test
    fun `qname function creates IRIs from QNames`() {
        val repo = Rdf.memory()
        
        repo.add {
            prefix("foaf", "http://xmlns.com/foaf/0.1/")
            
            val nameIri = qname("foaf:name")
            assertEquals("http://xmlns.com/foaf/0.1/name", nameIri.value)
            
            val person = iri("http://example.org/person")
            person - nameIri - "David"
        }
        
        val triples = repo.getTriples()
        assertEquals(1, triples.size)
    }
    
    @Test
    fun `GraphDsl resolves QNames correctly`() {
        val graph = Rdf.graph {
            prefixes {
                put("foaf", "http://xmlns.com/foaf/0.1/")
                put("dcat", "http://www.w3.org/ns/dcat#")
            }
            
            val person = iri("http://example.org/person")
            person - "foaf:name" - "Eve"
            person - "foaf:age" - 28
            person - "dcat:keyword" - "example"
        }
        
        assertEquals(3, graph.getTriples().size)
        
        val triples = graph.getTriples()
        assertTrue(triples.any { 
            it.predicate.value == "http://xmlns.com/foaf/0.1/name"
        })
        assertTrue(triples.any { 
            it.predicate.value == "http://xmlns.com/foaf/0.1/age"
        })
        assertTrue(triples.any { 
            it.predicate.value == "http://www.w3.org/ns/dcat#keyword"
        })
    }
    
    @Test
    fun `QNames work with full IRIs fallback`() {
        val repo = Rdf.memory()
        
        repo.add {
            prefix("foaf", "http://xmlns.com/foaf/0.1/")
            
            val person = iri("http://example.org/person")
            person - "foaf:name" - "Frank"
            person - "http://example.org/customProp" - "value"  // Full IRI
        }
        
        val triples = repo.getTriples()
        assertEquals(2, triples.size)
        
        assertTrue(triples.any { 
            it.predicate.value == "http://xmlns.com/foaf/0.1/name"
        })
        assertTrue(triples.any { 
            it.predicate.value == "http://example.org/customProp"
        })
    }
    
    @Test
    fun `multiple prefix mappings work together`() {
        val repo = Rdf.memory()
        
        repo.add {
            prefixes {
                put("foaf", "http://xmlns.com/foaf/0.1/")
                put("dcat", "http://www.w3.org/ns/dcat#")
                put("dcterms", "http://purl.org/dc/terms/")
            }
            
            val catalog = iri("http://example.org/catalog")
            catalog - "dcterms:title" - "My Catalog"
            catalog - "dcterms:description" - "A test catalog"
            catalog - "dcat:dataset" - iri("http://example.org/dataset1")
            
            val person = iri("http://example.org/person")
            person - "foaf:name" - "Grace"
        }
        
        val triples = repo.getTriples()
        assertEquals(4, triples.size)
        
        assertTrue(triples.any { 
            it.predicate.value == "http://purl.org/dc/terms/title"
        })
        assertTrue(triples.any { 
            it.predicate.value == "http://www.w3.org/ns/dcat#dataset"
        })
        assertTrue(triples.any { 
            it.predicate.value == "http://xmlns.com/foaf/0.1/name"
        })
    }
}
