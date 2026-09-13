# Compact DSL Guide - Vocabulary Agnostic

{% include version-banner.md %}

The Kastor RDF API provides a **vocabulary-agnostic** core API with multiple syntax options for creating RDF triples. The core API makes **no assumptions** about specific vocabularies, allowing you to work with any RDF vocabulary.

> **Note**: For creating ontologies and vocabularies, see the specialized DSL guides:
> - [RDFS DSL Guide](rdfs-dsl-guide.md) - For RDF Schema vocabulary creation
> - [SKOS DSL Guide](skos-dsl-guide.md) - For SKOS concept schemes, broader–narrower, labels
> - [PROV-O DSL Guide](prov-o-dsl-guide.md) - For PROV-O provenance assertions (generation, use, attribution)
> - [Metadata & geometry DSLs](metadata-vocabulary-dsls.md) - For DCAT, DCTerms, VoID, GeoSPARQL, OWL-Time
> - [BFO DSL Guide](bfo-dsl-guide.md) - For BFO / OBO-style instance assertions (parthood, RO relations)
> - [OWL DSL Guide](owl-dsl-guide.md) - For OWL 2 ontology creation
> - [SHACL DSL Guide](shacl-dsl-guide.md) - For SHACL shapes and validation constraints

## 🎯 Core Design Principle: Vocabulary Agnostic

The core API is designed to be **vocabulary-agnostic**, meaning:

- ✅ **No hardcoded vocabulary assumptions** in the core API
- ✅ **Works with any RDF vocabulary** (FOAF, Dublin Core, custom vocabularies, etc.)
- ✅ **Explicit predicates** as `Iri` values (vocabulary constants, `iri(...)` or `qname(...)`)
- ✅ **Type-safe** for all literal types (String, Int, Double, Boolean)
- ✅ **Multiple syntax options** available for different preferences

## 🚀 Core Syntax Options (All Vocabulary Agnostic)

### 1. **Ultra-Compact Syntax** (Most Concise)

Bracket assignment takes an `Iri` predicate; strings are not accepted as predicates.

```kotlin
val namePred = iri("http://example.org/name")
val agePred = iri("http://example.org/age")
val emailPred = iri("http://example.org/email")
val friendPred = iri("http://example.org/friend")

repo.add {
    person[namePred] = "Alice"
    person[agePred] = 30
    person[emailPred] = "alice@example.com"
    person[friendPred] = bob
}
```

With prefix mappings, build predicates from QNames with `qname(...)`:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF

repo.add {
    // Built-in prefixes need no declaration: rdf, rdfs, owl, sh, xsd, obo, skos, prov, dcat, dcterms, void, geo, time
    prefixes {
        put("foaf", FOAF.namespace)
    }

    person[qname("foaf:name")] = "Alice"
    person[qname("foaf:age")] = 30
    person[DCTERMS.description] = "A person"

    // rdf:type
    person[RDF.type] = qname("foaf:Person")
}
```

**Benefits:**
- ✅ Most concise syntax
- ✅ Familiar array/map assignment pattern
- ✅ Works with any predicate IRI
- ✅ Type-safe for common literal types

### 2. **Natural Language Syntax** (Most Explicit)

```kotlin
repo.add {
    person has namePred with "Alice"
    person has agePred with 30
    person has emailPred with "alice@example.com"
    person has friendPred with bob

    // QName predicates via qname(...)
    person has qname("foaf:name") with "Alice"

    // rdf:type with `is`
    person `is` FOAF.Person
}
```

**Benefits:**
- ✅ Most explicit and readable
- ✅ Self-documenting code
- ✅ Works with any predicate IRI
- ✅ Clear intent

### 3. **Generic Infix Operator** (Natural Flow)

`has` and `with` are ordinary infix functions, so the statement reads left to right. There is no separate string-predicate form:

```kotlin
repo.add {
    person has namePred with "Alice"
    person has friendPred with bob
}
```

**Benefits:**
- ✅ Natural reading flow
- ✅ Works with any predicate IRI
- ✅ Type-safe for different value types

### 4. **Minus Operator Syntax** (Multiple Values)

The minus operator (`-`) provides intuitive syntax for creating multiple triples with the same subject-predicate pair:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF

repo.add {
    // Single values
    person - namePred - "Alice"
    person - agePred - 30
    person - qname("dcterms:description") - "A person"

    // Multiple individual triples using values() function
    person - FOAF.knows - values(friend1, friend2, friend3)

    // RDF Lists using list() function
    person - FOAF.mbox - list("alice@example.com", "alice@work.com")

    // RDF Containers using bag(), seq(), alt() functions
    person - DCTERMS.subject - bag("Technology", "AI", "RDF", "Kotlin")  // rdf:Bag
    person - FOAF.knows - seq(friend1, friend2, friend3)                // rdf:Seq
    person - FOAF.mbox - alt("alice@example.com", "alice@work.com")     // rdf:Alt

    // Mixed types: pass RdfTerm values
    person - DCTERMS.subject - values(string("Technology"), string("RDF"), int(42), boolean(true))
}
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
// Use typed predicates to avoid stringly-typed IRIs
// String literals
val namePred = iri("http://example.org/name")
person[namePred] = "Alice"                           // ✅ Compiles
person has namePred with "Alice"                     // ✅ Compiles
person - namePred - "Alice"                          // ✅ Compiles

// Integer literals
val agePred = iri("http://example.org/age")
person[agePred] = 30                                 // ✅ Compiles
person has agePred with 30                            // ✅ Compiles
person - agePred - 30                                 // ✅ Compiles

// Double literals
val salaryPred = iri("http://example.org/salary")
person[salaryPred] = 75000.0                          // ✅ Compiles
person has salaryPred with 75000.0                    // ✅ Compiles
person - salaryPred - 75000.0                         // ✅ Compiles

// Boolean literals
val activePred = iri("http://example.org/active")
person[activePred] = true                             // ✅ Compiles
person has activePred with true                       // ✅ Compiles
person - activePred - true                            // ✅ Compiles

// Resource references
val friendPred = iri("http://example.org/friend")
person[friendPred] = bob                              // ✅ Compiles
person has friendPred with bob                        // ✅ Compiles
person - friendPred - bob                             // ✅ Compiles

// Multiple values with minus operator
person - FOAF.knows - values(friend1, friend2, friend3) // ✅ Compiles
person - FOAF.mbox - list("email1", "email2")           // ✅ Compiles
```

