# Compact DSL Guide - Vocabulary Agnostic

## Overview

The Kastor RDF API provides a **vocabulary-agnostic** core API with multiple syntax options for creating RDF triples. The core API makes **no assumptions** about specific vocabularies, allowing you to work with any RDF vocabulary. Optional vocabulary extensions provide domain-specific compact syntax when needed.

## 🎯 Core Design Principle: Vocabulary Agnostic

The core API is designed to be **vocabulary-agnostic**, meaning:

- ✅ **No hardcoded vocabulary assumptions** in the core API
- ✅ **Works with any RDF vocabulary** (FOAF, Dublin Core, custom vocabularies, etc.)
- ✅ **Flexible predicate specification** using full IRIs or string literals
- ✅ **Type-safe** for all literal types (String, Int, Double, Boolean)
- ✅ **Multiple syntax options** available for different preferences

## 🚀 Core Syntax Options (All Vocabulary Agnostic)

### 1. **Ultra-Compact Syntax** (Most Concise)

```kotlin
// Bracket notation with full IRIs
person["http://example.org/name"] = "Alice"
person["http://example.org/age"] = 30
person["http://example.org/email"] = "alice@example.com"
person["http://example.org/friend"] = bob

// With IRI predicates
val name = iri("http://example.org/name")
val age = iri("http://example.org/age")
person[name] = "Alice"
person[age] = 30
```

**Benefits:**
- ✅ Most concise syntax
- ✅ Familiar array/map assignment pattern
- ✅ Works with any predicate IRI
- ✅ Type-safe for common literal types

### 2. **Natural Language Syntax** (Most Explicit)

```kotlin
// Natural language syntax with any predicate IRI
person has name with "Alice"
person has age with 30
person has email with "alice@example.com"
person has friend with bob

// With IRI predicates
val name = iri("http://example.org/name")
person has name with "Alice"
```

**Benefits:**
- ✅ Most explicit and readable
- ✅ Self-documenting code
- ✅ Works with any predicate IRI
- ✅ Clear intent

### 3. **Generic Infix Operator** (Natural Flow)

```kotlin
// Generic infix operator with any predicate IRI
person has name with "Alice"
person has age with 30
person has email with "alice@example.com"
person has friend with bob

// With IRI predicates
val name = iri("http://example.org/name")
person has name with "Alice"
```

**Benefits:**
- ✅ Natural reading flow
- ✅ Works with any predicate IRI
- ✅ Type-safe for different value types

### 4. **Minus Operator Syntax** (Multiple Values)

The minus operator (`-`) provides intuitive syntax for creating multiple triples with the same subject-predicate pair:

```kotlin
// Single values
person - name - "Alice"
person - age - 30
person - email - "alice@example.com"

// Multiple individual triples using values() function
person - FOAF.knows - values(friend1, friend2, friend3)

// RDF Lists using list() function
person - FOAF.mbox - list("alice@example.com", "alice@work.com")

// RDF Containers using bag(), seq(), alt() functions
person - DCTERMS.subject - bag("Technology", "AI", "RDF", "Kotlin")  // rdf:Bag
person - FOAF.knows - seq(friend1, friend2, friend3)                // rdf:Seq
person - FOAF.mbox - alt("alice@example.com", "alice@work.com")     // rdf:Alt

// Mixed types work with all functions
person - DCTERMS.subject - values("Technology", "Programming", "RDF", 42, true)
```

**Benefits:**
- ✅ Intuitive `values()` function for individual triples
- ✅ Intuitive `list()` function for RDF lists
- ✅ Intuitive `bag()`, `seq()`, `alt()` functions for RDF containers
- ✅ Follows common programming conventions
- ✅ Type-safe for all RDF term types
- ✅ Supports multiple values efficiently

## 🔧 Implementation Details

### Type Safety

All syntax options provide compile-time type safety:

