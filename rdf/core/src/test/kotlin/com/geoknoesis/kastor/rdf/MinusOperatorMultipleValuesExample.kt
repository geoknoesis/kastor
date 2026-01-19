package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import org.junit.jupiter.api.Test

/**
 * Example demonstrating the extended minus operator syntax for multiple values.
 * 
 * The minus operator (-) now supports multiple values in the object position,
 * allowing you to create multiple triples with the same subject-predicate pair
 * in a single, concise expression.
 */
class MinusOperatorMultipleValuesExample {

    @Test
    fun `demonstrate minus operator multiple values functionality`() {
        println("=== Minus Operator Multiple Values Demo ===\n")
        
        // 1. Basic multiple values with arrays
        println("1. Multiple Values with Arrays:")
        val repo1 = Rdf.memory()
        repo1.add {
            val person = Iri("http://example.org/person")
            val friends = arrayOf(
                Iri("http://example.org/friend1"),
                Iri("http://example.org/friend2"),
                Iri("http://example.org/friend3")
            )
            
            person - FOAF.name - "Alice"
            person - FOAF.knows - friends  // Creates 3 triples: person knows friend1, friend2, friend3
            person - FOAF.mbox - arrayOf("alice@example.com", "alice@work.com")  // Creates 2 triples
        }
        
        val triples1 = repo1.defaultGraph.getTriples()
        println("   Created ${triples1.size} triples")
        triples1.forEach { println("   - $it") }
        println()
        
        // 2. Multiple values with lists
        println("2. Multiple Values with Lists:")
        val repo2 = Rdf.memory()
        repo2.add {
            val document = Iri("http://example.org/document")
            val authors = listOf(
                Iri("http://example.org/author1"),
                Iri("http://example.org/author2"),
                Iri("http://example.org/author3")
            )
            val tags = listOf("Technology", "Programming", "Kotlin", "RDF")
            
            document - DCTERMS.title - "RDF in Kotlin"
            document - DCTERMS.creator - authors  // Creates 3 triples
            document - DCTERMS.subject - tags  // Creates 4 triples
        }
        
        val triples2 = repo2.defaultGraph.getTriples()
        println("   Created ${triples2.size} triples")
        triples2.forEach { println("   - $it") }
        println()
        
        // 3. Multiple values with pairs
        println("3. Multiple Values with Pairs:")
        val repo3 = Rdf.memory()
        repo3.add {
            val person = Iri("http://example.org/person")
            
            person - FOAF.name - "Bob"
            person - FOAF.homepage - ("http://bob.com" to "http://bob.blog.com")  // Creates 2 triples
            person - FOAF.knows - (Iri("http://example.org/friend1") to Iri("http://example.org/friend2"))  // Creates 2 triples
        }
        
        val triples3 = repo3.defaultGraph.getTriples()
        println("   Created ${triples3.size} triples")
        triples3.forEach { println("   - $it") }
        println()
        
        // 4. Multiple values with triples
        println("4. Multiple Values with Triples:")
        val repo4 = Rdf.memory()
        repo4.add {
            val resource = Iri("http://example.org/resource")
            
            resource - RDFS.label - "Sample Resource"
            resource - RDF.type - Triple("Document", "Report", "Publication")  // Creates 3 triples
            resource - DCTERMS.subject - Triple("Technology", "Programming", "RDF")  // Creates 3 triples
        }
        
        val triples4 = repo4.defaultGraph.getTriples()
        println("   Created ${triples4.size} triples")
        triples4.forEach { println("   - $it") }
        println()
        
        // 5. Mixed types in collections
        println("5. Mixed Types in Collections:")
        val repo5 = Rdf.memory()
        repo5.add {
            val person = Iri("http://example.org/person")
            val friend1 = Iri("http://example.org/friend1")
            val friend2 = Iri("http://example.org/friend2")
            val bnode = bnode("anon1")
            
            person - FOAF.name - "Charlie"
            // Mixed types: IRI, BlankNode, String
            person - FOAF.knows - arrayOf(friend1, friend2, bnode)  // Creates 3 triples
            
            // Mixed types: String, Int, Boolean
            person - DCTERMS.subject - listOf("Technology", 42, true)  // Creates 3 triples
        }
        
        val triples5 = repo5.defaultGraph.getTriples()
        println("   Created ${triples5.size} triples")
        triples5.forEach { println("   - $it") }
        println()
        
        // 6. Standalone graph with multiple values
        println("6. Standalone Graph with Multiple Values:")
        val graph = Rdf.graph {
            val project = Iri("http://example.org/project")
            val contributors = listOf(
                Iri("http://example.org/dev1"),
                Iri("http://example.org/dev2"),
                Iri("http://example.org/dev3")
            )
            
            project - RDFS.label - "Kastor RDF Library"
            project - DCTERMS.description - "A modern RDF library for Kotlin"
            project - DCTERMS.contributor - contributors  // Creates 3 triples
            project - DCTERMS.subject - arrayOf("RDF", "Kotlin", "DSL", "Library")  // Creates 4 triples
        }
        
        val graphTriples = graph.getTriples()
        println("   Created ${graphTriples.size} triples")
        graphTriples.forEach { println("   - $it") }
        println()
        
        // 7. Complex real-world example
        println("7. Complex Real-World Example:")
        val repo7 = Rdf.memory()
        repo7.add {
            val conference = Iri("http://example.org/conference/kotlinconf2024")
            val speakers = listOf(
                Iri("http://example.org/speaker/alice"),
                Iri("http://example.org/speaker/bob"),
                Iri("http://example.org/speaker/charlie"),
                Iri("http://example.org/speaker/diana")
            )
            val topics = arrayOf("Kotlin", "RDF", "DSL", "Semantic Web", "Graph Databases")
            val locations = ("Copenhagen" to "Denmark")
            
            conference - DCTERMS.title - "KotlinConf 2024"
            conference - DCTERMS.description - "Annual Kotlin conference"
            conference - DCTERMS.creator - speakers  // Creates 4 triples
            conference - DCTERMS.subject - topics  // Creates 5 triples
            conference - DCTERMS.coverage - locations  // Creates 2 triples
            conference - RDF.type - Triple("Event", "Conference", "Workshop")  // Creates 3 triples
        }
        
        val triples7 = repo7.defaultGraph.getTriples()
        println("   Created ${triples7.size} triples")
        triples7.forEach { println("   - $it") }
        println()
        
        println("=== Summary ===")
        println("The minus operator (-) now supports multiple values in the object position!")
        println("You can use:")
        println("- Arrays: person - FOAF.knows - arrayOf(friend1, friend2, friend3)")
        println("- Lists: document - DCTERMS.creator - listOf(author1, author2, author3)")
        println("- Pairs: person - FOAF.homepage - (url1 to url2)")
        println("- Triples: resource - RDF.type - Triple(type1, type2, type3)")
        println("- Mixed types: person - DCTERMS.subject - listOf(\"Tech\", 42, true)")
        println()
        println("This creates multiple triples efficiently in a single, readable expression!")
        
        // Clean up
        repo1.close()
        repo2.close()
        repo3.close()
        repo4.close()
        repo5.close()
        repo7.close()
    }
}









