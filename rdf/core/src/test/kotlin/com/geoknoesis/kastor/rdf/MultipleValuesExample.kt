package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Example demonstrating how to handle multiple values for the same property
 * using the existing DSL patterns.
 */
class MultipleValuesExample {

    @Test
    fun `demonstrate multiple values with existing DSL`() {
        val repo = Rdf.memory()
        
        repo.add {
            val person = iri("http://example.org/person")
            val friend1 = iri("http://example.org/friend1")
            val friend2 = iri("http://example.org/friend2")
            val friend3 = iri("http://example.org/friend3")
            
            // Method 1: Multiple individual statements (most explicit)
            person[FOAF.name] = "Alice"
            person[FOAF.knows] = friend1
            person[FOAF.knows] = friend2
            person[FOAF.knows] = friend3
            person[FOAF.mbox] = "alice@example.com"
            person[FOAF.mbox] = "alice@work.com"
        }
        
        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(6, allTriples.size, "Should have 6 triples")
        
        val personTriples = allTriples.filter { it.subject == iri("http://example.org/person") }
        assertEquals(6, personTriples.size, "Person should have 6 properties")
        
        // Verify multiple knows relationships
        val knowsTriples = personTriples.filter { it.predicate == FOAF.knows }
        assertEquals(3, knowsTriples.size, "Should have 3 knows relationships")
        
        val knowsObjects = knowsTriples.map { it.obj }
        assertTrue(knowsObjects.contains(iri("http://example.org/friend1")), "Should know friend1")
        assertTrue(knowsObjects.contains(iri("http://example.org/friend2")), "Should know friend2")
        assertTrue(knowsObjects.contains(iri("http://example.org/friend3")), "Should know friend3")
        
        // Verify multiple email addresses
        val mboxTriples = personTriples.filter { it.predicate == FOAF.mbox }
        assertEquals(2, mboxTriples.size, "Should have 2 email addresses")
        
        val mboxValues = mboxTriples.map { (it.obj as Literal).lexical }
        assertTrue(mboxValues.contains("alice@example.com"), "Should have personal email")
        assertTrue(mboxValues.contains("alice@work.com"), "Should have work email")
        
        repo.close()
    }

    @Test
    fun `demonstrate multiple values with minus operator`() {
        val repo = Rdf.memory()
        
        repo.add {
            val person = iri("http://example.org/person")
            val friend1 = iri("http://example.org/friend1")
            val friend2 = iri("http://example.org/friend2")
            val friend3 = iri("http://example.org/friend3")
            
            // Method 2: Using minus operator (concise)
            person - FOAF.name - "Alice"
            person - FOAF.knows - friend1
            person - FOAF.knows - friend2
            person - FOAF.knows - friend3
            person - FOAF.mbox - "alice@example.com"
            person - FOAF.mbox - "alice@work.com"
        }
        
        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(6, allTriples.size, "Should have 6 triples")
        
        val personTriples = allTriples.filter { it.subject == iri("http://example.org/person") }
        assertEquals(6, personTriples.size, "Person should have 6 properties")
        
        // Verify multiple knows relationships
        val knowsTriples = personTriples.filter { it.predicate == FOAF.knows }
        assertEquals(3, knowsTriples.size, "Should have 3 knows relationships")
        
        val knowsObjects = knowsTriples.map { it.obj }
        assertTrue(knowsObjects.contains(iri("http://example.org/friend1")), "Should know friend1")
        assertTrue(knowsObjects.contains(iri("http://example.org/friend2")), "Should know friend2")
        assertTrue(knowsObjects.contains(iri("http://example.org/friend3")), "Should know friend3")
        
        repo.close()
    }

    @Test
    fun `demonstrate multiple values with helper function`() {
        val repo = Rdf.memory()
        
        repo.add {
            val person = iri("http://example.org/person")
            val friends = listOf(
                iri("http://example.org/friend1"),
                iri("http://example.org/friend2"),
                iri("http://example.org/friend3")
            )
            
            // Method 3: Using helper function (functional approach)
            person[FOAF.name] = "Alice"
            
            // Add multiple friends
            friends.forEach { friend ->
                person[FOAF.knows] = friend
            }
            
            // Add multiple email addresses
            listOf("alice@example.com", "alice@work.com").forEach { email ->
                person[FOAF.mbox] = email
            }
        }
        
        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(6, allTriples.size, "Should have 6 triples")
        
        val personTriples = allTriples.filter { it.subject == iri("http://example.org/person") }
        assertEquals(6, personTriples.size, "Person should have 6 properties")
        
        // Verify multiple knows relationships
        val knowsTriples = personTriples.filter { it.predicate == FOAF.knows }
        assertEquals(3, knowsTriples.size, "Should have 3 knows relationships")
        
        // Verify multiple email addresses
        val mboxTriples = personTriples.filter { it.predicate == FOAF.mbox }
        assertEquals(2, mboxTriples.size, "Should have 2 email addresses")
        
        repo.close()
    }

