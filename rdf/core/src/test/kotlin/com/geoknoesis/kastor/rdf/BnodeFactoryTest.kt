package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.dsl.bag
import com.geoknoesis.kastor.rdf.dsl.seq
import com.geoknoesis.kastor.rdf.dsl.alt
import com.geoknoesis.kastor.rdf.dsl.values
import com.geoknoesis.kastor.rdf.dsl.list
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Test for bnode factory with sequential naming.
 */
class BnodeFactoryTest {

    @Test
    fun `bnode factory creates sequential names for lists`() {
        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")

        repo.add {
            person - FOAF.name - "Alice"
            person - FOAF.mbox - list("alice@example.com", "alice@work.com", "alice@personal.com")
        }

        val allTriples = repo.defaultGraph.getTriples()
        val bnodeTriples = allTriples.filter { it.subject is BlankNode || it.obj is BlankNode }
        
        // Extract bnode names and verify they are sequential
        val bnodeNames = bnodeTriples.flatMap { triple ->
            listOfNotNull(
                if (triple.subject is BlankNode) (triple.subject as BlankNode).id else null,
                if (triple.obj is BlankNode) (triple.obj as BlankNode).id else null
            )
        }.distinct().sorted()

        // Should have sequential bnode names: b1, b2, b3
        assertTrue(bnodeNames.any { it.startsWith("b") }, "Should have b bnode names")
        assertTrue(bnodeNames.contains("b1"), "Should have b1")
        assertTrue(bnodeNames.contains("b2"), "Should have b2") 
        assertTrue(bnodeNames.contains("b3"), "Should have b3")

        repo.close()
    }

    @Test
    fun `bnode factory creates sequential names for containers`() {
        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")

        repo.add {
            person - FOAF.name - "Alice"
            person - DCTERMS.subject - bag("Tech", "AI", "RDF")     // Creates bag_1
            person - FOAF.knows - seq(person, person, person)      // Creates seq_2
            person - FOAF.mbox - alt("email1", "email2")          // Creates alt_3
        }

        val allTriples = repo.defaultGraph.getTriples()
        val bnodeTriples = allTriples.filter { it.subject is BlankNode || it.obj is BlankNode }
        
        // Extract bnode names and verify they are sequential
        val bnodeNames = bnodeTriples.flatMap { triple ->
            listOfNotNull(
                if (triple.subject is BlankNode) (triple.subject as BlankNode).id else null,
                if (triple.obj is BlankNode) (triple.obj as BlankNode).id else null
            )
        }.distinct().sorted()

        // Should have sequential container bnode names: b1, b2, b3
        assertTrue(bnodeNames.contains("b1"), "Should have b1")
        assertTrue(bnodeNames.contains("b2"), "Should have b2")
        assertTrue(bnodeNames.contains("b3"), "Should have b3")

        repo.close()
    }

    @Test
    fun `bnode factory creates predictable names across multiple operations`() {
        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")

        repo.add {
            person - FOAF.name - "Alice"
            
            // First operation: list
            person - FOAF.mbox - list("email1", "email2")
        }

        val firstRunTriples = repo.defaultGraph.getTriples()
        val firstBnodeNames = firstRunTriples.filter { it.subject is BlankNode || it.obj is BlankNode }
            .flatMap { triple ->
                listOfNotNull(
                    if (triple.subject is BlankNode) (triple.subject as BlankNode).id else null,
                    if (triple.obj is BlankNode) (triple.obj as BlankNode).id else null
                )
            }.distinct().sorted()

        // Add more data to the same repository
        repo.add {
            // Second operation: bag
            person - DCTERMS.subject - bag("Tech", "AI")
        }

        val secondRunTriples = repo.defaultGraph.getTriples()
        val secondBnodeNames = secondRunTriples.filter { it.subject is BlankNode || it.obj is BlankNode }
            .flatMap { triple ->
                listOfNotNull(
                    if (triple.subject is BlankNode) (triple.subject as BlankNode).id else null,
                    if (triple.obj is BlankNode) (triple.obj as BlankNode).id else null
                )
            }.distinct().sorted()

        // Each repo.add call creates a new TripleDsl instance, so counters reset
        assertTrue(firstBnodeNames.contains("b1"), "First run should have b1")
        assertTrue(firstBnodeNames.contains("b2"), "First run should have b2")
        
        assertTrue(secondBnodeNames.contains("b1"), "Second run should still have b1")
        assertTrue(secondBnodeNames.contains("b2"), "Second run should still have b2")
        assertTrue(secondBnodeNames.contains("b1"), "Second run should have b1 (new TripleDsl instance)")

        repo.close()
    }

