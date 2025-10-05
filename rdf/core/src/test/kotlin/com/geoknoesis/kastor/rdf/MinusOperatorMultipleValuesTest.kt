package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MinusOperatorMultipleValuesTest {

    @Test
    fun `minus operator with array of values works`() {
        val repo = Rdf.memory()
        
        repo.add {
            val person = iri("http://example.org/person")
            val friend1 = iri("http://example.org/friend1")
            val friend2 = iri("http://example.org/friend2")
            val friend3 = iri("http://example.org/friend3")
            
            // Single value (existing functionality)
            person - FOAF.name - "Alice"
            
            // Multiple values using array
            person - FOAF.knows - arrayOf(friend1, friend2, friend3)
            
            // Multiple email addresses using array
            person - FOAF.mbox - arrayOf("alice@example.com", "alice@work.com")
        }
        
        val allTriples = repo.defaultGraph.getTriples()
        assertEquals(6, allTriples.size, "Should have 6 triples")
        
        val personTriples = allTriples.filter { it.subject == iri("http://example.org/person") }
        assertEquals(6, personTriples.size, "Person should have 6 properties")
        
        // Verify single name
        val nameTriples = personTriples.filter { it.predicate == FOAF.name }
        assertEquals(1, nameTriples.size, "Should have 1 name")
        assertEquals("Alice", (nameTriples.first().obj as Literal).lexical)
        
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
    fun `minus operator with list of values creates RDF List`() {
        val repo = Rdf.memory()
        
        repo.add {
            val person = iri("http://example.org/person")
            val friends = listOf(
                iri("http://example.org/friend1"),
                iri("http://example.org/friend2"),
                iri("http://example.org/friend3")
            )
            val emails = listOf("alice@example.com", "alice@work.com", "alice@personal.com")
            
            person - FOAF.name - "Alice"
            
            // Multiple values using list - creates RDF Lists
            person - FOAF.knows - friends
            person - FOAF.mbox - emails
        }
        
        val allTriples = repo.defaultGraph.getTriples()
        assertTrue(allTriples.size >= 13, "Should have at least 13 triples (1 name + 12 for RDF list structure)")
        
        val personTriples = allTriples.filter { it.subject == iri("http://example.org/person") }
        assertEquals(3, personTriples.size, "Person should have 3 direct properties (name, knows, mbox)")
        
        // Verify name property
        val nameTriples = personTriples.filter { it.predicate == FOAF.name }
        assertEquals(1, nameTriples.size, "Should have 1 name")
        
        // Verify knows property points to RDF List
        val knowsTriples = personTriples.filter { it.predicate == FOAF.knows }
        assertEquals(1, knowsTriples.size, "Should have 1 knows property")
        assertTrue(knowsTriples.first().obj is BlankNode, "Knows should point to a blank node (RDF list head)")
        
        // Verify mbox property points to RDF List
        val mboxTriples = personTriples.filter { it.predicate == FOAF.mbox }
        assertEquals(1, mboxTriples.size, "Should have 1 mbox property")
        assertTrue(mboxTriples.first().obj is BlankNode, "Mbox should point to a blank node (RDF list head)")
        
        repo.close()
    }


    @Test
    fun `minus operator with mixed types in collections works`() {
        val repo = Rdf.memory()
        
        repo.add {
            val person = iri("http://example.org/person")
            val friend1 = iri("http://example.org/friend1")
            val friend2 = iri("http://example.org/friend2")
            val bnode = bnode("anon1")
            
            person - FOAF.name - "Alice"
            
            // Mixed types in array
            person - FOAF.knows - arrayOf(friend1, friend2, bnode)
            
            // Mixed types in list
            person - DCTERMS.subject - listOf("Technology", "Programming", 42, true)
        }
        
        val allTriples = repo.defaultGraph.getTriples()
        assertTrue(allTriples.size >= 9, "Should have at least 9 triples") // 1 name + 3 knows + 5 for RDF list structure
        
        val personTriples = allTriples.filter { it.subject == iri("http://example.org/person") }
        assertEquals(3, personTriples.size, "Person should have 3 direct properties (name, knows, subject)")
        
        // Verify multiple knows relationships with mixed types (array creates individual triples)
        val knowsTriples = personTriples.filter { it.predicate == FOAF.knows }
        assertEquals(3, knowsTriples.size, "Should have 3 knows properties")
        
        val knowsObjects = knowsTriples.map { it.obj }
        assertTrue(knowsObjects.contains(iri("http://example.org/friend1")), "Should know friend1")
        assertTrue(knowsObjects.contains(iri("http://example.org/friend2")), "Should know friend2")
        assertTrue(knowsObjects.contains(bnode("anon1")), "Should know blank node")
        
        // Verify subject property points to RDF List
        val subjectTriples = personTriples.filter { it.predicate == DCTERMS.subject }
        assertEquals(1, subjectTriples.size, "Should have 1 subject property")
        assertTrue(subjectTriples.first().obj is BlankNode, "Subject should point to a blank node (RDF list head)")
        
        repo.close()
    }

    @Test
    fun `minus operator multiple values works with standalone graph`() {
        val person = iri("http://example.org/person")
        val friend1 = iri("http://example.org/friend1")
        val friend2 = iri("http://example.org/friend2")
        val friend3 = iri("http://example.org/friend3")
        
        val graph = Rdf.graph {
            person - FOAF.name - "Alice"
            
            // Multiple values using array - creates individual triples
            person - FOAF.knows - arrayOf(friend1, friend2, friend3)
            
            // Multiple values using list - creates RDF List
            person - FOAF.mbox - listOf("alice@example.com", "alice@work.com")
            
            // Multiple values using array for homepage
            person - FOAF.homepage - arrayOf("http://alice.com", "http://alice.blog.com")
        }
        
        val allTriples = graph.getTriples()
        assertTrue(allTriples.size >= 8, "Should have at least 8 triples")
        
        val personTriples = allTriples.filter { it.subject == person }
        assertEquals(4, personTriples.size, "Person should have 4 direct properties (name, knows, mbox, homepage)")
        
        // Verify knows property points to RDF List
        val knowsTriples = personTriples.filter { it.predicate == FOAF.knows }
        assertEquals(1, knowsTriples.size, "Should have 1 knows property")
        assertTrue(knowsTriples.first().obj is BlankNode, "Knows should point to a blank node (RDF list head)")
        
        // Verify mbox property points to RDF List
        val mboxTriples = personTriples.filter { it.predicate == FOAF.mbox }
        assertEquals(1, mboxTriples.size, "Should have 1 mbox property")
        assertTrue(mboxTriples.first().obj is BlankNode, "Mbox should point to a blank node (RDF list head)")
        
        // Verify homepage property points to RDF List
        val homepageTriples = personTriples.filter { it.predicate == FOAF.homepage }
        assertEquals(1, homepageTriples.size, "Should have 1 homepage property")
        assertTrue(homepageTriples.first().obj is BlankNode, "Homepage should point to a blank node (RDF list head)")
    }

    @Test
    fun `minus operator multiple values with document metadata works`() {
        val repo = Rdf.memory()
        
        repo.add {
            val document = iri("http://example.org/document")
            val author1 = iri("http://example.org/author1")
            val author2 = iri("http://example.org/author2")
            val author3 = iri("http://example.org/author3")
            
            // Multiple titles
            document - DCTERMS.title - arrayOf("Main Title", "Subtitle", "Alternative Title")
            
            // Multiple creators
            document - DCTERMS.creator - listOf(author1, author2, author3)
            
            // Multiple subjects using array
            document - DCTERMS.subject - arrayOf("Technology", "Programming")
            
            // Multiple types using list
            document - RDF.type - listOf("Document", "Report", "Publication")
        }
        
        val allTriples = repo.defaultGraph.getTriples()
        assertTrue(allTriples.size >= 11, "Should have at least 11 triples") // 3 titles + 5 for creator RDF list + 2 subjects + 5 for type RDF list
        
        val documentTriples = allTriples.filter { it.subject == iri("http://example.org/document") }
        assertEquals(7, documentTriples.size, "Document should have 7 direct properties (3 titles + 1 creator + 2 subjects + 1 type)")
        
        // Verify multiple titles (array creates individual triples)
        val titleTriples = documentTriples.filter { it.predicate == DCTERMS.title }
        assertEquals(3, titleTriples.size, "Should have 3 title properties")
        
        val titles = titleTriples.map { (it.obj as Literal).lexical }
        assertTrue(titles.contains("Main Title"), "Should have main title")
        assertTrue(titles.contains("Subtitle"), "Should have subtitle")
        assertTrue(titles.contains("Alternative Title"), "Should have alternative title")
        
        // Verify creator property points to RDF List and check each element
        val creatorTriples = documentTriples.filter { it.predicate == DCTERMS.creator }
        assertEquals(1, creatorTriples.size, "Should have 1 creator property")
        assertTrue(creatorTriples.first().obj is BlankNode, "Creator should point to a blank node (RDF list head)")
        
        // Traverse the RDF List to verify each author
        val listHead = creatorTriples.first().obj as BlankNode
        val listElements = mutableListOf<RdfTerm>()
        var currentListElement: RdfTerm? = listHead
        var count = 0
        
        while (currentListElement is BlankNode && count < 10) { // Limit count to prevent infinite loops
            val firstTriple = allTriples.find { it.subject == currentListElement && it.predicate == RDF.first }
            assertNotNull(firstTriple, "List node should have rdf:first property")
            listElements.add(firstTriple!!.obj)
            
            val restTriple = allTriples.find { it.subject == currentListElement && it.predicate == RDF.rest }
            assertNotNull(restTriple, "List node should have rdf:rest property")
            currentListElement = restTriple!!.obj
            count++
        }
        
        assertEquals(RDF.nil, currentListElement, "Last list element should point to rdf:nil")
        assertEquals(3, listElements.size, "RDF List should contain 3 authors")
        assertTrue(listElements.contains(iri("http://example.org/author1")), "RDF List should contain author1")
        assertTrue(listElements.contains(iri("http://example.org/author2")), "RDF List should contain author2")
        assertTrue(listElements.contains(iri("http://example.org/author3")), "RDF List should contain author3")
        
        // Verify subject property (array creates individual triples)
        val subjectTriples = documentTriples.filter { it.predicate == DCTERMS.subject }
        assertEquals(2, subjectTriples.size, "Should have 2 subject properties")
        
        val subjects = subjectTriples.map { (it.obj as Literal).lexical }
        assertTrue(subjects.contains("Technology"), "Should have Technology subject")
        assertTrue(subjects.contains("Programming"), "Should have Programming subject")
        
        // Verify type property points to RDF List and check each element
        val typeTriples = documentTriples.filter { it.predicate == RDF.type }
        assertEquals(1, typeTriples.size, "Should have 1 type property")
        assertTrue(typeTriples.first().obj is BlankNode, "Type should point to a blank node (RDF list head)")
        
        // Traverse the RDF List to verify each type
        val typeListHead = typeTriples.first().obj as BlankNode
        val typeListElements = mutableListOf<RdfTerm>()
        var currentTypeListElement: RdfTerm? = typeListHead
        var typeCount = 0
        
        while (currentTypeListElement is BlankNode && typeCount < 10) { // Limit count to prevent infinite loops
            val firstTriple = allTriples.find { it.subject == currentTypeListElement && it.predicate == RDF.first }
            assertNotNull(firstTriple, "Type list node should have rdf:first property")
            typeListElements.add(firstTriple!!.obj)
            
            val restTriple = allTriples.find { it.subject == currentTypeListElement && it.predicate == RDF.rest }
            assertNotNull(restTriple, "Type list node should have rdf:rest property")
            currentTypeListElement = restTriple!!.obj
            typeCount++
        }
        
        assertEquals(RDF.nil, currentTypeListElement, "Last type list element should point to rdf:nil")
        assertEquals(3, typeListElements.size, "RDF List should contain 3 types")
        assertTrue(typeListElements.contains(Literal("Document", XSD.string)), "RDF List should contain Document type")
        assertTrue(typeListElements.contains(Literal("Report", XSD.string)), "RDF List should contain Report type")
        assertTrue(typeListElements.contains(Literal("Publication", XSD.string)), "RDF List should contain Publication type")
        
        repo.close()
    }
}
