# The RDF Term Model API

This API provides a performant, type-safe, and idiomatic Kotlin representation of the Resource Description Framework (RDF) data model. It serves as the foundation for building RDF-aware applications, from simple data manipulation to complex graph processing.

The core design goals are:

  * **Type Safety**: Prevent common errors at compile time.
  * **Performance**: Use modern Kotlin features to minimize overhead.
  * **Clarity**: Create an expressive and readable API that feels natural to use.

-----

## The RDF Data Model: A Quick Tour 🧠

At its heart, RDF is a simple yet powerful way to make statements about resources. Think of it like building simple sentences. Every statement, called a **triple**, consists of three parts:

1.  **Subject**: The thing you are talking about.
2.  **Predicate**: What property or relationship you are describing.
3.  **Object**: The value of that property or the thing it's related to.

For example, the sentence "The sky has the color blue" can be represented as an RDF triple.

  * **Subject**: The sky
  * **Predicate**: has the color
  * **Object**: blue

These three components are represented in the API by a core set of types.

-----

## Mapping RDF to the Kotlin API 🗺️

The API maps these fundamental RDF concepts directly to a `sealed` type hierarchy, ensuring that you always know what kind of term you're working with. The root of this hierarchy is the `RdfTerm` interface.

### Resources: The Subject of a Statement

Resources are the "nouns" of RDF—the things you describe. In our API, they are represented by the `RdfResource` sealed interface. There are two primary kinds:

  * **IRI (Internationalized Resource Identifier)**: An IRI is a unique, global identifier, like a URL. It's the most common way to identify a resource. In the API, this is a `value class` for maximum performance and type safety.

    ```kotlin
    import com.geoknoesis.kastor.rdf.vocab.FOAF

    // Creating an IRI
    val person = iri("http://example.com/person/1")
    val predicate = FOAF.name
    ```

  * **Blank Node**: A Blank Node represents a resource without a global identifier. It's an anonymous node that is only unique within a specific context.

    ```kotlin
    // Creating a Blank Node
    val anonymousPerson = bnode("some-local-id-123")
    ```

### Literals: The Value of a Property

Literals represent raw data values, like a name, an age, or a date. They can only be the **object** of a triple. The API provides the `Literal` sealed interface.

  * **Plain/String Literals**: A simple string value. In modern RDF, this is a string with the datatype `xsd:string`.

    ```kotlin
    // Creating a plain string literal
    val name = string("Alice")
    ```

  * **Language-Tagged Literals**: A string in a specific human language.

    ```kotlin
    // Creating a French language literal
    val greeting = lang("Bonjour", "fr")
    ```

  * **Datatyped Literals**: A value with a specific datatype, like an integer or a date. You can create these directly from native Kotlin types using `.toLiteral()` extensions, or by specifying the datatype's IRI.

    ```kotlin
    // From native types
    val age = 30.toLiteral() // "30"^^xsd:integer
    val created = LocalDate.now().toLiteral() // "2025-08-11"^^xsd:date

    // By specifying the datatype
    val price = Literal("29.99", XSD.decimal)
    ```

### Triples: The Complete Statement

A triple brings the subject, predicate, and object together to form a complete statement.

  * **`RdfTriple`**: This is a simple `data class` holding the three parts of a statement.
  * **`TripleTerm`**: This is for RDF-Star, which lets you make statements *about other statements*. It wraps a triple so it can be used as a subject or object.

<!-- end list -->

```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF

val alice = iri("ex:alice")
val name = FOAF.name
val knows = FOAF.knows
val bob = iri("ex:bob")

// A simple statement: "Alice has the name 'Alice'."
val t1 = triple(alice, name, string("Alice"))

// A statement about another statement: "<<alice knows bob>> has a confidence of 95%."
val t2_quoted = quoted(triple(alice, knows, bob))
val t2 = triple(t2_quoted, iri("ex:confidence"), 0.95.toLiteral())
```

## Triple DSL: Natural Language for RDF Statements 🌟

The API provides a powerful **Domain Specific Language (DSL)** that makes creating RDF triples feel as natural as writing English sentences. This DSL uses Kotlin's infix functions to create a fluent, readable syntax.

### The Core DSL: `has` and `with`

The DSL consists of two main infix functions that work together:

1. **`has`**: Connects a subject to a predicate
2. **`with`**: Connects a subject-predicate pair to an object

```kotlin
// Basic syntax: subject has predicate with object
val triple = alice has name with "Alice"
```

This reads naturally: "Alice has name with Alice" - which is exactly what the RDF triple represents!

### Complete DSL Examples

```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF

val alice = iri("ex:alice")
val name = FOAF.name
val age = FOAF.age
val knows = FOAF.knows
val bob = iri("ex:bob")
val email = FOAF.mbox

// Simple properties
val nameTriple = alice has name with "Alice"
val ageTriple = alice has age with 30
val emailTriple = alice has email with "alice@example.com"

// Relationships
val knowsTriple = alice has knows with bob

// Multiple triples for the same subject
val aliceTriples = listOf(
    alice has name with "Alice",
    alice has age with 30,
    alice has email with "alice@example.com",
    alice has knows with bob
)
```

### Supported Object Types