```kotlin
// String literals
person["http://example.org/name"] = "Alice"           // ✅ Compiles
person has name with "Alice"                            // ✅ Compiles
person - name - "Alice"                                // ✅ Compiles

// Integer literals
person["http://example.org/age"] = 30                  // ✅ Compiles
person has age with 30                                 // ✅ Compiles
person - age - 30                                      // ✅ Compiles

// Double literals
person["http://example.org/salary"] = 75000.0          // ✅ Compiles
person has salary with 75000.0                         // ✅ Compiles
person - salary - 75000.0                              // ✅ Compiles

// Boolean literals
person["http://example.org/active"] = true             // ✅ Compiles
person has active with true                            // ✅ Compiles
person - active - true                                 // ✅ Compiles

// Resource references
person["http://example.org/friend"] = bob              // ✅ Compiles
person has friend with bob                             // ✅ Compiles
person - friend - bob                                  // ✅ Compiles

// Multiple values with minus operator
person - FOAF.knows - values(friend1, friend2, friend3) // ✅ Compiles
person - FOAF.mbox - list("email1", "email2")           // ✅ Compiles
```

### Predicate Flexibility

```kotlin
// String predicates (auto-converted to IRIs)
person["http://example.org/name"] = "Alice"
person["http://xmlns.com/foaf/0.1/name"] = "Alice"
person["http://purl.org/dc/terms/title"] = "My Document"

// IRI predicates
val name = iri("http://example.org/name")
val foafName = iri("http://xmlns.com/foaf/0.1/name")
val dcTitle = iri("http://purl.org/dc/terms/title")

person[name] = "Alice"
person has foafName with "Alice"
person has dcTitle with "My Document"
```

### Variable Usage Patterns

The ultra-compact syntax supports multiple ways to organize IRI variables:

```kotlin
// 1. Simple variables (most common)
val name = iri("http://example.org/name")
val age = iri("http://example.org/age")
val email = iri("http://example.org/email")

person[name] = "Alice"
person[age] = 30
person[email] = "alice@example.com"

// 2. Vocabulary objects (best for organization)
object PersonVocab {
    val name = iri("http://example.org/person/name")
    val age = iri("http://example.org/person/age")
    val email = iri("http://example.org/person/email")
    val worksFor = iri("http://example.org/person/worksFor")
}

object CompanyVocab {
    val name = iri("http://example.org/company/name")
    val industry = iri("http://example.org/company/industry")
    val location = iri("http://example.org/company/location")
}

person[PersonVocab.name] = "Alice"
person[PersonVocab.worksFor] = company
company[CompanyVocab.name] = "Tech Corp"

// 3. Local variables (within blocks)
repo.add {
    val personName = iri("http://example.org/person/name")
    val personAge = iri("http://example.org/person/age")
    
    person[personName] = "Alice"
    person[personAge] = 30
}

// 4. Mixed approach (flexible)
val commonName = iri("http://example.org/name")  // Used everywhere
val commonAge = iri("http://example.org/age")    // Used everywhere

object ProjectVocab {
    val name = iri("http://example.org/project/name")
    val startDate = iri("http://example.org/project/startDate")
}

person[commonName] = "Alice"
person[commonAge] = 30
project[ProjectVocab.name] = "AI Platform"
```

### Batch Operations

```kotlin
// Add multiple triples efficiently
repo.add {
    val people = listOf(person1, person2, person3)
    
    people.forEachIndexed { index, person ->
        person["http://example.org/name"] = "Person ${index + 1}"
        person["http://example.org/age"] = 20 + index * 5
        person["http://example.org/email"] = "person${index + 1}@example.com"
    }
}
```

## 🎨 Style Comparison