### Predicate Flexibility

Predicates are always `Iri` values:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF

val name = iri("http://example.org/name")      // full IRI
val foafName = FOAF.name                        // vocabulary constant

repo.add {
    val dcTitle = qname("dcterms:title")        // QName via built-in or declared prefixes
    person[name] = "Alice"
    person has foafName with "Alice"
    person has dcTitle with "My Document"
}
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
        person[iri("http://example.org/name")] = "Person ${index + 1}"
        person[iri("http://example.org/age")] = 20 + index * 5
        person[iri("http://example.org/email")] = "person${index + 1}@example.com"
    }
}
```

## 🎨 Style Comparison

| Style | Example | Pros | Cons |
|-------|---------|------|------|
| **Ultra-Compact (String)** | `person[iri("http://example.org/name")] = "Alice"` | Most concise, familiar pattern | Requires full IRIs, less readable |
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

## 🧠 Objects: Literals, IRIs and QNames

A string object is always a string literal: the DSL does not guess whether a string is an IRI or a QName. Say what you mean:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.XSD

repo.add {
    prefixes {
        put("foaf", FOAF.namespace)
    }

    val person = iri("http://example.org/person")

    person - FOAF.knows - qname("foaf:Person")                  // IRI from a QName
    person - FOAF.homepage - iri("http://example.org/profile")  // IRI
    person - FOAF.name - "Alice"                                // "Alice"^^xsd:string
    person - FOAF.name - "foaf:Person"                          // "foaf:Person"^^xsd:string (not resolved)
    person - FOAF.name - lang("Alice", "en")                    // "Alice"@en (tags are validated and lower-cased)
    person - FOAF.age - Literal("30", XSD.integer)              // "30"^^xsd:integer, lexical form preserved
}
```

## 🏗️ Built-in Prefixes

Kastor comes with built-in prefixes for the most common vocabularies, so you don't need to declare them:

### Available Built-in Prefixes

| Prefix | Namespace | Description |
|--------|-----------|-------------|
| `rdf` | `http://www.w3.org/1999/02/22-rdf-syntax-ns#` | RDF Core vocabulary |
| `rdfs` | `http://www.w3.org/2000/01/rdf-schema#` | RDF Schema vocabulary |
| `owl` | `http://www.w3.org/2002/07/owl#` | Web Ontology Language |
| `sh` | `http://www.w3.org/ns/shacl#` | Shapes Constraint Language |
| `xsd` | `http://www.w3.org/2001/XMLSchema#` | XML Schema datatypes |
| `obo` | `http://purl.obolibrary.org/obo/` | OBO Foundry ontologies |
| `skos` | `http://www.w3.org/2004/02/skos/core#` | SKOS |
| `prov` | `http://www.w3.org/ns/prov#` | PROV-O |
| `dcat` | `http://www.w3.org/ns/dcat#` | DCAT |
| `dcterms` | `http://purl.org/dc/terms/` | Dublin Core terms |
| `void` | `http://rdfs.org/ns/void#` | VoID |
| `geo` | `http://www.opengis.net/ont/geosparql#` | GeoSPARQL |
| `time` | `http://www.w3.org/2006/time#` | OWL-Time |

