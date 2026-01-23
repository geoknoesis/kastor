package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.dsl.bag
import com.geoknoesis.kastor.rdf.dsl.seq
import com.geoknoesis.kastor.rdf.dsl.alt
import com.geoknoesis.kastor.rdf.dsl.values
import com.geoknoesis.kastor.rdf.dsl.list
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Test for RDF containers (Bag, Seq, Alt) DSL support.
 */
class RdfContainerTest {

    @Test
    fun `bag creates rdf_Bag container with rdf__1, rdf__2, etc`() {
        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")

        repo.add {
            person - FOAF.name - string("Alice")
            person - DCTERMS.subject - bag(
                string("Technology"),
                string("AI"),
                string("RDF"),
                string("Kotlin")
            )
        }

        val allTriples = repo.defaultGraph.getTriples()
        assertTrue(allTriples.size >= 7, "Should have at least 7 triples (1 name + 1 bag type + 4 bag members)")

        // Find the bag node
        val bagTriple = allTriples.find { 
            it.subject == person && it.predicate == DCTERMS.subject 
        }
        assertNotNull(bagTriple, "Should have bag triple")
        assertTrue(bagTriple!!.obj is BlankNode, "Bag should be a blank node")

        val bagNode = bagTriple.obj as BlankNode

        // Check bag type declaration
        val bagTypeTriple = allTriples.find { 
            it.subject == bagNode && it.predicate == RDF.type && it.obj == RDF.Bag 
        }
        assertNotNull(bagTypeTriple, "Bag should have rdf:type rdf:Bag")

        // Check bag members
        val bagMembers = allTriples.filter { 
            it.subject == bagNode && it.predicate.value.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#_")
        }
        assertEquals(4, bagMembers.size, "Bag should have 4 members")

        val memberValues = bagMembers.map { it.obj }
        assertTrue(memberValues.contains(Literal("Technology", XSD.string)), "Should contain Technology")
        assertTrue(memberValues.contains(Literal("AI", XSD.string)), "Should contain AI")
        assertTrue(memberValues.contains(Literal("RDF", XSD.string)), "Should contain RDF")
        assertTrue(memberValues.contains(Literal("Kotlin", XSD.string)), "Should contain Kotlin")

        repo.close()
    }

    @Test
    fun `seq creates rdf_Seq container with rdf__1, rdf__2, etc`() {
        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")
        val friend1 = Iri("http://example.org/friend1")
        val friend2 = Iri("http://example.org/friend2")
        val friend3 = Iri("http://example.org/friend3")

        repo.add {
            person - FOAF.name - string("Alice")
            person - FOAF.knows - seq(friend1, friend2, friend3)
        }

        val allTriples = repo.defaultGraph.getTriples()
        assertTrue(allTriples.size >= 5, "Should have at least 5 triples (1 name + 1 seq type + 3 seq members)")

        // Find the seq node
        val seqTriple = allTriples.find { 
            it.subject == person && it.predicate == FOAF.knows 
        }
        assertNotNull(seqTriple, "Should have seq triple")
        assertTrue(seqTriple!!.obj is BlankNode, "Seq should be a blank node")

        val seqNode = seqTriple.obj as BlankNode

        // Check seq type declaration
        val seqTypeTriple = allTriples.find { 
            it.subject == seqNode && it.predicate == RDF.type && it.obj == RDF.Seq 
        }
        assertNotNull(seqTypeTriple, "Seq should have rdf:type rdf:Seq")

        // Check seq members
        val seqMembers = allTriples.filter { 
            it.subject == seqNode && it.predicate.value.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#_")
        }
        assertEquals(3, seqMembers.size, "Seq should have 3 members")

        val memberValues = seqMembers.map { it.obj }
        assertTrue(memberValues.contains(friend1), "Should contain friend1")
        assertTrue(memberValues.contains(friend2), "Should contain friend2")
        assertTrue(memberValues.contains(friend3), "Should contain friend3")

        repo.close()
    }