    @Test
    fun `demonstrate multiple values with different types`() {
        val repo = Rdf.memory()
        
        repo.add {
            val resource = iri("http://example.org/resource")
            
            // Multiple string values
            resource[FOAF.name] = "Primary Name"
            resource[FOAF.name] = "Alternative Name"
            
            // Multiple integer values
            resource[FOAF.age] = 25
            resource[FOAF.age] = 30
            resource[FOAF.age] = 35
            
            // Multiple IRI values
            resource[RDF.type] = RDF.Property
            resource[RDF.type] = RDFS.Resource
            resource[RDF.type] = iri("http://example.org/CustomType")
        }
        
        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(8, allTriples.size, "Should have 8 triples")
        
        val resourceTriples = allTriples.filter { it.subject == iri("http://example.org/resource") }
        assertEquals(8, resourceTriples.size, "Resource should have 8 properties")
        
        // Verify multiple names
        val nameTriples = resourceTriples.filter { it.predicate == FOAF.name }
        assertEquals(2, nameTriples.size, "Should have 2 names")
        
        val names = nameTriples.map { (it.obj as Literal).lexical }
        assertTrue(names.contains("Primary Name"), "Should have primary name")
        assertTrue(names.contains("Alternative Name"), "Should have alternative name")
        
        // Verify multiple ages
        val ageTriples = resourceTriples.filter { it.predicate == FOAF.age }
        assertEquals(3, ageTriples.size, "Should have 3 ages")
        
        val ages = ageTriples.map { (it.obj as Literal).lexical }
        assertTrue(ages.contains("25"), "Should have age 25")
        assertTrue(ages.contains("30"), "Should have age 30")
        assertTrue(ages.contains("35"), "Should have age 35")
        
        // Verify multiple types
        val typeTriples = resourceTriples.filter { it.predicate == RDF.type }
        assertEquals(3, typeTriples.size, "Should have 3 types")
        
        val types = typeTriples.map { it.obj }
        assertTrue(types.contains(RDF.Property), "Should be RDF.Property")
        assertTrue(types.contains(RDFS.Resource), "Should be RDFS.Resource")
        assertTrue(types.contains(iri("http://example.org/CustomType")), "Should be CustomType")
        
        repo.close()
    }

    @Test
    fun `demonstrate multiple values with document metadata`() {
        val repo = Rdf.memory()
        
        repo.add {
            val document = iri("http://example.org/document")
            
            // Multiple titles
            document[DCTERMS.title] = "Main Title"
            document[DCTERMS.title] = "Subtitle"
            document[DCTERMS.title] = "Alternative Title"
            
            // Multiple creators
            document[DCTERMS.creator] = iri("http://example.org/author1")
            document[DCTERMS.creator] = iri("http://example.org/author2")
            
            // Multiple subjects/tags
            document[DCTERMS.subject] = "Technology"
            document[DCTERMS.subject] = "Programming"
            document[DCTERMS.subject] = "Kotlin"
        }
        
        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(8, allTriples.size, "Should have 8 triples")
        
        val documentTriples = allTriples.filter { it.subject == iri("http://example.org/document") }
        assertEquals(8, documentTriples.size, "Document should have 8 properties")
        
        // Verify multiple titles
        val titleTriples = documentTriples.filter { it.predicate == DCTERMS.title }
        assertEquals(3, titleTriples.size, "Should have 3 titles")
        
        val titles = titleTriples.map { (it.obj as Literal).lexical }
        assertTrue(titles.contains("Main Title"), "Should have main title")
        assertTrue(titles.contains("Subtitle"), "Should have subtitle")
        assertTrue(titles.contains("Alternative Title"), "Should have alternative title")
        
        // Verify multiple creators
        val creatorTriples = documentTriples.filter { it.predicate == DCTERMS.creator }
        assertEquals(2, creatorTriples.size, "Should have 2 creators")
        
        val creators = creatorTriples.map { it.obj }
        assertTrue(creators.contains(iri("http://example.org/author1")), "Should have author1")
        assertTrue(creators.contains(iri("http://example.org/author2")), "Should have author2")
        
        // Verify multiple subjects
        val subjectTriples = documentTriples.filter { it.predicate == DCTERMS.subject }
        assertEquals(3, subjectTriples.size, "Should have 3 subjects")
        
        val subjects = subjectTriples.map { (it.obj as Literal).lexical }
        assertTrue(subjects.contains("Technology"), "Should have Technology subject")
        assertTrue(subjects.contains("Programming"), "Should have Programming subject")
        assertTrue(subjects.contains("Kotlin"), "Should have Kotlin subject")
        
        repo.close()
    }
}
