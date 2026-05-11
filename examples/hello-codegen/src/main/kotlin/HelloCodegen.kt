/**
 * Kastor Hello Codegen Example
 * 
 * This is a minimal example demonstrating Kastor Gen code generation.
 * It shows how to:
 * - Define a simple SHACL shape
 * - Generate Kotlin interfaces from SHACL
 * - Use the generated interfaces
 * 
 * Run this example:
 *   ./gradlew :examples:hello-codegen:run
 *   or
 *   ./gradlew helloCodegen
 * 
 * To generate code:
 *   ./gradlew :examples:hello-codegen:kspKotlin
 */

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF

// Note: To enable code generation, uncomment the annotation below and run:
// ./gradlew :examples:hello-codegen:kspKotlin
//
// @GenerateFromOntology(
//     shaclPath = "person-shape.ttl",
//     contextPath = "person-context.jsonld",
//     packageName = "com.example.hello"
// )
// class PersonGenerator

fun main() {
    println("=== Kastor Hello Codegen ===\n")
    
    println("This example demonstrates Kastor Gen code generation.")
    println("The @GenerateFromOntology annotation on PersonGenerator class")
    println("will trigger generation of Person interface from the SHACL shape.\n")
    
    // Create repository
    val repo = Rdf.memory()
    println("✓ Created repository")
    
    // Add RDF data that matches the Person shape
    repo.add {
        prefixes {
            "ex" to "http://example.org/"
            "foaf" to "http://xmlns.com/foaf/0.1/"
        }
        
        val alice = iri("http://example.org/alice")
        alice - RDF.type - iri("http://example.org/Person")
        alice - iri("http://example.org/name") - "Alice"
        alice - iri("http://example.org/age") - 30
    }
    println("✓ Added RDF data matching Person shape")
    
    // Show the data
    val aliceIri = iri("http://example.org/alice")
    println("✓ Created resource IRI")
    
    // Access properties directly from RDF
    val nameTriple = repo.defaultGraph.getTriples()
        .firstOrNull { it.subject == aliceIri && it.predicate == iri("http://example.org/name") }
    val ageTriple = repo.defaultGraph.getTriples()
        .firstOrNull { it.subject == aliceIri && it.predicate == iri("http://example.org/age") }
    
    val name = nameTriple?.obj as? Literal
    val age = ageTriple?.obj as? Literal
    
    println("\n✓ Data accessed from RDF:")
    println("  Name: ${name?.lexical}")
    println("  Age: ${age?.lexical}")
    
    println("\nTo use generated interfaces:")
    println("  1. Run: ./gradlew :examples:hello-codegen:kspKotlin")
    println("  2. Import: import com.example.hello.Person")
    println("  3. Use: val alice: Person = aliceRef.asType<Person>()")
    println("  4. Access: println(\"Name: \${alice.name}, Age: \${alice.age}\")")
    
    println("\n=== Done ===")
}

