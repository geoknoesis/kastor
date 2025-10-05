package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import org.junit.jupiter.api.Test

/**
 * Demonstration of the concept for handling multiple values for the same property.
 * 
 * This shows different approaches to handle multiple values in RDF:
 * 1. Multiple individual statements (current working approach)
 * 2. Helper functions for collections
 * 3. Future: TTL-style vararg syntax (conceptual)
 */
class MultipleValuesConceptDemo {

    @Test
    fun `demonstrate multiple values concept`() {
        val repo = Rdf.memory()
        
        println("=== Multiple Values for Same Property Demo ===\n")
        
        // Current working approach: Multiple individual statements
        println("1. Current Approach - Multiple Individual Statements:")
        repo.add {
            val person = iri("http://example.org/person")
            val friend1 = iri("http://example.org/friend1")
            val friend2 = iri("http://example.org/friend2")
            val friend3 = iri("http://example.org/friend3")
            
            person[FOAF.name] = "Alice"
            person[FOAF.knows] = friend1
            person[FOAF.knows] = friend2
            person[FOAF.knows] = friend3
            person[FOAF.mbox] = "alice@example.com"
            person[FOAF.mbox] = "alice@work.com"
        }
        
        val allTriples = repo.defaultGraph.getTriples()
        println("   Created ${allTriples.size} triples")
        
        val personTriples = allTriples.filter { it.subject == iri("http://example.org/person") }
        val knowsTriples = personTriples.filter { it.predicate == FOAF.knows }
        val mboxTriples = personTriples.filter { it.predicate == FOAF.mbox }
        
        println("   - Person has ${personTriples.size} properties")
        println("   - ${knowsTriples.size} knows relationships")
        println("   - ${mboxTriples.size} email addresses")
        println()
        
        // Helper function approach
        println("2. Helper Function Approach:")
        repo.add {
            val person2 = iri("http://example.org/person2")
            val friends = listOf(
                iri("http://example.org/friend4"),
                iri("http://example.org/friend5"),
                iri("http://example.org/friend6")
            )
            
            person2[FOAF.name] = "Bob"
            
            // Helper function to add multiple values
            friends.forEach { friend ->
                person2[FOAF.knows] = friend
            }
            
            listOf("bob@example.com", "bob@work.com", "bob@personal.com").forEach { email ->
                person2[FOAF.mbox] = email
            }
        }
        
        val allTriples2 = repo.defaultGraph.getTriples()
        val person2Triples = allTriples2.filter { it.subject == iri("http://example.org/person2") }
        val knows2Triples = person2Triples.filter { it.predicate == FOAF.knows }
        val mbox2Triples = person2Triples.filter { it.predicate == FOAF.mbox }
        
        println("   Created ${allTriples2.size} total triples")
        println("   - Person2 has ${person2Triples.size} properties")
        println("   - ${knows2Triples.size} knows relationships")
        println("   - ${mbox2Triples.size} email addresses")
        println()
        
        // Conceptual TTL-style vararg syntax (not implemented yet)
        println("3. Conceptual TTL-style Vararg Syntax (Future):")
        println("   // This would be the ideal syntax:")
        println("   person.properties {")
        println("       name(\"Alice\")")
        println("       knows(friend1, friend2, friend3)  // Multiple values in one call")
        println("       mbox(\"alice@example.com\", \"alice@work.com\")")
        println("   }")
        println()
        
        // Document metadata example
        println("4. Document Metadata with Multiple Values:")
        repo.add {
            val document = iri("http://example.org/document")
            
            // Multiple titles
            document[DCTERMS.title] = "Main Title"
            document[DCTERMS.title] = "Subtitle"
            document[DCTERMS.title] = "Alternative Title"
            
            // Multiple creators
            document[DCTERMS.creator] = iri("http://example.org/author1")
            document[DCTERMS.creator] = iri("http://example.org/author2")
            
            // Multiple subjects
            document[DCTERMS.subject] = "Technology"
            document[DCTERMS.subject] = "Programming"
            document[DCTERMS.subject] = "Kotlin"
        }
        
        val allTriples3 = repo.defaultGraph.getTriples()
        val documentTriples = allTriples3.filter { it.subject == iri("http://example.org/document") }
        val titleTriples = documentTriples.filter { it.predicate == DCTERMS.title }
        val creatorTriples = documentTriples.filter { it.predicate == DCTERMS.creator }
        val subjectTriples = documentTriples.filter { it.predicate == DCTERMS.subject }
        
        println("   Created ${allTriples3.size} total triples")
        println("   - Document has ${documentTriples.size} properties")
        println("   - ${titleTriples.size} titles")
        println("   - ${creatorTriples.size} creators")
        println("   - ${subjectTriples.size} subjects")
        println()
        
        println("=== Summary ===")
        println("Multiple values for the same property are fully supported!")
        println("You can use any of these approaches:")
        println("- Multiple individual statements (most explicit)")
        println("- Helper functions with collections (functional approach)")
        println("- Future: TTL-style vararg syntax (most concise)")
        
        repo.close()
    }
    
