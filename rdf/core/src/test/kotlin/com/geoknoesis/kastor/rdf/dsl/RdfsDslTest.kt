package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class RdfsDslTest {
    
    @Test
    fun `test basic class definition`() {
        val graph = rdfs {
            `class`("http://example.org/Person") {
                label("Person", "en")
                comment("A human being", "en")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check class type
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Person") &&
            it.predicate == RDF.type &&
            it.obj == RDFS.Class
        })
        
        // Check label
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Person") &&
            it.predicate == RDFS.label &&
            (it.obj as? Literal)?.lexical == "Person"
        })
        
        // Check comment
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Person") &&
            it.predicate == RDFS.comment &&
            (it.obj as? Literal)?.lexical == "A human being"
        })
    }
    
    @Test
    fun `test class with subClassOf`() {
        val graph = rdfs {
            `class`("http://example.org/Person") {
                subClassOf(RDFS.Resource)
            }
            
            `class`("http://example.org/Student") {
                subClassOf("http://example.org/Person")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check Person subClassOf Resource
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Person") &&
            it.predicate == RDFS.subClassOf &&
            it.obj == RDFS.Resource
        })
        
        // Check Student subClassOf Person
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Student") &&
            it.predicate == RDFS.subClassOf &&
            it.obj == Iri("http://example.org/Person")
        })
    }
    
    @Test
    fun `test class with seeAlso and isDefinedBy`() {
        val graph = rdfs {
            `class`("http://example.org/Person") {
                seeAlso("http://example.org/docs/Person")
                isDefinedBy("http://example.org/ontology")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Person") &&
            it.predicate == RDFS.seeAlso &&
            it.obj == Iri("http://example.org/docs/Person")
        })
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Person") &&
            it.predicate == RDFS.isDefinedBy &&
            it.obj == Iri("http://example.org/ontology")
        })
    }
    
    @Test
    fun `test basic property definition`() {
        val graph = rdfs {
            property("http://example.org/name") {
                label("Name", "en")
                comment("The name of a person", "en")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check property type
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/name") &&
            it.predicate == RDF.type &&
            it.obj == RDF.Property
        })
        
        // Check label
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/name") &&
            it.predicate == RDFS.label &&
            (it.obj as? Literal)?.lexical == "Name"
        })
    }
    
    @Test
    fun `test property with domain and range`() {
        val graph = rdfs {
            property("http://example.org/name") {
                domain("http://example.org/Person")
                range(RDFS.Literal)
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check domain
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/name") &&
            it.predicate == RDFS.domain &&
            it.obj == Iri("http://example.org/Person")
        })
        
        // Check range
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/name") &&
            it.predicate == RDFS.range &&
            it.obj == RDFS.Literal
        })
    }
    
    @Test
    fun `test property with multiple domains and ranges`() {
        val graph = rdfs {
            property("http://example.org/name") {
                domains("http://example.org/Person", "http://example.org/Organization")
                ranges(RDFS.Literal, XSD.string)
            }
        }
        
        val triples = graph.getTriples().toList()
        
        val domains = triples.filter {
            it.subject == Iri("http://example.org/name") &&
            it.predicate == RDFS.domain
        }
        assertEquals(2, domains.size)
        
        val ranges = triples.filter {
            it.subject == Iri("http://example.org/name") &&
            it.predicate == RDFS.range
        }
        assertEquals(2, ranges.size)
    }
    
    @Test
    fun `test property with subPropertyOf`() {
        val graph = rdfs {
            property("http://example.org/firstName") {
                subPropertyOf("http://example.org/name")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/firstName") &&
            it.predicate == RDFS.subPropertyOf &&
            it.obj == Iri("http://example.org/name")
        })
    }
    
    @Test
    fun `test datatype definition`() {
        val graph = rdfs {
            datatype("http://example.org/EmailAddress") {
                label("Email Address", "en")
                comment("A valid email address", "en")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check datatype type
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/EmailAddress") &&
            it.predicate == RDF.type &&
            it.obj == RDFS.Datatype
        })
        
        // Check label
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/EmailAddress") &&
            it.predicate == RDFS.label &&
            (it.obj as? Literal)?.lexical == "Email Address"
        })
    }
    
    @Test
    fun `test prefixes`() {
        val graph = rdfs {
            prefix("ex", "http://example.org/")
            
            `class`("ex:Person") {
                label("Person")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Person")
        })
    }
    
    @Test
    fun `test multiple prefixes`() {
        val graph = rdfs {
            prefixes {
                put("ex", "http://example.org/")
                put("foaf", "http://xmlns.com/foaf/0.1/")
            }
            
            `class`("ex:Person") {
                subClassOf("foaf:Person")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Person")
        })
    }
    
    @Test
    fun `test label without language tag`() {
        val graph = rdfs {
            `class`("http://example.org/Person") {
                label("Person")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        val labelTriple = triples.find {
            it.subject == Iri("http://example.org/Person") &&
            it.predicate == RDFS.label
        }
        
        assertNotNull(labelTriple)
        val literal = labelTriple!!.obj as Literal
        assertEquals("Person", literal.lexical)
        assertFalse(literal is LangString)
    }
    
    @Test
    fun `test label with language tag`() {
        val graph = rdfs {
            `class`("http://example.org/Person") {
                label("Person", "en")
                label("Personne", "fr")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        val labels = triples.filter {
            it.subject == Iri("http://example.org/Person") &&
            it.predicate == RDFS.label
        }
        
        assertEquals(2, labels.size)
        
        val englishLabel = labels.find { 
            val lit = it.obj as? LangString
            lit?.lang == "en"
        }
        val frenchLabel = labels.find { 
            val lit = it.obj as? LangString
            lit?.lang == "fr"
        }
        
        assertNotNull(englishLabel)
        assertNotNull(frenchLabel)
        assertEquals("Person", (englishLabel!!.obj as Literal).lexical)
        assertEquals("Personne", (frenchLabel!!.obj as Literal).lexical)
    }
    
    @Test
    fun `test comment without language tag`() {
        val graph = rdfs {
            `class`("http://example.org/Person") {
                comment("A human being")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        val commentTriple = triples.find {
            it.subject == Iri("http://example.org/Person") &&
            it.predicate == RDFS.comment
        }
        
        assertNotNull(commentTriple)
        val literal = commentTriple!!.obj as Literal
        assertEquals("A human being", literal.lexical)
        assertFalse(literal is LangString)
    }
    
    @Test
    fun `test multiple classes`() {
        val graph = rdfs {
            `class`("http://example.org/Person") {
                label("Person")
            }
            
            `class`("http://example.org/Organization") {
                label("Organization")
            }
            
            `class`("http://example.org/Document") {
                label("Document")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        val classes = triples.filter {
            it.predicate == RDF.type &&
            it.obj == RDFS.Class
        }
        
        assertEquals(3, classes.size)
    }
    
    @Test
    fun `test multiple properties`() {
        val graph = rdfs {
            property("http://example.org/name") {
                label("Name")
            }
            
            property("http://example.org/age") {
                label("Age")
            }
            
            property("http://example.org/email") {
                label("Email")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        val properties = triples.filter {
            it.predicate == RDF.type &&
            it.obj == RDF.Property
        }
        
        assertEquals(3, properties.size)
    }
    
    @Test
    fun `test class hierarchy`() {
        val graph = rdfs {
            `class`("http://example.org/Animal") {
                subClassOf(RDFS.Resource)
            }
            
            `class`("http://example.org/Mammal") {
                subClassOf("http://example.org/Animal")
            }
            
            `class`("http://example.org/Dog") {
                subClassOf("http://example.org/Mammal")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check Animal subClassOf Resource
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Animal") &&
            it.predicate == RDFS.subClassOf &&
            it.obj == RDFS.Resource
        })
        
        // Check Mammal subClassOf Animal
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Mammal") &&
            it.predicate == RDFS.subClassOf &&
            it.obj == Iri("http://example.org/Animal")
        })
        
        // Check Dog subClassOf Mammal
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Dog") &&
            it.predicate == RDFS.subClassOf &&
            it.obj == Iri("http://example.org/Mammal")
        })
    }
    
    @Test
    fun `test property hierarchy`() {
        val graph = rdfs {
            property("http://example.org/name") {
                label("Name")
            }
            
            property("http://example.org/firstName") {
                subPropertyOf("http://example.org/name")
            }
            
            property("http://example.org/lastName") {
                subPropertyOf("http://example.org/name")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check firstName subPropertyOf name
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/firstName") &&
            it.predicate == RDFS.subPropertyOf &&
            it.obj == Iri("http://example.org/name")
        })
        
        // Check lastName subPropertyOf name
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/lastName") &&
            it.predicate == RDFS.subPropertyOf &&
            it.obj == Iri("http://example.org/name")
        })
    }
    
    @Test
    fun `test complete class definition`() {
        val graph = rdfs {
            `class`("http://example.org/Person") {
                label("Person", "en")
                label("Personne", "fr")
                comment("A human being", "en")
                comment("Un être humain", "fr")
                subClassOf(RDFS.Resource)
                seeAlso("http://example.org/docs/Person")
                isDefinedBy("http://example.org/ontology")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check all assertions
        assertEquals(1, triples.count { it.predicate == RDF.type && it.obj == RDFS.Class })
        assertEquals(2, triples.count { it.predicate == RDFS.label })
        assertEquals(2, triples.count { it.predicate == RDFS.comment })
        assertEquals(1, triples.count { it.predicate == RDFS.subClassOf })
        assertEquals(1, triples.count { it.predicate == RDFS.seeAlso })
        assertEquals(1, triples.count { it.predicate == RDFS.isDefinedBy })
    }
    
    @Test
    fun `test complete property definition`() {
        val graph = rdfs {
            property("http://example.org/name") {
                label("Name", "en")
                comment("The name of a person", "en")
                domain("http://example.org/Person")
                range(RDFS.Literal)
                subPropertyOf(RDF.value)
                seeAlso("http://example.org/docs/name")
                isDefinedBy("http://example.org/ontology")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        // Check all assertions
        assertEquals(1, triples.count { it.predicate == RDF.type && it.obj == RDF.Property })
        assertEquals(1, triples.count { it.predicate == RDFS.label })
        assertEquals(1, triples.count { it.predicate == RDFS.comment })
        assertEquals(1, triples.count { it.predicate == RDFS.domain })
        assertEquals(1, triples.count { it.predicate == RDFS.range })
        assertEquals(1, triples.count { it.predicate == RDFS.subPropertyOf })
        assertEquals(1, triples.count { it.predicate == RDFS.seeAlso })
        assertEquals(1, triples.count { it.predicate == RDFS.isDefinedBy })
    }
    
    @Test
    fun `test empty graph`() {
        val graph = rdfs {
            // Empty graph
        }
        
        val triples = graph.getTriples().toList()
        assertTrue(triples.isEmpty())
    }
    
    @Test
    fun `test class with IRI object for subClassOf`() {
        val graph = rdfs {
            `class`("http://example.org/Person") {
                subClassOf(Iri("http://example.org/Animal"))
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/Person") &&
            it.predicate == RDFS.subClassOf &&
            it.obj == Iri("http://example.org/Animal")
        })
    }
    
    @Test
    fun `test property with IRI object for domain and range`() {
        val graph = rdfs {
            property("http://example.org/name") {
                domain(Iri("http://example.org/Person"))
                range(Iri("http://example.org/String"))
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/name") &&
            it.predicate == RDFS.domain &&
            it.obj == Iri("http://example.org/Person")
        })
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/name") &&
            it.predicate == RDFS.range &&
            it.obj == Iri("http://example.org/String")
        })
    }
    
    @Test
    fun `test datatype with seeAlso and isDefinedBy`() {
        val graph = rdfs {
            datatype("http://example.org/EmailAddress") {
                seeAlso("http://example.org/docs/EmailAddress")
                isDefinedBy("http://example.org/ontology")
            }
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/EmailAddress") &&
            it.predicate == RDFS.seeAlso &&
            it.obj == Iri("http://example.org/docs/EmailAddress")
        })
        
        assertTrue(triples.any {
            it.subject == Iri("http://example.org/EmailAddress") &&
            it.predicate == RDFS.isDefinedBy &&
            it.obj == Iri("http://example.org/ontology")
        })
    }
    
    @Test
    fun `test direct triple addition`() {
        val person = Iri("http://example.org/Person")
        val graph = rdfs {
            triple(person, RDF.type, RDFS.Class)
            triple(person, RDFS.label, string("Person"))
        }
        
        val triples = graph.getTriples().toList()
        
        assertTrue(triples.any {
            it.subject == person &&
            it.predicate == RDF.type &&
            it.obj == RDFS.Class
        })
        
        assertTrue(triples.any {
            it.subject == person &&
            it.predicate == RDFS.label &&
            (it.obj as? Literal)?.lexical == "Person"
        })
    }
}

