# 🚀 Quick Start Guide

{% include version-banner.md %}

Get up and running with Kastor RDF in minutes! This guide will walk you through the essential steps to create your first RDF application.

## 📋 Prerequisites

- **Kotlin 1.9+** installed on your system
- **Gradle** build system
- Basic understanding of **RDF concepts** (optional - we'll cover the basics)

## 🏗️ Installation

### 1. Add Dependencies

Add the following to your `build.gradle.kts`:

```kotlin
dependencies {
    // Core API
    implementation("com.geoknoesis.kastor:rdf-core:0.1.0")
    
    // Choose your backend (or both)
    implementation("com.geoknoesis.kastor:rdf-jena:0.1.0")    // Apache Jena backend
    implementation("com.geoknoesis.kastor:rdf-rdf4j:0.1.0")   // Eclipse RDF4J backend
}

repositories {
    mavenCentral()
    // Add if using snapshot versions
    // maven { url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots") }
}
```

### 2. Import the API

```kotlin
import com.geoknoesis.kastor.rdf.*
```

## 🎯 Your First RDF Application

### Step 1: Create a Repository

```kotlin
fun main() {
    // Create an in-memory repository
    val repo = Rdf.memory()
    
    println("✅ Repository created successfully!")
}
```

### Step 2: Add Some Data

```kotlin
fun main() {
    val repo = Rdf.memory()
    
    // Define vocabulary objects for clean organization
    object PersonVocab {
        val name = iri("http://example.org/person/name")
        val age = iri("http://example.org/person/age")
        val email = iri("http://example.org/person/email")
        val worksFor = iri("http://example.org/person/worksFor")
    }
    
    object CompanyVocab {
        val name = iri("http://example.org/company/name")
        val industry = iri("http://example.org/company/industry")
    }
    
    // Add data using the elegant DSL
    repo.add {
        val alice = iri("http://example.org/person/alice")
        val company = iri("http://example.org/company/tech")
        
        // Ultra-compact syntax
        alice[PersonVocab.name] = "Alice Johnson"
        alice[PersonVocab.age] = 30
        alice[PersonVocab.email] = "alice@example.com"
        alice[PersonVocab.worksFor] = company
        
        // Company information
        company[CompanyVocab.name] = "Tech Innovations Inc."
        company[CompanyVocab.industry] = "Software Development"
    }
    
    println("✅ Data added successfully!")
}
```

### Step 3: Query Your Data

```kotlin
fun main() {
    val repo = Rdf.memory()
    
    // ... (previous code for adding data) ...
    
    // Query the data
    val results = repo.select(SparqlSelectQuery("""
        SELECT ?name ?age ?email ?company
        WHERE {
            ?person <http://example.org/person/name> ?name ;
                    <http://example.org/person/age> ?age ;
                    <http://example.org/person/email> ?email ;
                    <http://example.org/person/worksFor> ?company .
        }
    """))
    
    // Process results
    results.forEach { binding ->
        val name = binding.getString("name")
        val age = binding.getInt("age")
        val email = binding.getString("email")
        val company = binding.getString("company")
        
        println("👤 $name (age $age): $email")
        println("   🏢 Works for: $company")
    }
    
    println("✅ Query executed successfully!")
}
```

### Step 4: Clean Up

```kotlin
fun main() {
    val repo = Rdf.memory()
    
    // ... (previous code) ...
    
    // Always close your repository
    repo.close()
    
    println("✅ Application completed successfully!")
}
```

## 🎨 Multiple Ways to Add Data

Kastor RDF provides multiple syntax styles to suit your preferences:

### 1. Ultra-Compact Syntax (Most Concise)

```kotlin
repo.add {
    val person = iri("http://example.org/person/john")
    val namePred = iri("http://example.org/person/name")
    val agePred = iri("http://example.org/person/age")
    val emailPred = iri("http://example.org/person/email")
    
    person[namePred] = "John Doe"
    person[agePred] = 25
    person[emailPred] = "john@example.com"
}
```

### 2. Natural Language Syntax (Most Explicit)

```kotlin
repo.add {
    val person = iri("http://example.org/person/jane")
    val namePred = iri("http://example.org/person/name")
    val agePred = iri("http://example.org/person/age")
    val emailPred = iri("http://example.org/person/email")
    
    person has namePred with "Jane Smith"
    person has agePred with 28
    person has emailPred with "jane@example.com"
}
```

### 3. Generic Infix Operator (Natural Flow)

```kotlin
repo.add {
    val person = iri("http://example.org/person/bob")
    val namePred = iri("http://example.org/person/name")
    val agePred = iri("http://example.org/person/age")
    val emailPred = iri("http://example.org/person/email")
    
    person has namePred with "Bob Wilson"
    person has agePred with 32
    person has emailPred with "bob@example.com"
}
```

### 4. Minus Operator (New values() & list() Functions)

```kotlin
repo.add {
    val person = iri("http://example.org/person/bob")
    val namePred = iri("http://example.org/person/name")
    val agePred = iri("http://example.org/person/age")
    val emailPred = iri("http://example.org/person/email")
    
    person has namePred with "Bob Wilson"
    person has agePred with 32
    person has emailPred with "bob@example.com"
}

// 3. Minus operator syntax (new!)
repo.add {
    val person = iri("http://example.org/person/charlie")
    val namePred = iri("http://example.org/person/name")
    val agePred = iri("http://example.org/person/age")
    val emailPred = iri("http://example.org/person/email")
    
    person - namePred - "Charlie Brown"
    person - agePred - 25
    person - emailPred - "charlie@example.com"
    
    // Multiple values with curly braces
    person - FOAF.knows - values(friend1, friend2, friend3)
    
    // RDF lists with parentheses
    person - FOAF.mbox - list("charlie@example.com", "charlie@work.com")
    
    // RDF containers
    person - DCTERMS.subject - bag("Technology", "AI", "RDF")     // rdf:Bag
    person - FOAF.knows - seq(friend1, friend2, friend3)         // rdf:Seq
    person - FOAF.mbox - alt("charlie@example.com", "charlie@work.com")  // rdf:Alt
}
```

### 5. Operator Overloads (Minimal Syntax)

```kotlin
val person = iri("http://example.org/person/sarah")
val name = iri("http://example.org/person/name")

val triple = person -> (name to "Sarah Johnson")
repo.addTriple(triple)
```

### 5. Convenience Functions (Explicit)

```kotlin
val person = iri("http://example.org/person/mike")
val name = iri("http://example.org/person/name")

val triple = triple(person, name, "Mike Brown")
repo.addTriple(triple)
```

## 🔄 Chaining Operations

Perform multiple operations in sequence:

```kotlin
repo.add {
    val person = iri("http://example.org/person/fluent")
    val namePred = iri("http://example.org/person/name")
    val agePred = iri("http://example.org/person/age")
    person[namePred] = "Fluent User"
    person[agePred] = 35
}

val namePred = iri("http://example.org/person/name")
val agePred = iri("http://example.org/person/age")
val results = repo.select(SparqlSelectQuery(
    "SELECT ?name ?age WHERE { ?person ${namePred} ?name ; ${agePred} ?age }"
))
results.forEach { binding ->
    println("${binding.getString("name")} is ${binding.getInt("age")} years old")
}

repo.clear()
```

## ⚡ Performance Monitoring

Monitor your application's performance:

```kotlin
// Time query execution
val namePred = iri("http://example.org/person/name")
val started = System.nanoTime()
val results = repo.select(SparqlSelectQuery("""
    SELECT ?name WHERE { ?person ${namePred} ?name }
"""))
val queryDurationMs = (System.nanoTime() - started) / 1_000_000
println("Query executed in: ${queryDurationMs}ms")
```

## 📦 Batch Operations

Process large datasets efficiently:

```kotlin
// Create many people efficiently
val people = (1..1000).map { i ->
    iri("http://example.org/person/person$i")
}

val namePred = iri("http://example.org/person/name")
val agePred = iri("http://example.org/person/age")

// Add in a single DSL block
repo.add {
    people.forEachIndexed { index, person ->
        person[namePred] = "Person ${index + 1}"
        person[agePred] = 20 + (index % 50)
    }
}
```

## 🔍 Advanced Query Features

Use convenient query methods:

```kotlin
val namePred = iri("http://example.org/person/name")
val agePred = iri("http://example.org/person/age")

// Get first result directly
val firstPerson = repo.select(SparqlSelectQuery("""
    SELECT ?name WHERE { ?person ${namePred} ?name } LIMIT 1
""")).first()
println("First person: ${firstPerson?.getString("name")}")

// Get results as a map
val nameAgeMap = repo.select(SparqlSelectQuery(
    "SELECT ?name ?age WHERE { ?person ${namePred} ?name ; ${agePred} ?age }"
)).asSequence()
    .mapNotNull { binding ->
        val name = binding.getString("name")
        val age = binding.getInt("age")
        if (name != null && age != null) name to age.toString() else null
    }
    .toMap()
println("Name-Age Map: $nameAgeMap")

// Get results as specific types
val names: List<String> = repo.select(SparqlSelectQuery("""
    SELECT ?name WHERE { ?person ${namePred} ?name }
""")).asSequence()
    .mapNotNull { it.getString("name") }
    .toList()
println("Names: $names")
```

## 💼 Transaction Operations

Ensure data consistency with transactions:

```kotlin
val namePred = iri("http://example.org/person/name")
val agePred = iri("http://example.org/person/age")

repo.transaction {
    // All operations in this block are atomic
    add {
        val person = iri("http://example.org/person/atomic")
        person[namePred] = "Atomic User"
        person[agePred] = 40
    }
    
    // If any operation fails, all changes are rolled back
    println("Transaction completed successfully!")
}
```

## 📊 Repository Types

Choose the right repository for your needs:

### In-Memory Repository (Default)

```kotlin
val repo = Rdf.memory()  // Fast, temporary storage
```

### Persistent Repository

```kotlin
val repo = Rdf.persistent("my-data")  // Data persists between runs
```

### Repository with Inference

```kotlin
val repo = Rdf.memoryWithInference()  // Automatic RDFS reasoning
```

### Custom Configuration

```kotlin
val repo = Rdf.repository {
    providerId = "jena"
    variantId = "tdb2"
    location = "custom-data"
    inference = true
    optimization = true
    cacheSize = 2000
    maxMemory = "2GB"
}
```

## 🎯 Next Steps

Now that you've completed the quick start, here's what to explore next:

### 📚 Documentation
- **[Compact DSL Guide](../api/compact-dsl-guide.md)** - Multiple syntax styles
- **[RDF Fundamentals](../concepts/rdf-fundamentals.md)** - Understanding RDF basics
- **[SPARQL Fundamentals](../concepts/sparql-fundamentals.md)** - Query language introduction

### 🎯 Examples
- **[Examples Overview](../examples/README.md)** - Complete working examples

### 🛠️ Tutorials
- **[Hello World Tutorial](../tutorials/hello-world.md)** - Your first RDF application
- **[Load and Query Tutorial](../tutorials/load-and-query.md)** - Working with data
- **[Remote Endpoint Tutorial](../tutorials/remote-endpoint.md)** - Connecting to remote repositories

## 🚀 Complete Example

Here's a complete example that demonstrates all the key features:

```kotlin
import com.geoknoesis.kastor.rdf.*

fun main() {
    println("🚀 === Kastor RDF Quick Start ===\n")
    
    // Create repository
    val repo = Rdf.memory()
    println("✅ Repository created")
    
    // Define vocabularies
    object PersonVocab {
        val name = iri("http://example.org/person/name")
        val age = iri("http://example.org/person/age")
        val email = iri("http://example.org/person/email")
        val worksFor = iri("http://example.org/person/worksFor")
    }
    
    object CompanyVocab {
        val name = iri("http://example.org/company/name")
        val industry = iri("http://example.org/company/industry")
    }
    
    // Add data using fluent interface
    repo.add {
        val alice = iri("http://example.org/person/alice")
        val company = iri("http://example.org/company/tech")
        
        // Ultra-compact syntax
        alice[PersonVocab.name] = "Alice Johnson"
        alice[PersonVocab.age] = 30
        alice[PersonVocab.email] = "alice@example.com"
        alice[PersonVocab.worksFor] = company
        
        // Company information
        company[CompanyVocab.name] = "Tech Innovations Inc."
        company[CompanyVocab.industry] = "Software Development"
    }
    
    val results = repo.select(SparqlSelectQuery("""
        SELECT ?name ?age ?email ?company
        WHERE {
            ?person <http://example.org/person/name> ?name ;
                    <http://example.org/person/age> ?age ;
                    <http://example.org/person/email> ?email ;
                    <http://example.org/person/worksFor> ?company .
        }
    """))
    results.forEach { binding ->
        val name = binding.getString("name")
        val age = binding.getInt("age")
        val email = binding.getString("email")
        val company = binding.getString("company")
        
        println("👤 $name (age $age): $email")
        println("   🏢 Works for: $company")
    }
    
    // Performance monitoring
    val started = System.nanoTime()
    repo.select(SparqlSelectQuery("""
        SELECT ?name WHERE { ?person <http://example.org/person/name> ?name }
    """))
    val queryDurationMs = (System.nanoTime() - started) / 1_000_000
    println("\n📊 Query executed in: ${queryDurationMs}ms")
    
    // Clean up
    repo.close()
    
    println("\n🎉 === Quick Start Complete ===")
    println("🚀 You're ready to build amazing RDF applications!")
}
```

## 🤝 Need Help?

- **Documentation**: [docs/](docs/)
- **Examples**: [examples/](examples/)
- **Issues**: [GitHub Issues](https://github.com/geoknoesis/kastor-rdf/issues)
- **Discussions**: [GitHub Discussions](https://github.com/geoknoesis/kastor-rdf/discussions)

---

**🚀 Congratulations! You've successfully completed the Kastor RDF Quick Start Guide. You're now ready to build elegant and powerful RDF applications!**