    @Test
    fun `demonstrate practical examples`() {
        val repo = Rdf.memory()
        
        println("\n=== Practical Examples ===\n")
        
        // Example 1: Person with multiple social connections
        println("Example 1: Person with Multiple Social Connections")
        repo.add {
            val person = iri("http://example.org/person")
            val socialConnections = listOf(
                iri("http://example.org/friend1"),
                iri("http://example.org/friend2"),
                iri("http://example.org/colleague1"),
                iri("http://example.org/colleague2")
            )
            
            person[FOAF.name] = "Alice"
            person[FOAF.age] = 30
            
            // Add multiple social connections
            socialConnections.forEach { connection ->
                person[FOAF.knows] = connection
            }
            
            // Multiple contact methods
            person[FOAF.mbox] = "alice@example.com"
            person[FOAF.mbox] = "alice@work.com"
            person[FOAF.homepage] = "http://alice.example.com"
            person[FOAF.homepage] = "http://alice.blog.com"
        }
        
        val personTriples = repo.defaultGraph.getTriples().filter { 
            it.subject == iri("http://example.org/person") 
        }
        println("   Alice has ${personTriples.size} properties")
        println("   - ${personTriples.count { it.predicate == FOAF.knows }} social connections")
        println("   - ${personTriples.count { it.predicate == FOAF.mbox }} email addresses")
        println("   - ${personTriples.count { it.predicate == FOAF.homepage }} websites")
        println()
        
        // Example 2: Document with multiple metadata
        println("Example 2: Document with Multiple Metadata")
        repo.add {
            val document = iri("http://example.org/document")
            
            // Multiple titles for different languages
            document[DCTERMS.title] = "RDF in Kotlin"
            document[DCTERMS.title] = "RDF en Kotlin"
            document[DCTERMS.title] = "RDF mit Kotlin"
            
            // Multiple authors
            document[DCTERMS.creator] = iri("http://example.org/author1")
            document[DCTERMS.creator] = iri("http://example.org/author2")
            document[DCTERMS.creator] = iri("http://example.org/author3")
            
            // Multiple subjects/tags
            val tags = listOf("RDF", "Kotlin", "Programming", "Semantic Web", "DSL")
            tags.forEach { tag ->
                document[DCTERMS.subject] = tag
            }
            
            // Multiple dates (creation, modification, publication)
            document[DCTERMS.created] = "2023-01-01"
            document[DCTERMS.modified] = "2023-06-01"
            document[DCTERMS.date] = "2023-12-25"
        }
        
        val documentTriples = repo.defaultGraph.getTriples().filter { 
            it.subject == iri("http://example.org/document") 
        }
        println("   Document has ${documentTriples.size} properties")
        println("   - ${documentTriples.count { it.predicate == DCTERMS.title }} titles")
        println("   - ${documentTriples.count { it.predicate == DCTERMS.creator }} authors")
        println("   - ${documentTriples.count { it.predicate == DCTERMS.subject }} subjects/tags")
        println("   - ${documentTriples.count { it.predicate == DCTERMS.created || it.predicate == DCTERMS.modified || it.predicate == DCTERMS.date }} dates")
        println()
        
        // Example 3: Resource with multiple types
        println("Example 3: Resource with Multiple Types")
        repo.add {
            val resource = iri("http://example.org/resource")
            
            // Multiple types (common in RDF)
            resource[RDF.type] = RDF.Property
            resource[RDF.type] = RDFS.Resource
            resource[RDF.type] = iri("http://example.org/CustomType")
            resource[RDF.type] = iri("http://example.org/AnotherType")
            
            // Multiple labels for different languages
            resource[RDFS.label] = "Property"
            resource[RDFS.label] = "Propriété"
            resource[RDFS.label] = "Eigenschaft"
            
            // Multiple comments
            resource[RDFS.comment] = "A custom property definition"
            resource[RDFS.comment] = "Used for demonstration purposes"
        }
        
        val resourceTriples = repo.defaultGraph.getTriples().filter { 
            it.subject == iri("http://example.org/resource") 
        }
        println("   Resource has ${resourceTriples.size} properties")
        println("   - ${resourceTriples.count { it.predicate == RDF.type }} types")
        println("   - ${resourceTriples.count { it.predicate == RDFS.label }} labels")
        println("   - ${resourceTriples.count { it.predicate == RDFS.comment }} comments")
        
        repo.close()
    }
}