| Style | Example | Pros | Cons |
|-------|---------|------|------|
| **Ultra-Compact (String)** | `person["http://example.org/name"] = "Alice"` | Most concise, familiar pattern | Requires full IRIs, less readable |
| **Ultra-Compact (Variable)** | `person[name] = "Alice"` | Concise, readable, IDE support | Requires variable definition |
| **Ultra-Compact (Vocab)** | `person[PersonVocab.name] = "Alice"` | Concise, organized, type-safe | Requires vocabulary object |
| **Natural Language** | `person has name with "Alice"` | Most explicit, self-documenting | Most verbose |
| **Generic Infix** | `person has name with "Alice"` | Natural flow, concise | Most verbose |
| **Minus Operator (Single)** | `person - name - "Alice"` | Clean, readable, familiar | Single values only |
| **Minus Operator (Multiple)** | `person - FOAF.knows - values(f1, f2, f3)` | Intuitive multiple values | Requires values() function |
| **Minus Operator (RDF List)** | `person - FOAF.mbox - list("e1", "e2")` | Proper RDF Lists | Requires list() function |
| **Minus Operator (RDF Bag)** | `person - DCTERMS.subject - bag("t1", "t2")` | RDF Bag container | Requires bag() function |
| **Minus Operator (RDF Seq)** | `person - FOAF.knows - seq(f1, f2, f3)` | RDF Seq container | Requires seq() function |
| **Minus Operator (RDF Alt)** | `person - FOAF.mbox - alt("e1", "e2")` | RDF Alt container | Requires alt() function |

## 🎯 Optional Vocabulary Extensions

For domain-specific compact syntax, you can optionally import vocabulary extensions:

```kotlin
import com.geoknoesis.kastor.rdf.VocabularyExtensions.*

// Then use compact syntax with predefined vocabularies
repo.add {
    person name "Alice"           // Uses FOAF vocabulary
    person age 30                 // Uses FOAF vocabulary
    person email "alice@example.com" // Uses FOAF vocabulary
    person friend bob             // Uses FOAF vocabulary
}
```

### Available Vocabulary Extensions

- **FOAF** (Friend of a Friend): `person name "Alice"`
- **RDFS**: `person label "Alice"`
- **Dublin Core**: `document title "My Document"`
- **Example**: `person worksFor company`

### Creating Custom Vocabularies

```kotlin
// Define your own vocabulary
object MyVocab {
    val name = iri("http://myvocab.org/name")
    val age = iri("http://myvocab.org/age")
    val email = iri("http://myvocab.org/email")
    val worksFor = iri("http://myvocab.org/worksFor")
}

// Use with core API
repo.add {
    person[MyVocab.name] = "Alice"
    person has MyVocab.age with 30
    person has MyVocab.email with "alice@example.com"
}

// Or create custom DSL extensions
infix fun RdfResource.name(value: String): RdfTriple {
    return createTriple(this, MyVocab.name, string(value))
}

// Then use
repo.add {
    person name "Alice"  // Uses your custom vocabulary
}
```

## 🎯 Minus Operator Deep Dive

The minus operator (`-`) provides a powerful and intuitive way to create RDF triples, especially when dealing with multiple values.

### Basic Syntax

```kotlin
// Single values
person - name - "Alice"
person - age - 30
person - email - "alice@example.com"
person - friend - bob
```

### Multiple Individual Triples (Curly Braces)

Use `values()` function to create multiple individual triples:

```kotlin
// Creates 3 separate triples:
// person knows friend1
// person knows friend2  
// person knows friend3
person - FOAF.knows - values(friend1, friend2, friend3)

// Works with mixed types
person - DCTERMS.subject - values("Technology", "Programming", "RDF", 42, true)
```

### RDF Lists (Parentheses)

Use `list()` function to create proper RDF List structures:

```kotlin
// Creates RDF List with rdf:first, rdf:rest, rdf:nil
person - FOAF.mbox - list("alice@example.com", "alice@work.com")

// Creates RDF List for ordered data
person - DCTERMS.type - list("Person", "Agent", "Researcher")
```

### RDF Containers

Use `bag()`, `seq()`, and `alt()` functions to create RDF containers:

