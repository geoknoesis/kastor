# 🚀 Quick Start Guide

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
    implementation("com.geoknoesis:kastor-rdf-core:0.1.0")
    
    // Choose your backend (or both)
    implementation("com.geoknoesis:kastor-rdf-jena:0.1.0")    // Apache Jena backend
    implementation("com.geoknoesis:kastor-rdf-rdf4j:0.1.0")   // Eclipse RDF4J backend
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
        val name = "http://example.org/person/name".toIri()
        val age = "http://example.org/person/age".toIri()
        val email = "http://example.org/person/email".toIri()
        val worksFor = "http://example.org/person/worksFor".toIri()
    }
    
    object CompanyVocab {
        val name = "http://example.org/company/name".toIri()
        val industry = "http://example.org/company/industry".toIri()
    }
    
    // Add data using the elegant DSL
    repo.add {
        val alice = "http://example.org/person/alice".toResource()
        val company = "http://example.org/company/tech".toResource()
        
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
    val results = repo.query("""
        SELECT ?name ?age ?email ?company
        WHERE {
            ?person <http://example.org/person/name> ?name ;
                    <http://example.org/person/age> ?age ;
                    <http://example.org/person/email> ?email ;
                    <http://example.org/person/worksFor> ?company .
        }
    """)
    
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
    val person = "http://example.org/person/john".toResource()
    
    person["http://example.org/person/name"] = "John Doe"
    person["http://example.org/person/age"] = 25
    person["http://example.org/person/email"] = "john@example.com"
}
```

### 2. Natural Language Syntax (Most Explicit)

```kotlin
repo.add {
    val person = "http://example.org/person/jane".toResource()
    
    person has "http://example.org/person/name" with "Jane Smith"
    person has "http://example.org/person/age" with 28
    person has "http://example.org/person/email" with "jane@example.com"
}
```

### 3. Generic Infix Operator (Natural Flow)

```kotlin
repo.add {
    val person = "http://example.org/person/bob".toResource()
    
    person has "http://example.org/person/name" with "Bob Wilson"
    person has "http://example.org/person/age" with 32
    person has "http://example.org/person/email" with "bob@example.com"
}
```

### 4. Minus Operator (New values() & list() Functions)

```kotlin
repo.add {
    val person = "http://example.org/person/bob".toResource()
    
    person has "http://example.org/person/name" with "Bob Wilson"
    person has "http://example.org/person/age" with 32
    person has "http://example.org/person/email" with "bob@example.com"
}