The `with` function automatically converts common Kotlin types to appropriate RDF literals:

```kotlin
// Strings become xsd:string literals
val nameTriple = alice has name with "Alice"

// Numbers become xsd:integer literals  
val ageTriple = alice has age with 30

// RDF terms are used as-is
val knowsTriple = alice has knows with bob

// You can also use explicit literals
val customTriple = alice has iri("ex:score") with 95.5.toLiteral()
```

### How It Works Under the Hood

The DSL is built on a simple helper class and infix functions:

```kotlin
// The helper class that holds subject and predicate
data class SubjectAndPredicate(val subject: RdfResource, val predicate: Iri)

// Infix function to connect subject and predicate
infix fun RdfResource.has(predicate: Iri) = SubjectAndPredicate(this, predicate)

// Infix function to connect subject-predicate pair with object
infix fun SubjectAndPredicate.with(obj: RdfTerm) = RdfTriple(this.subject, this.predicate, obj)

// Overloads for common types
infix fun SubjectAndPredicate.with(obj: String) = RdfTriple(this.subject, this.predicate, string(obj))
infix fun SubjectAndPredicate.with(obj: Int) = RdfTriple(this.subject, this.predicate, obj.toLiteral())
```

### Advanced DSL Usage

The DSL works seamlessly with other API features:

```kotlin
// Creating complex graphs
val personGraph = graph("ex:people") {
    addTriple(alice has name with "Alice")
    addTriple(alice has age with 30)
    addTriple(bob has name with "Bob")
    addTriple(bob has age with 25)
    addTriple(alice has knows with bob)
}

// Using with collections
val triples = listOf(
    alice has name with "Alice",
    alice has age with 30,
    bob has name with "Bob"
)

// Adding to repositories
repository.addTriple(null, alice has name with "Alice")
```

### Why This DSL is Powerful

1. **Readability**: Code reads like natural language
2. **Type Safety**: Compile-time checking prevents errors
3. **Performance**: Zero runtime overhead - it's just function calls
4. **Extensibility**: Easy to add new overloads for custom types
5. **Consistency**: Works the same way across the entire API

The DSL transforms RDF from a verbose, technical syntax into something that feels natural and intuitive to write and read.

-----

##  Why This Kotlin API is Superior to a Typical Java Approach ✨

This API isn't just a translation of RDF concepts; it's designed to leverage Kotlin's modern features to create a developer experience that is safer, more concise, and more performant than what's typically possible in traditional Java.

### 🛡️ Compile-Time Safety with Sealed Interfaces

In this API, `RdfTerm` is a **`sealed interface`**. This is a massive advantage. It means the compiler knows every possible subtype (`Iri`, `BlankNode`, `Literal`, etc.). When you use a `when` expression, the compiler forces you to handle every case, eliminating an entire category of runtime bugs.

**Kotlin (Safe & Exhaustive)**

```kotlin
fun getTermValue(term: RdfTerm): String = when (term) {
    is Iri -> "IRI: ${term.value}"
    is BlankNode -> "Blank Node: ${term.id}"
    is Literal -> "Literal: ${term.lexical}"
    is TripleTerm -> "Quoted Triple"
} 
// No `else` branch needed! The compiler guarantees we covered all cases.
```

**Typical Java (Risky & Verbose)**

```java
// In Java, you'd need instanceof checks, which are not exhaustive.
public String getTermValue(RdfTerm term) {
    if (term instanceof Iri) {
        return "IRI: " + ((Iri) term).getValue();
    } else if (term instanceof BlankNode) {
        return "Blank Node: " + ((BlankNode) term).getId();
    } else if (term instanceof Literal) {
        return "Literal: " + ((Literal) term).getLexical();
    }
    // What if a new type is added? This default might hide a bug.
    // The compiler offers no help here.
    return "Unknown term type"; 
}
```

### 🚀 Performance & Safety with Value Classes

The `Iri` and `BlankNode` types are \*\*`@JvmInline value class`\*\*es. This means that at compile time, we get full type safety—you can't accidentally use a `BlankNode`'s ID where an `Iri`'s value is expected. But at runtime, Kotlin compiles it down to a simple `String`, **avoiding the overhead of creating a wrapper object**.

You get the safety of a custom class with the performance of a primitive. Java does not have a stable equivalent to this feature yet.

### ✨\ Conciseness and Readability (DSL)

Kotlin allows for a much cleaner and more readable API through top-level functions, extension functions, and operator overloading.

Compare creating a literal from an integer.

**Kotlin (Concise & Fluent)**

```kotlin
// Extension function makes it feel like a built-in feature.
val age = 30.toLiteral()
```

**Typical Java (Verbose)**

```java
// Requires a static factory method call.
Literal age = Rdf.createLiteral(30);
```

This conciseness makes the code easier to write and, more importantly, easier to read and maintain.

### ☕ No More NullPointerExceptions

The entire API is built with Kotlin's null-safety in mind. The value accessors (`asInt()`, `asBoolean()`, etc.) return nullable types (`Int?`, `Boolean?`), forcing you to handle the possibility of a failed conversion safely. This eliminates one of the most common sources of runtime crashes in Java: the dreaded `NullPointerException`.