### Usage Examples

```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF

repo.add {
    // No need to declare built-in prefixes
    val personClass = iri("http://example.org/Person")

    personClass[qname("rdf:type")] = qname("rdfs:Class")
    personClass - qname("rdfs:label") - "Person Class"
    personClass - qname("owl:sameAs") - iri("http://example.org/Person2")

    // Mix with custom prefixes
    prefixes {
        put("foaf", FOAF.namespace)
    }
    personClass - qname("rdfs:subClassOf") - qname("foaf:Agent")
}
```

### Override Built-in Prefixes

You can override built-in prefixes if needed:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF

repo.add {
    prefixes {
        put("rdf", "http://example.org/custom-rdf#")  // Override built-in rdf prefix
        put("foaf", FOAF.namespace)                   // Custom prefix
    }

    val resource = iri("http://example.org/resource")
    resource[qname("rdf:type")] = qname("rdf:CustomType")  // Uses the custom namespace
}
```

## 🎯 Custom Vocabularies

Kastor ships no vocabulary-specific infix functions (such as `person name "Alice"`). Instead, group your predicates in an object and use them with any syntax:

```kotlin
object MyVocab {
    val name = iri("http://myvocab.org/name")
    val age = iri("http://myvocab.org/age")
    val email = iri("http://myvocab.org/email")
    val worksFor = iri("http://myvocab.org/worksFor")
}

repo.add {
    person[MyVocab.name] = "Alice"
    person has MyVocab.age with 30
    person - MyVocab.email - "alice@example.com"
}
```

## 🎯 Type Declarations

There are no string aliases such as `"a"`. Declare `rdf:type` with `is`, with `RDF.type` or with a QName:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF

repo.add {
    val person = iri("http://example.org/person")
    val organization = iri("http://example.org/org")

    person `is` FOAF.Person                               // natural language
    person[RDF.type] = FOAF.Agent                         // bracket syntax
    organization - RDF.type - FOAF.Organization           // minus operator
    organization has RDF.type with qname("foaf:Agent")    // has/with

    person[FOAF.name] = "Alice"
    organization[FOAF.name] = "ACME Corp"
}
```

| Style | Example |
|-------|---------|
| **Natural "is"** | ``person `is` FOAF.Person`` |
| **Bracket** | `person[RDF.type] = FOAF.Agent` |
| **Minus operator** | `person - RDF.type - FOAF.Person` |
| **has/with** | `person has RDF.type with FOAF.Person` |

## 🏷️ QName Support with Prefix Mappings

Use QNames for cleaner, more readable code with prefix mappings. `qname(...)` turns a QName (or a full IRI) into an `Iri` using the built-in and declared prefixes:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF

repo.add {
    // Configure prefix mappings
    prefixes {
        put("foaf", FOAF.namespace)
    }

    val person = iri("http://example.org/person")

    // Use QNames with all syntax styles
    person - qname("foaf:name") - "Alice"             // Minus operator
    person[qname("foaf:age")] = 30                    // Bracket syntax
    person has qname("dcat:keyword") with "example"   // Natural language (dcat is built in)

    // Mix QNames and full IRIs
    person - qname("foaf:knows") - iri("http://example.org/bob")
    val customProp = iri("http://example.org/customProp")
    person - customProp - "value"

    // Add a single prefix mapping
    prefix("schema", "https://schema.org/")
    person - qname("schema:name") - "Alice"
}
```

**Benefits of QNames:**
- **Readability**: Shorter, more readable predicates and types
- **Maintainability**: Change namespace in one place
- **Consistency**: Standard RDF prefix notation
- **Flexibility**: Mix with full IRIs when needed

## 🎯 Minus Operator Deep Dive

The minus operator (`-`) provides a powerful and intuitive way to create RDF triples, especially when dealing with multiple values.

### Basic Syntax

```kotlin
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF

// Single values
person - name - "Alice"
person - age - 30
person - email - "alice@example.com"
person - friend - bob

