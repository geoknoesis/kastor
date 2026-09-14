# Getting Started with Kastor Gen

This tutorial will walk you through creating your first Kastor Gen application step by step.

> 💡 **Why Kastor Gen?** Generate type-safe domain interfaces automatically from SHACL/JSON-LD ontologies. 
> **90% less manual code**, **100% consistency** with your ontology, **compile-time type safety**. 
> [See detailed benefits →](../getting-started/benefits.md)

## Prerequisites

- JDK 21+
- Kotlin 2.4 and KSP 2.3 (the Kastor build uses Kotlin 2.4.20 and KSP 2.3.12)
- Basic understanding of Kotlin
- Familiarity with RDF concepts (helpful but not required)

> **Fastest start:** clone the [Kastor repository](https://github.com/geoknoesis/kastor) and run
> `./gradlew :examples:hello-codegen:run`. That module generates a `Person` interface and wrapper from
> `person-shape.ttl` with the KSP processor and materializes RDF data with it.
> `./gradlew :examples:dcat-us:runGeneratedExample` does the same for the DCAT-US 3.0 shapes.

## Step 1: Project Setup

Kastor Gen artifacts are **not yet published** to a public repository. Use one of:

- **A module inside the Kastor build** (what the examples do), with project dependencies:

  ```kotlin
  plugins {
      id("org.jetbrains.kotlin.jvm")
      id("com.google.devtools.ksp")
  }

  dependencies {
      implementation(project(":kastor-gen:runtime"))
      ksp(project(":kastor-gen:processor"))

      // An RDF provider, e.g. for Rdf.memory()
      implementation(project(":rdf:jena"))

      // Optional: SHACL validation
      implementation(project(":kastor-gen:validation-jena"))
  }
  ```

- **Your own project via Maven Local.** In a Kastor checkout run

  ```bash
  ./gradlew publishToMavenLocal
  ```

  then add `mavenLocal()` to your repositories and use the published coordinates with the Kastor build's
  project version:

  ```kotlin
  plugins {
      kotlin("jvm") version "2.4.20"
      id("com.google.devtools.ksp") version "2.3.12"
  }

  repositories {
      mavenLocal()
      mavenCentral()
  }

  dependencies {
      implementation("com.geoknoesis.kastor:kastor-gen-runtime:0.3.0-SNAPSHOT")
      ksp("com.geoknoesis.kastor:kastor-gen-processor:0.3.0-SNAPSHOT")
  }
  ```

The processor needs no KSP arguments for hand-written `@Rdf` interfaces. When you generate from SHACL files
(`@Rdf(shacl = …)`), KSP cannot see edits to those files and the processor warns about each one it reads;
declare `src/main/resources` as an input of the `kspKotlin` task and set
`kastor.gen.resources.tracked=true` as shown in [Incremental Builds](../guides/incremental-builds.md#ksp-processor).
All options are listed in the [processor reference](../reference/processor.md#processor-options).

## Step 2: Create Your First Domain Interface

Let's create a simple `Person` interface:

```kotlin
// src/main/kotlin/com/example/Person.kt
package com.example

import com.geoknoesis.kastor.gen.annotations.Rdf
import com.geoknoesis.kastor.rdf.vocab.FOAF

@Rdf(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @Rdf(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>
    
    @Rdf(iri = "http://xmlns.com/foaf/0.1/age")
    val age: List<Int>
    
    @Rdf(iri = "http://xmlns.com/foaf/0.1/mbox")
    val email: List<String>
}
```

## Step 3: Build and Generate Code

Run the build to generate wrapper classes:

```bash
./gradlew build
```

This will generate wrapper classes in `build/generated/ksp/main/kotlin/` that implement your domain interface and provide RDF side-channel access.

## Step 4: Create RDF Data

Let's create some sample RDF data:

```kotlin
// src/main/kotlin/com/example/Main.kt
package com.example

import com.geoknoesis.kastor.gen.runtime.*
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF

fun main() {
    // Create an in-memory RDF repository
    val repo = Rdf.memory()
    
    // Add some sample data
    repo.add {
        val alice = iri("http://example.org/alice")
        val bob = iri("http://example.org/bob")
        
        // Alice's data
        alice - FOAF.name - "Alice Johnson"
        alice - FOAF.age - 30
        alice - FOAF.mbox - "alice@example.com"
        alice - FOAF.knows - bob
        
        // Bob's data
        bob - FOAF.name - "Bob Smith"
        bob - FOAF.age - 25
        bob - FOAF.mbox - "bob@example.com"
    }
    
    println("RDF data created successfully!")
}
```

## Step 5: Materialize Domain Objects

Now let's materialize RDF data into domain objects:

```kotlin
fun main() {
    // ... (previous code)
    
    // Materialize Alice as a Person object
    val aliceRef = RdfRef(iri("http://example.org/alice"), repo.defaultGraph)
    val alice: Person = aliceRef.asType()
    
    // Use the domain interface
    println("Name: ${alice.name.firstOrNull()}")
    println("Age: ${alice.age.firstOrNull()}")
    println("Email: ${alice.email.firstOrNull()}")
    
    // Output:
    // Name: Alice Johnson
    // Age: 30
    // Email: alice@example.com
}
```

## Step 6: Access RDF Side-Channel

Let's explore the RDF side-channel capabilities:

```kotlin
fun main() {
    // ... (previous code)
    
    // Access the RDF side-channel
    val rdfHandle = alice.asRdf()
    
    // Get the underlying RDF node and graph
    println("Node: ${rdfHandle.node}")
    println("Graph: ${rdfHandle.graph}")
    
    // Access unmapped properties (extras)
    val extras = rdfHandle.extras
    val allPredicates = extras.predicates()
    println("All predicates: ${allPredicates.map { it.value }}")
    
    // Check if there are any unmapped properties
    if (allPredicates.isNotEmpty()) {
        println("Unmapped properties found:")
        allPredicates.forEach { pred ->
            val values = extras.values(pred)
            println("  ${pred.value}: ${values.map { it.toString() }}")
        }
    }
}
```

## Step 7: Add Validation (Optional)

If you added validation dependencies, you can enable SHACL validation:

```kotlin
fun main() {
    // ... (previous code)
    
    // Materialize with validation
    val validation = JenaValidation.fromTurtle(shapesTtl) // shapesTtl: your SHACL shapes as Turtle text
    val alice: Person = aliceRef.asValidatedType(validation)
    println("Validation passed!")
    
    // Or validate manually (using the same context)
    val rdfHandle = alice.asRdf()
    rdfHandle.validateOrThrow()
    println("Manual validation passed!")
}
```

## Step 8: Complete Example

Here's the complete working example:

```kotlin
package com.example

import com.geoknoesis.kastor.gen.runtime.*
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF

@Rdf(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @Rdf(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>
    
    @Rdf(iri = "http://xmlns.com/foaf/0.1/age")
    val age: List<Int>
    
    @Rdf(iri = "http://xmlns.com/foaf/0.1/mbox")
    val email: List<String>
}

fun main() {
    // Create RDF repository and data
    val repo = Rdf.memory()
    repo.add {
        val alice = iri("http://example.org/alice")
        alice - FOAF.name - "Alice Johnson"
        alice - FOAF.age - 30
        alice - FOAF.mbox - "alice@example.com"
    }
    
    // Materialize domain object
    val aliceRef = RdfRef(iri("http://example.org/alice"), repo.defaultGraph)
    val alice: Person = aliceRef.asType()
    
    // Use domain interface
    println("=== Domain Interface Usage ===")
    println("Name: ${alice.name.firstOrNull()}")
    println("Age: ${alice.age.firstOrNull()}")
    println("Email: ${alice.email.firstOrNull()}")
    
    // Access RDF side-channel
    println("\n=== RDF Side-Channel Access ===")
    val rdfHandle = alice.asRdf()
    val extras = rdfHandle.extras
    val predicates = extras.predicates()
    
    if (predicates.isNotEmpty()) {
        println("Unmapped properties:")
        predicates.forEach { pred ->
            val values = extras.values(pred)
            println("  ${pred.value}: ${values.map { it.toString() }}")
        }
    } else {
        println("No unmapped properties found")
    }
    
    repo.close()
}
```

## What's Next?

Congratulations! You've successfully created your first Kastor Gen application. Here's what you've learned:

- ✅ How to set up Kastor Gen in a Kotlin project
- ✅ How to define domain interfaces with RDF annotations
- ✅ How to create and work with RDF data
- ✅ How to materialize RDF data into domain objects
- ✅ How to access RDF side-channels for advanced functionality

## Next Steps

- **Learn about [Core Concepts](core-concepts.md)** - Understand the side-channel architecture
- **Explore [Domain Modeling](domain-modeling.md)** - Learn best practices for domain interfaces
- **Check out [RDF Integration](rdf-integration.md)** - Advanced RDF side-channel usage
- **See [Practical Examples](../examples/README.md)** - Real-world use cases

## Troubleshooting

### Common Issues

**Q: Build fails with "No wrapper factory registered"**
A: Make sure KSP is properly configured and the build completed successfully. The wrapper classes need to be generated first.

**Q: Annotations not found**
A: Ensure you import `com.geoknoesis.kastor.gen.annotations.Rdf` (and `Prefix` when you declare prefix maps).

**Q: Validation not working**
A: Check that you've added the validation dependencies and that a ValidationContext is registered.

### Getting Help

- Check the [FAQ](../faq.md) for common questions
- Look at [Examples](../examples/README.md) for more patterns
- Review the [API Reference](../reference/README.md) for detailed documentation