    @Test
    fun `alt creates rdf_Alt container with rdf__1, rdf__2, etc`() {
        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")
        val email1 = "alice@example.com"
        val email2 = "alice@work.com"

        repo.add {
            person - FOAF.name - string("Alice")
            person - FOAF.mbox - alt(string(email1), string(email2))
        }

        val allTriples = repo.defaultGraph.getTriples()
        assertTrue(allTriples.size >= 4, "Should have at least 4 triples (1 name + 1 alt type + 2 alt members)")

        // Find the alt node
        val altTriple = allTriples.find { 
            it.subject == person && it.predicate == FOAF.mbox 
        }
        assertNotNull(altTriple, "Should have alt triple")
        assertTrue(altTriple!!.obj is BlankNode, "Alt should be a blank node")

        val altNode = altTriple.obj as BlankNode

        // Check alt type declaration
        val altTypeTriple = allTriples.find { 
            it.subject == altNode && it.predicate == RDF.type && it.obj == RDF.Alt 
        }
        assertNotNull(altTypeTriple, "Alt should have rdf:type rdf:Alt")

        // Check alt members
        val altMembers = allTriples.filter { 
            it.subject == altNode && it.predicate.value.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#_")
        }
        assertEquals(2, altMembers.size, "Alt should have 2 members")

        val memberValues = altMembers.map { it.obj }
        assertTrue(memberValues.contains(Literal(email1, XSD.string)), "Should contain email1")
        assertTrue(memberValues.contains(Literal(email2, XSD.string)), "Should contain email2")

        repo.close()
    }

    @Test
    fun `containers work with mixed types`() {
        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")
        val friend = Iri("http://example.org/friend")
        val age = 30
        val height = 165.5

        repo.add {
            person - FOAF.name - string("Alice")
            person - DCTERMS.subject - bag(
                string("Technology"),
                friend,
                age.toLiteral(),
                height.toLiteral()
            )
        }

        val allTriples = repo.defaultGraph.getTriples()
        assertTrue(allTriples.size >= 6, "Should have at least 6 triples (1 name + 1 bag type + 4 bag members)")

        // Find the bag node
        val bagTriple = allTriples.find { 
            it.subject == person && it.predicate == DCTERMS.subject 
        }
        assertNotNull(bagTriple, "Should have bag triple")

        val bagNode = bagTriple!!.obj as BlankNode

        // Check bag members with mixed types
        val bagMembers = allTriples.filter { 
            it.subject == bagNode && it.predicate.value.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#_")
        }
        assertEquals(4, bagMembers.size, "Bag should have 4 members")

        val memberValues = bagMembers.map { it.obj }
        assertTrue(memberValues.contains(Literal("Technology", XSD.string)), "Should contain Technology string")
        assertTrue(memberValues.contains(friend), "Should contain friend IRI")
        assertTrue(memberValues.contains(Literal("30", XSD.integer)), "Should contain age integer")
        assertTrue(memberValues.contains(Literal("165.5", XSD.double)), "Should contain height double")

        repo.close()
    }

    @Test
    fun `containers work with standalone graph`() {
        val person = Iri("http://example.org/person")
        val friend1 = Iri("http://example.org/friend1")
        val friend2 = Iri("http://example.org/friend2")

        val graph = Rdf.graph {
            person - FOAF.name - string("Alice")
            person - FOAF.knows - seq(friend1, friend2)
            person - DCTERMS.subject - bag(string("Tech"), string("AI"))
            person - FOAF.mbox - alt(
                string("email1@example.com"),
                string("email2@example.com")
            )
        }

        val allTriples = graph.getTriples()
        assertTrue(allTriples.size >= 9, "Should have at least 9 triples (1 name + 3 container types + 6 container members)")

        // Find container nodes
        val seqTriple = allTriples.find { 
            it.subject == person && it.predicate == FOAF.knows 
        }
        val bagTriple = allTriples.find { 
            it.subject == person && it.predicate == DCTERMS.subject 
        }
        val altTriple = allTriples.find { 
            it.subject == person && it.predicate == FOAF.mbox 
        }

        assertNotNull(seqTriple, "Should have seq triple")
        assertNotNull(bagTriple, "Should have bag triple")
        assertNotNull(altTriple, "Should have alt triple")

        // Check types
        val seqNode = seqTriple!!.obj as BlankNode
        val bagNode = bagTriple!!.obj as BlankNode
        val altNode = altTriple!!.obj as BlankNode

        assertTrue(allTriples.any { 
            it.subject == seqNode && it.predicate == RDF.type && it.obj == RDF.Seq 
        }, "Seq should have correct type")
        
        assertTrue(allTriples.any { 
            it.subject == bagNode && it.predicate == RDF.type && it.obj == RDF.Bag 
        }, "Bag should have correct type")
        
        assertTrue(allTriples.any { 
            it.subject == altNode && it.predicate == RDF.type && it.obj == RDF.Alt 
        }, "Alt should have correct type")
    }

