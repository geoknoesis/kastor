package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.RDFS
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RdfExtensionsTest {
    
    @Test
    fun `DSL bracket syntax works`() {
        val repo = Rdf.memory()
        
        val person = Iri("http://example.org/person")
        val name = "John Doe"
        val age = 30
        val email = "john@example.org"
        
        // Test DSL bracket syntax
        repo.add {
            person[FOAF.name] = name
            person[FOAF.age] = age
            person[FOAF.mbox] = email
        }
        
        // Verify triples were added
        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(3, allTriples.size, "Should have 3 triples")
        
        // Find specific triples
        val nameTriple = allTriples.find { it.predicate == FOAF.name }
        val ageTriple = allTriples.find { it.predicate == FOAF.age }
        val emailTriple = allTriples.find { it.predicate == FOAF.mbox }
        
        assertNotNull(nameTriple, "Should have name triple")
        assertNotNull(ageTriple, "Should have age triple")
        assertNotNull(emailTriple, "Should have email triple")
        
        assertEquals(person, nameTriple!!.subject, "Subject should be person")
        assertEquals(name, (nameTriple.obj as Literal).lexical, "Object should be name literal")
        
        repo.close()
    }
    
    @Test
    fun `DSL has-with syntax works`() {
        val repo = Rdf.memory()
        
        val person = Iri("http://example.org/person")
        val friend = Iri("http://example.org/friend")
        
        // Test DSL has-with syntax
        repo.add {
            person has FOAF.name with "Alice"
            person has FOAF.knows with friend
            friend has FOAF.name with "Bob"
        }
        
        // Verify triples were added
        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(3, allTriples.size, "Should have 3 triples")
        
        // Find specific triples
        val nameTriple = allTriples.find { it.predicate == FOAF.name && it.subject == person }
        val knowsTriple = allTriples.find { it.predicate == FOAF.knows }
        val friendNameTriple = allTriples.find { it.predicate == FOAF.name && it.subject == friend }
        
        assertNotNull(nameTriple, "Should have person name triple")
        assertNotNull(knowsTriple, "Should have knows triple")
        assertNotNull(friendNameTriple, "Should have friend name triple")
        
        assertEquals("Alice", (nameTriple!!.obj as Literal).lexical, "Person name should be Alice")
        assertEquals(friend, knowsTriple!!.obj, "Knows object should be friend")
        assertEquals("Bob", (friendNameTriple!!.obj as Literal).lexical, "Friend name should be Bob")
        
        repo.close()
    }
    
    @Test
    fun `DSL with different literal types works`() {
        val repo = Rdf.memory()
        
        val resource = Iri("http://example.org/resource")
        
        // Test DSL with different literal types
        repo.add {
            resource[DCTERMS.title] = "Test Title"
            resource[DCTERMS.extent] = 1000
            resource[DCTERMS.available] = true
        }
        
        // Verify triples were added
        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(3, allTriples.size, "Should have 3 triples")
        
        // Find specific triples
        val titleTriple = allTriples.find { it.predicate == DCTERMS.title }
        val extentTriple = allTriples.find { it.predicate == DCTERMS.extent }
        val availableTriple = allTriples.find { it.predicate == DCTERMS.available }
        
        assertNotNull(titleTriple, "Should have title triple")
        assertNotNull(extentTriple, "Should have extent triple")
        assertNotNull(availableTriple, "Should have available triple")
        
        assertEquals("Test Title", (titleTriple!!.obj as Literal).lexical, "Title should be preserved")
        assertEquals("1000", (extentTriple!!.obj as Literal).lexical, "Extent should be converted to string")
        assertEquals("true", (availableTriple!!.obj as Literal).lexical, "Available should be converted to string")
        
        repo.close()
    }
    
    @Test
    fun `DSL with IRI objects works`() {
        val repo = Rdf.memory()
        
        val person = Iri("http://example.org/person")
        val friend = Iri("http://example.org/friend")
        val creator = Iri("http://example.org/creator")
        
        // Test DSL with IRI objects
        repo.add {
            person has FOAF.knows with friend
            person has DCTERMS.creator with creator
        }
        
        // Verify triples were added
        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(2, allTriples.size, "Should have 2 triples")
        
        // Find specific triples
        val knowsTriple = allTriples.find { it.predicate == FOAF.knows }
        val creatorTriple = allTriples.find { it.predicate == DCTERMS.creator }
        
        assertNotNull(knowsTriple, "Should have knows triple")
        assertNotNull(creatorTriple, "Should have creator triple")
        
        assertEquals(friend, knowsTriple!!.obj, "Knows object should be friend IRI")
        assertEquals(creator, creatorTriple!!.obj, "Creator object should be creator IRI")
        
        repo.close()
    }
    
    @Test
    fun `DSL with blank node objects works`() {
        val repo = Rdf.memory()
        
        val person = Iri("http://example.org/person")
        val bnode = bnode("b1")
        
        // Test DSL with blank node objects
        repo.add {
            person has FOAF.knows with bnode
            bnode has FOAF.name with "Anonymous"
        }
        
        // Verify triples were added
        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(2, allTriples.size, "Should have 2 triples")
        
        // Find specific triples
        val knowsTriple = allTriples.find { it.predicate == FOAF.knows }
        val nameTriple = allTriples.find { it.predicate == FOAF.name && it.subject == bnode }
        
        assertNotNull(knowsTriple, "Should have knows triple")
        assertNotNull(nameTriple, "Should have blank node name triple")
        
        assertEquals(bnode, knowsTriple!!.obj, "Knows object should be blank node")
        assertEquals("Anonymous", (nameTriple!!.obj as Literal).lexical, "Blank node name should be Anonymous")
        
        repo.close()
    }
    
    @Test
    fun `DSL with string predicates works`() {
        val repo = Rdf.memory()
        
        val person = Iri("http://example.org/person")
        
        // Test DSL with string predicates
        repo.add {
            person[Iri("http://example.org/customProperty")] = "custom value"
            person[Iri("http://example.org/name")] = "John"
        }
        
        // Verify triples were added
        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(2, allTriples.size, "Should have 2 triples")
        
        // Find specific triples
        val customTriple = allTriples.find { it.predicate.value == "http://example.org/customProperty" }
        val nameTriple = allTriples.find { it.predicate.value == "http://example.org/name" }
        
        assertNotNull(customTriple, "Should have custom property triple")
        assertNotNull(nameTriple, "Should have name triple")
        
        assertEquals("custom value", (customTriple!!.obj as Literal).lexical, "Custom property should be preserved")
        assertEquals("John", (nameTriple!!.obj as Literal).lexical, "Name should be preserved")
        
        repo.close()
    }
    
    @Test
    fun `DSL with mixed term types works`() {
        val repo = Rdf.memory()
        
        val resource = Iri("http://example.org/resource")
        val bnode = bnode("b1")
        val friend = Iri("http://example.org/friend")
        
        // Test DSL with mixed term types
        repo.add {
            resource has DCTERMS.title with "Resource Name"
            resource has FOAF.knows with friend
            resource has FOAF.knows with bnode
            bnode has RDFS.label with "Blank Node Label"
            bnode has FOAF.knows with friend
        }
        
        // Verify triples were added
        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(5, allTriples.size, "Should have 5 triples")
        
        // Find specific triples
        val titleTriple = allTriples.find { it.predicate == DCTERMS.title }
        val resourceKnowsFriend = allTriples.find { it.predicate == FOAF.knows && it.subject == resource && it.obj == friend }
        val resourceKnowsBnode = allTriples.find { it.predicate == FOAF.knows && it.subject == resource && it.obj == bnode }
        val bnodeLabel = allTriples.find { it.predicate == RDFS.label && it.subject == bnode }
        val bnodeKnowsFriend = allTriples.find { it.predicate == FOAF.knows && it.subject == bnode && it.obj == friend }
        
        assertNotNull(titleTriple, "Should have title triple")
        assertNotNull(resourceKnowsFriend, "Should have resource knows friend triple")
        assertNotNull(resourceKnowsBnode, "Should have resource knows blank node triple")
        assertNotNull(bnodeLabel, "Should have blank node label triple")
        assertNotNull(bnodeKnowsFriend, "Should have blank node knows friend triple")
        
        repo.close()
    }

    @Test
    fun `DSL minus operator syntax works`() {
        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")
        val friend = Iri("http://example.org/friend")

        repo.add {
            person - FOAF.name - "Alice"
            person - FOAF.age - 30
            person - FOAF.knows - friend
            friend - FOAF.name - "Bob"
        }

        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(4, allTriples.size, "Should have 4 triples")

        // Find specific triples
        val nameTriple = allTriples.find { it.predicate == FOAF.name && it.subject == person }
        val ageTriple = allTriples.find { it.predicate == FOAF.age && it.subject == person }
        val knowsTriple = allTriples.find { it.predicate == FOAF.knows && it.subject == person }
        val friendNameTriple = allTriples.find { it.predicate == FOAF.name && it.subject == friend }

        assertNotNull(nameTriple, "Should have person name triple")
        assertNotNull(ageTriple, "Should have person age triple")
        assertNotNull(knowsTriple, "Should have knows triple")
        assertNotNull(friendNameTriple, "Should have friend name triple")

        assertEquals("Alice", (nameTriple!!.obj as Literal).lexical, "Person name should be Alice")
        assertEquals("30", (ageTriple!!.obj as Literal).lexical, "Person age should be 30")
        assertEquals(friend, knowsTriple!!.obj, "Knows object should be friend")
        assertEquals("Bob", (friendNameTriple!!.obj as Literal).lexical, "Friend name should be Bob")

        repo.close()
    }

    @Test
    fun `DSL minus operator with different literal types works`() {
        val repo = Rdf.memory()
        val resource = Iri("http://example.org/resource")

        repo.add {
            resource - DCTERMS.title - "Test Title"
            resource - DCTERMS.extent - 1000
            resource - DCTERMS.available - true
        }

        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(3, allTriples.size, "Should have 3 triples")

        val titleTriple = allTriples.find { it.predicate == DCTERMS.title }
        val extentTriple = allTriples.find { it.predicate == DCTERMS.extent }
        val availableTriple = allTriples.find { it.predicate == DCTERMS.available }

        assertNotNull(titleTriple, "Should have title triple")
        assertNotNull(extentTriple, "Should have extent triple")
        assertNotNull(availableTriple, "Should have available triple")

        assertEquals("Test Title", (titleTriple!!.obj as Literal).lexical, "Title should be preserved")
        assertEquals("1000", (extentTriple!!.obj as Literal).lexical, "Extent should be converted to string")
        assertEquals("true", (availableTriple!!.obj as Literal).lexical, "Available should be converted to string")

        repo.close()
    }

    @Test
    fun `DSL minus operator with IRI and BlankNode objects works`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/subject")
        val friend = Iri("http://example.org/friend")
        val bnode = bnode("anon1")

        repo.add {
            subject - FOAF.knows - friend
            subject - DCTERMS.creator - friend
            subject - FOAF.knows - bnode
        }

        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(3, allTriples.size, "Should have 3 triples")

        val knowsTriple = allTriples.find { it.predicate == FOAF.knows && it.obj == friend }
        val creatorTriple = allTriples.find { it.predicate == DCTERMS.creator }
        val bnodeTriple = allTriples.find { it.predicate == FOAF.knows && it.obj == bnode }

        assertNotNull(knowsTriple, "Should have knows triple")
        assertNotNull(creatorTriple, "Should have creator triple")
        assertNotNull(bnodeTriple, "Should have blank node triple")

        assertEquals(friend, knowsTriple!!.obj, "Knows object should be friend IRI")
        assertEquals(friend, creatorTriple!!.obj, "Creator object should be friend IRI")
        assertEquals(bnode, bnodeTriple!!.obj, "Blank node object should be correct")

        repo.close()
    }

    @Test
    fun `DSL minus operator mixed with bracket syntax works`() {
        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")

        repo.add {
            // Mix minus operator with bracket syntax
            person - FOAF.name - "Alice"
            person[DCTERMS.title] = "Person Title"
            person - FOAF.age - 25
            person[DCTERMS.creator] = person
        }

        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(4, allTriples.size, "Should have 4 triples")

        val nameTriple = allTriples.find { it.predicate == FOAF.name }
        val titleTriple = allTriples.find { it.predicate == DCTERMS.title }
        val ageTriple = allTriples.find { it.predicate == FOAF.age }
        val creatorTriple = allTriples.find { it.predicate == DCTERMS.creator }

        assertNotNull(nameTriple, "Should have name triple")
        assertNotNull(titleTriple, "Should have title triple")
        assertNotNull(ageTriple, "Should have age triple")
        assertNotNull(creatorTriple, "Should have creator triple")

        assertEquals("Alice", (nameTriple!!.obj as Literal).lexical, "Name should be Alice")
        assertEquals("Person Title", (titleTriple!!.obj as Literal).lexical, "Title should be Person Title")
        assertEquals("25", (ageTriple!!.obj as Literal).lexical, "Age should be 25")
        assertEquals(person, creatorTriple!!.obj, "Creator should be person")

        repo.close()
    }
}