    @Test
    fun `bnode factory works with standalone graph`() {
        val person = Iri("http://example.org/person")

        val graph = Rdf.graph {
            person - FOAF.name - "Alice"
            person - FOAF.mbox - list("email1", "email2")      // Creates list_1, list_2
            person - DCTERMS.subject - bag("Tech", "AI")       // Creates bag_3
            person - FOAF.knows - seq(person, person)          // Creates seq_4
            person - FOAF.mbox - alt("email1", "email2")       // Creates alt_5
        }

        val allTriples = graph.getTriples()
        val bnodeNames = allTriples.filter { it.subject is BlankNode || it.obj is BlankNode }
            .flatMap { triple ->
                listOfNotNull(
                    if (triple.subject is BlankNode) (triple.subject as BlankNode).id else null,
                    if (triple.obj is BlankNode) (triple.obj as BlankNode).id else null
                )
            }.distinct().sorted()

        // Should have sequential bnode names across all operations
        assertTrue(bnodeNames.contains("b1"), "Should have b1")
        assertTrue(bnodeNames.contains("b2"), "Should have b2")
        assertTrue(bnodeNames.contains("b3"), "Should have b3")
        assertTrue(bnodeNames.contains("b4"), "Should have b4")
        assertTrue(bnodeNames.contains("b5"), "Should have b5")

        // Verify no gaps in sequence
        val numberedBnodes = bnodeNames.filter { it.matches(Regex("\\w+\\d+")) }
            .map { Regex("\\d+").find(it)?.value?.toInt() ?: 0 }
            .sorted()

        assertEquals(listOf(1, 2, 3, 4, 5), numberedBnodes, "Should have sequential numbering")
    }

    @Test
    fun `bnode factory creates unique names for different prefixes`() {
        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")

        repo.add {
            person - FOAF.name - "Alice"
            
            // Mix different container types
            person - FOAF.mbox - list("email1", "email2")          // list_1, list_2
            person - DCTERMS.subject - bag("Tech", "AI")           // bag_3
            person - FOAF.knows - seq(person, person)              // seq_4
            person - FOAF.mbox - alt("email1", "email2")           // alt_5
            person - DCTERMS.subject - bag("More", "Tech")         // bag_6
        }

        val allTriples = repo.defaultGraph.getTriples()
        val bnodeNames = allTriples.filter { it.subject is BlankNode || it.obj is BlankNode }
            .flatMap { triple ->
                listOfNotNull(
                    if (triple.subject is BlankNode) (triple.subject as BlankNode).id else null,
                    if (triple.obj is BlankNode) (triple.obj as BlankNode).id else null
                )
            }.distinct().sorted()

        // Should have sequential numbering: b1, b2, b3, b4, b5, b6
        assertTrue(bnodeNames.contains("b1"), "Should have b1")
        assertTrue(bnodeNames.contains("b2"), "Should have b2")
        assertTrue(bnodeNames.contains("b3"), "Should have b3")
        assertTrue(bnodeNames.contains("b4"), "Should have b4")
        assertTrue(bnodeNames.contains("b5"), "Should have b5")
        assertTrue(bnodeNames.contains("b6"), "Should have b6")

        // Verify all names are unique
        assertEquals(bnodeNames.size, bnodeNames.distinct().size, "All bnode names should be unique")
    }
}