    @Test
    fun `containers vs lists vs individual values comparison`() {
        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")
        val friend1 = Iri("http://example.org/friend1")
        val friend2 = Iri("http://example.org/friend2")

        repo.add {
            person - FOAF.name - string("Alice")
            
            // Individual values (creates 2 separate triples)
            person - FOAF.knows - values(friend1, friend2)
            
            // RDF List (creates list structure with rdf:first, rdf:rest, rdf:nil)
            person - DCTERMS.subject - list(string("Tech"), string("AI"))
            
            // RDF Bag (creates bag with rdf:_1, rdf:_2)
            person - FOAF.mbox - bag(string("email1@example.com"), string("email2@example.com"))
            
            // RDF Seq (creates seq with rdf:_1, rdf:_2)
            person - FOAF.age - seq(30.toLiteral(), 35.toLiteral())
            
            // RDF Alt (creates alt with rdf:_1, rdf:_2)
            person - FOAF.interest - alt(string("Music"), string("Sports"))
        }

        val allTriples = repo.defaultGraph.getTriples()
        assertTrue(allTriples.size >= 15, "Should have many triples from all container types")

        val personTriples = allTriples.filter { it.subject == person }
        assertEquals(7, personTriples.size, "Person should have 7 direct properties (1 name + 2 individual values + 4 containers)")

        // Check individual values created 2 separate triples
        val knowsTriples = allTriples.filter { 
            it.subject == person && it.predicate == FOAF.knows 
        }
        assertEquals(2, knowsTriples.size, "values() should create 2 individual triples")

        // Check RDF List structure
        val subjectTriple = allTriples.find { 
            it.subject == person && it.predicate == DCTERMS.subject 
        }
        assertNotNull(subjectTriple, "Should have subject triple")
        assertTrue(subjectTriple!!.obj is BlankNode, "List should be a blank node")

        // Check RDF Bag structure
        val mboxTriple = allTriples.find { 
            it.subject == person && it.predicate == FOAF.mbox 
        }
        assertNotNull(mboxTriple, "Should have mbox triple")
        assertTrue(mboxTriple!!.obj is BlankNode, "Bag should be a blank node")

        // Check RDF Seq structure
        val ageTriple = allTriples.find { 
            it.subject == person && it.predicate == FOAF.age 
        }
        assertNotNull(ageTriple, "Should have age triple")
        assertTrue(ageTriple!!.obj is BlankNode, "Seq should be a blank node")

        // Check RDF Alt structure
        val interestTriple = allTriples.find { 
            it.subject == person && it.predicate == FOAF.interest 
        }
        assertNotNull(interestTriple, "Should have interest triple")
        assertTrue(interestTriple!!.obj is BlankNode, "Alt should be a blank node")

        repo.close()
    }

    @Test
    fun `empty containers work correctly`() {
        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")

        repo.add {
            person - FOAF.name - string("Alice")
            person - DCTERMS.subject - bag()
            person - FOAF.knows - seq()
            person - FOAF.mbox - alt()
        }

        val allTriples = repo.defaultGraph.getTriples()
        assertTrue(allTriples.size >= 4, "Should have at least 4 triples (1 name + 3 container types)")

        val personTriples = allTriples.filter { it.subject == person }
        assertEquals(4, personTriples.size, "Person should have 4 properties")

        // Check that empty containers still have type declarations
        val bagTriple = personTriples.find { it.predicate == DCTERMS.subject }
        val seqTriple = personTriples.find { it.predicate == FOAF.knows }
        val altTriple = personTriples.find { it.predicate == FOAF.mbox }

        assertNotNull(bagTriple, "Should have bag triple")
        assertNotNull(seqTriple, "Should have seq triple")
        assertNotNull(altTriple, "Should have alt triple")

        // Check type declarations exist for empty containers
        val bagNode = bagTriple!!.obj as BlankNode
        val seqNode = seqTriple!!.obj as BlankNode
        val altNode = altTriple!!.obj as BlankNode

        assertTrue(allTriples.any { 
            it.subject == bagNode && it.predicate == RDF.type && it.obj == RDF.Bag 
        }, "Empty bag should have type declaration")
        
        assertTrue(allTriples.any { 
            it.subject == seqNode && it.predicate == RDF.type && it.obj == RDF.Seq 
        }, "Empty seq should have type declaration")
        
        assertTrue(allTriples.any { 
            it.subject == altNode && it.predicate == RDF.type && it.obj == RDF.Alt 
        }, "Empty alt should have type declaration")

        repo.close()
    }
}









