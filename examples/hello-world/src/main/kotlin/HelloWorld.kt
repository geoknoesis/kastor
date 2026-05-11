/**
 * Kastor Hello World Example
 * 
 * This is a simple, runnable example that demonstrates:
 * - Creating an RDF repository
 * - Adding RDF data using the DSL
 * - Querying the data with SPARQL
 * - Serializing the graph
 * 
 * Run this example:
 *   ./gradlew :examples:hello-world:run
 *   or
 *   ./gradlew helloWorld
 */

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF

fun main() {
    println("=== Kastor Hello World ===\n")
    
    // Create an in-memory repository (using Jena for SPARQL support)
    // Note: For a simple example, we'll query the graph directly instead of using SPARQL
    val repo = Rdf.memory()
    println("✓ Created in-memory repository")
    
    // Add RDF data using the DSL
    repo.add {
        prefixes {
            "foaf" to "http://xmlns.com/foaf/0.1/"
            "ex" to "http://example.org/"
        }
        
        val alice = iri("http://example.org/alice")
        alice has FOAF.name with "Alice"
        alice has FOAF.age with 30
        alice - RDF.type - FOAF.Person
        
        val bob = iri("http://example.org/bob")
        bob has FOAF.name with "Bob"
        bob has FOAF.age with 25
        bob - RDF.type - FOAF.Person
        bob has FOAF.knows with alice
    }
    println("✓ Added RDF data using DSL")
    
    // Query the data directly from the graph
    println("\n✓ Query Results:")
    val persons = repo.defaultGraph.getTriples()
        .filter { it.predicate == RDF.type && it.obj == FOAF.Person }
        .map { it.subject }
    
    persons.forEach { person ->
        val nameTriple = repo.defaultGraph.getTriples()
            .firstOrNull { it.subject == person && it.predicate == FOAF.name }
        val ageTriple = repo.defaultGraph.getTriples()
            .firstOrNull { it.subject == person && it.predicate == FOAF.age }
        
        val name = (nameTriple?.obj as? Literal)?.lexical ?: "Unknown"
        val age = (ageTriple?.obj as? Literal)?.lexical?.toIntOrNull() ?: 0
        println("  - $name is $age years old")
    }
    
    // Serialize the graph (if supported)
    try {
        val turtle = repo.defaultGraph.serialize(RdfFormat.TURTLE)
        println("\n✓ Serialized Graph (Turtle):")
        println(turtle)
    } catch (e: Exception) {
        println("\n✓ Serialization not available for memory repository")
        println("  (Use Jena or RDF4J provider for serialization support)")
    }
    
    println("\n=== Done ===")
}

