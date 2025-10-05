package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import org.junit.jupiter.api.Test

/**
 * Example demonstrating the updated minus operator syntax.
 * 
 * The minus operator (-) now supports:
 * - Arrays: Creates multiple individual triples
 * - Lists: Creates RDF Lists (proper RDF structure)
 * - Single values: Creates single triples
 * 
 * Pair and Triple options have been removed for simplicity.
 */
class UpdatedMinusOperatorExample {

    @Test
    fun `demonstrate updated minus operator functionality`() {
        println("=== Updated Minus Operator Demo ===\n")
        
        // 1. Single values (unchanged)
        println("1. Single Values:")
        val repo1 = Rdf.memory()
        repo1.add {
            val person = iri("http://example.org/person")
            
            person - FOAF.name - "Alice"
            person - FOAF.age - 30
            person - FOAF.mbox - "alice@example.com"
        }
        
        val triples1 = repo1.defaultGraph.getTriples()
        println("   Created ${triples1.size} triples")
        triples1.forEach { println("   - $it") }
        println()
        
        // 2. Arrays create multiple individual triples
        println("2. Arrays - Multiple Individual Triples:")
        val repo2 = Rdf.memory()
        repo2.add {
            val person = iri("http://example.org/person")
            val friends = arrayOf(
                iri("http://example.org/friend1"),
                iri("http://example.org/friend2"),
                iri("http://example.org/friend3")
            )
            val emails = arrayOf("alice@example.com", "alice@work.com")
            
            person - FOAF.name - "Alice"
            person - FOAF.knows - friends  // Creates 3 individual triples
            person - FOAF.mbox - emails    // Creates 2 individual triples
        }
        
        val triples2 = repo2.defaultGraph.getTriples()
        println("   Created ${triples2.size} triples")
        triples2.forEach { println("   - $it") }
        println()
        
        // 3. Lists create RDF Lists (proper RDF structure)
        println("3. Lists - RDF Lists:")
        val repo3 = Rdf.memory()
        repo3.add {
            val person = iri("http://example.org/person")
            val friends = listOf(
                iri("http://example.org/friend1"),
                iri("http://example.org/friend2"),
                iri("http://example.org/friend3")
            )
            val subjects = listOf("Technology", "Programming", "RDF")
            
            person - FOAF.name - "Alice"
            person - FOAF.knows - friends   // Creates RDF List structure
            person - DCTERMS.subject - subjects  // Creates RDF List structure
        }
        
        val triples3 = repo3.defaultGraph.getTriples()
        println("   Created ${triples3.size} triples (includes RDF list structure)")
        triples3.forEach { println("   - $it") }
        println()
        
        // 4. Mixed types in arrays
        println("4. Mixed Types in Arrays:")
        val repo4 = Rdf.memory()
        repo4.add {
            val person = iri("http://example.org/person")
            val friend1 = iri("http://example.org/friend1")
            val friend2 = iri("http://example.org/friend2")
            val bnode = bnode("anon1")
            
            person - FOAF.name - "Alice"
            // Mixed types: IRI, BlankNode, String
            person - FOAF.knows - arrayOf(friend1, friend2, bnode)  // Creates 3 individual triples
        }
        
        val triples4 = repo4.defaultGraph.getTriples()
        println("   Created ${triples4.size} triples")
        triples4.forEach { println("   - $it") }
        println()
        
        // 5. Mixed types in lists (creates RDF List)
        println("5. Mixed Types in Lists (RDF List):")
        val repo5 = Rdf.memory()
        repo5.add {
            val person = iri("http://example.org/person")
            val friend1 = iri("http://example.org/friend1")
            val friend2 = iri("http://example.org/friend2")
            val bnode = bnode("anon1")
            
            person - FOAF.name - "Alice"
            // Mixed types: IRI, BlankNode, String, Int, Boolean
            person - DCTERMS.subject - listOf(friend1, friend2, bnode, "Technology", 42, true)
        }
        
        val triples5 = repo5.defaultGraph.getTriples()
        println("   Created ${triples5.size} triples (includes RDF list structure)")
        triples5.forEach { println("   - $it") }
        println()
        
        // 6. Empty list creates rdf:nil
        println("6. Empty List - rdf:nil:")
        val repo6 = Rdf.memory()
        repo6.add {
            val person = iri("http://example.org/person")
            
            person - FOAF.name - "Alice"
            person - FOAF.knows - emptyList<Any>()  // Creates rdf:nil
        }
        
        val triples6 = repo6.defaultGraph.getTriples()
        println("   Created ${triples6.size} triples")
        triples6.forEach { println("   - $it") }
        println()
        
        // 7. Standalone graph with both arrays and lists
        println("7. Standalone Graph - Arrays vs Lists:")
        val graph = Rdf.graph {
            val person = iri("http://example.org/person")
            val friends = listOf(
                iri("http://example.org/friend1"),
                iri("http://example.org/friend2")
            )
            val emails = arrayOf("alice@example.com", "alice@work.com")
            
            person - FOAF.name - "Alice"
            person - FOAF.knows - friends   // RDF List
            person - FOAF.mbox - emails     // Multiple individual triples
        }
        
        val graphTriples = graph.getTriples()
        println("   Created ${graphTriples.size} triples")
        graphTriples.forEach { println("   - $it") }
        println()
        
        println("=== Summary ===")
        println("Updated minus operator (-) behavior:")
        println("- Single values: Creates single triples")
        println("- Arrays: Creates multiple individual triples (efficient for multiple relationships)")
        println("- Lists: Creates RDF Lists (proper RDF structure for ordered collections)")
        println("- Empty lists: Creates rdf:nil")
        println("- Mixed types: Supported in both arrays and lists")
        println()
        println("Choose based on your needs:")
        println("- Use arrays when you want multiple individual triples (e.g., person knows friend1, friend2, friend3)")
        println("- Use lists when you want proper RDF List structure (e.g., ordered collections, RDF-compliant)")
        
        // Clean up
        repo1.close()
        repo2.close()
        repo3.close()
        repo4.close()
        repo5.close()
        repo6.close()
    }
}