// With QNames
repo.add {
    prefixes {
        put("foaf", FOAF.namespace)
        put("dcterms", DCTERMS.namespace)
    }
    
    person - qname("foaf:name") - "Alice"
    person - qname("foaf:age") - 30
    person - qname("dcterms:email") - "alice@example.com"
}
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
person - DCTERMS.subject - values(string("Technology"), string("Programming"), string("RDF"), int(42), boolean(true))
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
    person - DCTERMS.creator - values(string("Alice"), string("Bob"), int(42), boolean(true))
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
val namePred = iri("http://example.org/name")
val agePred = iri("http://example.org/age")
person[namePred] = "Alice"
person[agePred] = 30

// For multiple values - Minus operator
person - FOAF.knows - values(friend1, friend2, friend3)
person - FOAF.mbox - list("email1", "email2")

// For complex relationships - Natural language
person has worksFor with company
person has manager with bob

// For batch operations - Mix and match
people.forEach { person ->
    person[namePred] = person.name
    person has worksFor with company
}
```

```kotlin
// String IRI alternative (supported, less explicit)
person[iri("http://example.org/name")] = "Alice"
person[iri("http://example.org/age")] = 30
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
// One add { } call is written as one batch
repo.add {
    people.forEach { person ->
        triple(person, PersonVocab.name, string("Alice"))
        triple(person, PersonVocab.age, int(30))
    }
}