```kotlin
// rdf:Bag - unordered container with duplicates allowed
person - DCTERMS.subject - bag("Technology", "AI", "RDF", "Technology")  // Duplicates OK

// rdf:Seq - ordered container
person - FOAF.knows - seq(friend1, friend2, friend3)  // Order preserved

// rdf:Alt - alternative container (typically first item is default)
person - FOAF.mbox - alt("alice@example.com", "alice@work.com")  // Primary, secondary
```

### Syntax Comparison

| Syntax | Result | Use Case |
|--------|--------|----------|
| `person - FOAF.knows - values(f1, f2, f3)` | **3 individual triples** | Multiple relationships |
| `person - FOAF.mbox - list("e1", "e2")` | **1 triple + RDF List** | Ordered collections |
| `person - DCTERMS.subject - bag("t1", "t2", "t3")` | **1 triple + rdf:Bag** | Unordered with duplicates |
| `person - FOAF.knows - seq(f1, f2, f3)` | **1 triple + rdf:Seq** | Ordered container |
| `person - FOAF.mbox - alt("e1", "e2")` | **1 triple + rdf:Alt** | Alternative options |
| `person - FOAF.knows - arrayOf(f1, f2, f3)` | **3 individual triples** | Traditional arrays |
| `person - FOAF.mbox - listOf("e1", "e2")` | **1 triple + RDF List** | Traditional lists |

### Real-World Examples

```kotlin
repo.add {
    val person = iri("http://example.org/person/alice")
    val friend1 = iri("http://example.org/person/bob")
    val friend2 = iri("http://example.org/person/charlie")
    val friend3 = iri("http://example.org/person/diana")
    
    // Basic properties
    person - FOAF.name - "Alice"
    person - FOAF.age - 30
    
    // Multiple friends (individual triples)
    person - FOAF.knows - values(friend1, friend2, friend3)
    
    // Multiple email addresses (RDF List)
    person - FOAF.mbox - list("alice@example.com", "alice@work.com")
    
    // Multiple subjects (RDF Bag - unordered, duplicates allowed)
    person - DCTERMS.subject - bag("Technology", "Programming", "RDF", "Technology")
    
    // Ordered friends list (RDF Seq)
    person - FOAF.knows - seq(friend1, friend2, friend3)
    
    // Alternative email addresses (RDF Alt)
    person - FOAF.mbox - alt("alice@example.com", "alice@work.com")
    
    // Mixed types work with all syntaxes
    person - DCTERMS.creator - values("Alice", "Bob", 42, true)
}
```

### When to Use Each Syntax

**Use `values()` for:**
- Multiple relationships (person knows multiple friends)
- Unordered collections
- When you want individual triples for querying
- Mixed data types

**Use `list()` for:**
- Ordered collections (email addresses, phone numbers)
- When order matters
- When you want proper RDF List semantics
- SPARQL list operations

**Use `bag()` for:**
- Unordered collections with duplicates allowed
- Topic tags, categories, keywords
- When duplicates are meaningful

**Use `seq()` for:**
- Ordered containers (not RDF Lists)
- When you need rdf:_1, rdf:_2, etc. structure
- Step-by-step processes, ordered lists

**Use `alt()` for:**
- Alternative options (primary email, secondary email)
- Default values with alternatives
- When first item is typically the preferred choice

## 🚀 Best Practices

### Choose Based on Context

```kotlin
// For simple data entry - Ultra-compact
person["http://example.org/name"] = "Alice"
person["http://example.org/age"] = 30

// For multiple values - Minus operator
person - FOAF.knows - values(friend1, friend2, friend3)
person - FOAF.mbox - list("email1", "email2")

// For complex relationships - Natural language
person has worksFor with company
person has manager with bob

// For batch operations - Mix and match
people.forEach { person ->
    person["http://example.org/name"] = person.name
    person has worksFor with company
}
```

### Vocabulary Management