// 3. Minus operator syntax (new!)
repo.add {
    val person = "http://example.org/person/charlie".toResource()
    
    person - "http://example.org/person/name" - "Charlie Brown"
    person - "http://example.org/person/age" - 25
    person - "http://example.org/person/email" - "charlie@example.com"
    
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
val person = "http://example.org/person/sarah".toResource()
val name = "http://example.org/person/name".toIri()

val triple = person -> (name to "Sarah Johnson")
repo.addTriple(triple)
```

### 5. Convenience Functions (Explicit)

```kotlin
val person = "http://example.org/person/mike".toResource()
val name = "http://example.org/person/name".toIri()

val triple = triple(person, name, "Mike Brown")
repo.addTriple(triple)
```

## 🔄 Fluent Interface Operations

Chain operations together for elegant, readable code:

```kotlin
repo.fluent()
    .add {
        // Add data
        val person = "http://example.org/person/fluent".toResource()
        person["http://example.org/person/name"] = "Fluent User"
        person["http://example.org/person/age"] = 35
    }
    .query("SELECT ?name ?age WHERE { ?person <http://example.org/person/name> ?name ; <http://example.org/person/age> ?age }")
    .forEach { binding ->
        println("${binding.getString("name")} is ${binding.getInt("age")} years old")
    }
    .clear()
    .statistics()
```

## ⚡ Performance Monitoring

Monitor your application's performance:

```kotlin
// Time query execution
val (results, queryDuration) = repo.queryTimed("""
    SELECT ?name WHERE { ?person <http://example.org/person/name> ?name }
""")
println("Query executed in: $queryDuration")

// Time operation execution
val (_, operationDuration) = repo.operationTimed {
    repo.add {
        val person = "http://example.org/person/timed".toResource()
        person["http://example.org/person/name"] = "Timed User"
    }
}
println("Operation completed in: $operationDuration")

// Get comprehensive statistics
println(repo.statisticsFormatted())
```

## 📦 Batch Operations

Process large datasets efficiently:

```kotlin
// Create many people efficiently
val people = (1..1000).map { i ->
    "http://example.org/person/person$i".toResource()
}

// Add in batches for better performance
repo.addBatch(batchSize = 100) {
    people.forEachIndexed { index, person ->
        person["http://example.org/person/name"] = "Person ${index + 1}"
        person["http://example.org/person/age"] = 20 + (index % 50)
    }
}
```

## 🔍 Advanced Query Features

Use convenient query methods:

```kotlin
// Get first result directly
val firstPerson = repo.queryFirst("""
    SELECT ?name WHERE { ?person <http://example.org/person/name> ?name } LIMIT 1
""")
println("First person: ${firstPerson?.getString("name")}")

// Get results as a map
val nameAgeMap = repo.queryMap(
    sparql = "SELECT ?name ?age WHERE { ?person <http://example.org/person/name> ?name ; <http://example.org/person/age> ?age }",
    keySelector = { it.getString("name") ?: "Unknown" },
    valueSelector = { it.getInt("age").toString() }
)
println("Name-Age Map: $nameAgeMap")

// Get results as specific types
val names: List<String> = repo.query("""
    SELECT ?name WHERE { ?person <http://example.org/person/name> ?name }
""").mapAs()
println("Names: $names")
```

## 💼 Transaction Operations

Ensure data consistency with transactions:

```kotlin
repo.transaction {
    // All operations in this block are atomic
    add {
        val person = "http://example.org/person/atomic".toResource()
        person["http://example.org/person/name"] = "Atomic User"
        person["http://example.org/person/age"] = 40
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
val repo = Rdf.factory {
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
- **[Super Sleek API Guide](super-sleek-api-guide.md)** - Comprehensive feature showcase
- **[Compact DSL Guide](compact-dsl-guide.md)** - Multiple syntax styles
- **[RDF Fundamentals](rdf-fundamentals.md)** - Understanding RDF basics
- **[SPARQL Fundamentals](sparql-fundamentals.md)** - Query language introduction

### 🎯 Examples
- **[Super Sleek Example](examples/src/main/kotlin/com/geoknoesis/kastor/rdf/examples/SuperSleekExample.kt)** - Complete feature showcase
- **[Ultra-Compact with Variables](examples/src/main/kotlin/com/geoknoesis/kastor/rdf/examples/UltraCompactWithVariablesExample.kt)** - Variable usage patterns

### 🛠️ Tutorials
- **[Hello World Tutorial](tutorials/hello-world.md)** - Your first RDF application
- **[Load and Query Tutorial](tutorials/load-and-query.md)** - Working with data
- **[Remote Endpoint Tutorial](tutorials/remote-endpoint.md)** - Connecting to remote repositories

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
        val name = "http://example.org/person/name".toIri()
        val age = "http://example.org/person/age".toIri()
        val email = "http://example.org/person/email".toIri()
        val worksFor = "http://example.org/person/worksFor".toIri()
    }
    
    object CompanyVocab {
        val name = "http://example.org/company/name".toIri()
        val industry = "http://example.org/company/industry".toIri()
    }
    
    // Add data using fluent interface
    repo.fluent()
        .add {
            val alice = "http://example.org/person/alice".toResource()
            val company = "http://example.org/company/tech".toResource()
            
            // Ultra-compact syntax
            alice[PersonVocab.name] = "Alice Johnson"
            alice[PersonVocab.age] = 30
            alice[PersonVocab.email] = "alice@example.com"
            alice[PersonVocab.worksFor] = company
            
            // Company information
            company[CompanyVocab.name] = "Tech Innovations Inc."
            company[CompanyVocab.industry] = "Software Development"
        }
        .query("""
            SELECT ?name ?age ?email ?company
            WHERE {
                ?person <http://example.org/person/name> ?name ;
                        <http://example.org/person/age> ?age ;
                        <http://example.org/person/email> ?email ;
                        <http://example.org/person/worksFor> ?company .
            }
        """)
        .forEach { binding ->
            val name = binding.getString("name")
            val age = binding.getInt("age")
            val email = binding.getString("email")
            val company = binding.getString("company")
            
            println("👤 $name (age $age): $email")
            println("   🏢 Works for: $company")
        }
    
    // Performance monitoring
    val (_, queryDuration) = repo.queryTimed("""
        SELECT ?name WHERE { ?person <http://example.org/person/name> ?name }
    """)
    println("\n📊 Query executed in: $queryDuration")
    
    // Statistics
    println("📈 Repository statistics:")
    println(repo.statisticsFormatted())
    
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