// Or build RdfTriple values yourself and add them in one call
val triples = people.flatMap { person ->
    listOf(RdfTriple(person, PersonVocab.name, string("Alice")), RdfTriple(person, PersonVocab.age, int(30)))
}
repo.addTriples(triples)
```

The DSL's `triples` property is a read-only view; blank nodes created inside the DSL get opaque, run-unique labels.

## 📚 Real-World Examples

### User Profile Creation (Vocabulary Agnostic)

```kotlin
// Ultra-compact style with full IRIs
val user = iri("http://example.org/user/1")
repo.add {
    user[iri("http://example.org/user/name")] = "Alice Johnson"
    user[iri("http://example.org/user/email")] = "alice@example.com"
    user[iri("http://example.org/user/age")] = 30
    user[iri("http://example.org/user/active")] = true
    user[iri("http://example.org/user/created")] = "2024-01-01"
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
    person[iri("http://example.org/person/name")] = "Alice"
    person[iri("http://example.org/person/email")] = "alice@example.com"
    
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
- Use **QNames** once you understand the basics

### For Experienced Developers
- Use **ultra-compact syntax** for bulk operations
- Create **custom vocabulary objects** for your domain
- Use **QNames with prefix mappings** for cleaner code
- Mix styles based on context and readability

### For Teams
- Establish **consistent vocabulary objects** for your domain
- Use **consistent prefix mappings** across the codebase
- Document **custom vocabulary extensions**
- Use **natural language** for complex relationships
- Use **compact syntax** for simple properties
- Use **QNames** for standard vocabularies (FOAF, DC, etc.)

## 🎉 Conclusion

The Kastor RDF API provides a **vocabulary-agnostic** core API that works with any RDF vocabulary. All syntax styles are equivalent and create the same RDF triples; choose what feels most natural to you:

- **Ultra-compact**: `person[namePred] = "Alice"` (most concise)
- **Natural language**: `person has namePred with "Alice"` (most explicit)
- **Minus operator**: `person - namePred - "Alice"` (clean and familiar)
- **QNames**: `person - qname("foaf:name") - "Alice"` (with prefix mappings)
- **Type declarations**: ``person `is` FOAF.Person`` or `person[RDF.type] = FOAF.Person`
- **Multiple values**: `person - FOAF.knows - values(f1, f2, f3)` (individual triples)
- **RDF Lists**: `person - FOAF.mbox - list("e1", "e2")` (proper RDF semantics)

Predicates are always `Iri` values and string objects are always string literals: use `iri(...)`, `qname(...)` or vocabulary constants when you mean an IRI. Configure mappings with `prefixes { put(prefix, namespace) }` or `prefix(prefix, namespace)`.

## 🌟 RDF-star Support (RDF 1.2 reifiers)

Kastor follows the RDF 1.2 model for statements about statements. A triple term `<<( s p o )>>` may appear only as an **object**, and metadata is attached to a **reifier**: `_:r rdf:reifies <<( s p o )>>`. The compact DSL creates reifiers with `reifies(...)`, which is available in both `Rdf.graph { }` and `repo.add { }`. This is useful for provenance, confidence scores, temporal information and other metadata about statements.

### **Reifier Syntax**

```kotlin
val graph = Rdf.graph {
    val alice = iri("http://example.org/alice")
    val bob = iri("http://example.org/bob")

    // Basic facts
    alice - FOAF.knows - bob
    alice - FOAF.age - 30

    // Metadata about a statement: _:r rdf:reifies <<( alice foaf:knows bob )>>
    reifies(alice, FOAF.knows, bob) { r ->
        r - DCTERMS.source - "LinkedIn"
        r - iri("http://example.org/confidence") - 0.95
        r - DCTERMS.date - "2025-01-15"
    }

    // Temporal metadata about the age statement
    reifies(alice, FOAF.age, 30.toLiteral()) { r ->
        r - DCTERMS.date - "2025-01-15"
        r - iri("http://example.org/validUntil") - "2025-12-31"
    }
}
```

The reifier does not assert the triple; add it separately (as above) when it belongs to the data.

### **Creating Reifiers**

```kotlin
Rdf.graph {
    val alice = iri("http://example.org/alice")
    val bob = iri("http://example.org/bob")

    // Method 1: subject, predicate, object (fresh blank-node reifier)
    val r1 = reifies(alice, FOAF.knows, bob)

    // Method 2: an existing RdfTriple
    val claim = RdfTriple(alice, FOAF.knows, bob)
    val r2 = reifies(claim)

    // Method 3: a named reifier IRI
    val r3 = reifies(alice, FOAF.knows, bob, reifier = iri("http://example.org/claim1"))

    // reifies returns the reifier, so you can add triples to it later
    r3 - DCTERMS.source - "LinkedIn"
}
```

A triple term on its own is created with `quoted(RdfTriple(...))` (a `TripleTerm`), and can only be used in object position. The older `embedded(s, p, o)` helper just builds an `RdfStarTriple` holder; attach metadata with `reifies` instead.

### **Nested Metadata**

A reifier is an ordinary resource, so metadata about metadata is either more triples on the same reifier or a reifier for a triple whose subject is that reifier:

```kotlin
val graph = Rdf.graph {
    val alice = iri("http://example.org/alice")

    val nameClaim = reifies(alice, FOAF.name, string("Alice Johnson"), reifier = iri("http://example.org/nameClaim"))
    nameClaim - DCTERMS.source - "Profile"

    // Metadata about the "source" annotation
    reifies(nameClaim, DCTERMS.source, string("Profile")) { r ->
        r - iri("http://example.org/verified") - true
        r - DCTERMS.date - "2025-01-15"
    }
}
```

### **Provider Support**

Triple terms need an RDF 1.2 store:

```kotlin
val repo = Rdf.repository {
    providerId = "jena"
    variantId = "memory"
}

if (repo.getCapabilities().supportsTripleTerms) {
    repo.add {
        reifies(alice, FOAF.knows, bob) { r ->
            r - DCTERMS.source - "LinkedIn"
        }
    }
} else {
    println("Triple terms are not supported by this provider")
}
```

**Supported Providers:**
- ✅ **Memory Provider**: RDF 1.2 triple terms (graph-only, no SPARQL)
- ✅ **Jena Provider**: RDF 1.2 triple terms
- ✅ **RDF4J Provider**: RDF 1.2 triple terms
- ❌ **SPARQL Provider**: the HTTP adapter does not decode triple terms

### **Use Cases**

Reifiers are particularly useful for:

- **Provenance**: Tracking the source of statements
- **Confidence**: Adding confidence scores to statements
- **Temporal Information**: Adding timestamps and validity periods
- **Verification**: Recording verification status and methods
- **Context**: Adding contextual information about statements

```kotlin
// Example: Scientific data with provenance and confidence
val graph = Rdf.graph {
    val result = iri("http://example.org/experiment1")
    val temperature = iri("http://example.org/temperature")

    result - temperature - 25.5
    reifies(result, temperature, 25.5.toLiteral()) { r ->
        r - iri("http://example.org/instrument") - "Thermometer-123"
        r - iri("http://example.org/confidence") - 0.98
        r - DCTERMS.date - "2025-01-15T14:30:00Z"
        r - iri("http://example.org/calibrated") - true
    }
}
```

### **Type Safety**

`reifies` takes an `RdfResource` subject and an `Iri` predicate, so a literal subject is a compile-time error rather than a runtime failure. RDF 1.2 forbids triple terms in subject position; see the [migration guide](../guides/migrating-to-rdf-1.2.md).