```kotlin
// Define vocabularies at the top of your file
object PersonVocab {
    val name = iri("http://example.org/person/name")
    val age = iri("http://example.org/person/age")
    val email = iri("http://example.org/person/email")
    val worksFor = iri("http://example.org/person/worksFor")
}

object CompanyVocab {
    val name = iri("http://example.org/company/name")
    val industry = iri("http://example.org/company/industry")
    val location = iri("http://example.org/company/location")
}

// Use throughout your code
repo.add {
    person[PersonVocab.name] = "Alice"
    person has PersonVocab.worksFor with company
    company has CompanyVocab.name with "Tech Corp"
}
```

### Performance Considerations

```kotlin
// Efficient batch operations
repo.add {
    val triples = mutableListOf<RdfTriple>()
    
    people.forEach { person ->
        triples.add(RdfTriple(person, PersonVocab.name, string("Alice")))
        triples.add(RdfTriple(person, PersonVocab.age, integer(30)))
    }
    
    // All triples added in one batch
}
```

## 📚 Real-World Examples

### User Profile Creation (Vocabulary Agnostic)

```kotlin
// Ultra-compact style with full IRIs
val user = iri("http://example.org/user/1")
repo.add {
    user["http://example.org/user/name"] = "Alice Johnson"
    user["http://example.org/user/email"] = "alice@example.com"
    user["http://example.org/user/age"] = 30
    user["http://example.org/user/active"] = true
    user["http://example.org/user/created"] = "2024-01-01"
}
```

### Social Network (Vocabulary Agnostic)

```kotlin
// Natural language style with any vocabulary
val alice = iri("http://example.org/person/alice")
val bob = iri("http://example.org/person/bob")
val company = iri("http://example.org/company/tech")

repo.add {
    alice has name with "Alice"
    alice has worksFor with company
    alice has friend with bob
    bob has name with "Bob"
    bob has worksFor with company
}
```

### Mixed Style for Complex Data

```kotlin
// Use the best syntax for each case
val person = iri("http://example.org/person/1")
val company = iri("http://example.org/company/1")

repo.add {
    // Simple properties - ultra-compact
    person["http://example.org/person/name"] = "Alice"
    person["http://example.org/person/email"] = "alice@example.com"
    
    // Complex relationships - natural language
    person has worksFor with company
    person has manager with bob
    person has department with engineering
}
```

## 🎯 Recommendations

### For Beginners
- Start with **natural language syntax** for clarity
- Use **full IRIs** to understand what you're creating
- Gradually adopt **ultra-compact syntax** for simple data

### For Experienced Developers
- Use **ultra-compact syntax** for bulk operations
- Create **custom vocabulary objects** for your domain
- Mix styles based on context and readability

### For Teams
- Establish **consistent vocabulary objects** for your domain
- Document **custom vocabulary extensions**
- Use **natural language** for complex relationships
- Use **compact syntax** for simple properties

## 🎉 Conclusion

The Kastor RDF API provides a **vocabulary-agnostic** core API that works with any RDF vocabulary. All syntax styles are equivalent and create the same RDF triples - choose what feels most natural to you!

- **Ultra-compact**: `person["http://example.org/name"] = "Alice"` (most concise)
- **Natural language**: `person has name with "Alice"` (most explicit)
- **Generic infix**: `person has name with "Alice"` (natural flow)
- **Minus operator**: `person - name - "Alice"` (clean and familiar)
- **Multiple values**: `person - FOAF.knows - values(f1, f2, f3)` (intuitive collections)
- **RDF Lists**: `person - FOAF.mbox - list("e1", "e2")` (proper RDF semantics)

The new **`values()` and `list()`** functions make it even easier to work with multiple values, following common programming conventions. Use `values()` for individual triples and `list()` for proper RDF Lists.

Optional vocabulary extensions provide domain-specific compact syntax when needed, but the core API remains flexible and vocabulary-agnostic.
