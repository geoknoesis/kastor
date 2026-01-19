# Annotations API Reference

Complete reference for Kastor Gen annotations used in domain modeling.

## RdfClass

Annotation for marking domain classes that should be backed by RDF.

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RdfClass(val iri: String = "")
```

**Parameters:**
- `iri: String = ""` - The RDF class IRI

**Target:**
- Classes and interfaces

**Retention:**
- Source (used by KSP processor)

**Usage:**
```kotlin
@RdfClass(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    // ...
}

@RdfClass(iri = "http://example.org/Product")
interface Product {
    // ...
}
```

**Notes:**
- The IRI should be a valid RDF class identifier
- Used by KSP processor to generate wrapper classes
- Should be applied to interfaces, not concrete classes

## RdfProperty

Annotation for marking properties that map to RDF predicates.

```kotlin
@Target(AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.SOURCE)
annotation class RdfProperty(val iri: String)
```

**Parameters:**
- `iri: String` - The RDF predicate IRI

**Target:**
- Property getters

**Retention:**
- Source (used by KSP processor)

**Usage:**
```kotlin
@RdfClass(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/age")
    val age: List<Int>
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/knows")
    val friends: List<Person>
}
```

**Notes:**
- Must be applied to property getters using `@get:` syntax
- The IRI should be a valid RDF predicate identifier
- Used by KSP processor to generate property accessors
- Properties should use `List<T>` type for consistency

## Annotation Processing

### KSP Processor

The Kastor Gen KSP processor scans for these annotations and generates wrapper classes:

```kotlin
// Input: Domain interface
@RdfClass(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>
}

// Output: Generated wrapper class
internal class PersonWrapper(override val rdf: RdfHandle) : Person, RdfBacked {
    override val name: List<String> by lazy {
        KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.name)
            .map { it.lexical }
    }
    
    companion object {
        init {
            kastor.gen.registry[Person::class.java] = { handle -> PersonWrapper(handle) }
        }
    }
}
```

### Processing Rules

1. **Class Processing:**
   - Only classes annotated with `@RdfClass` are processed
   - Both interfaces and classes are supported
   - Inheritance is preserved in generated wrappers

2. **Property Processing:**
   - Only properties annotated with `@RdfProperty` are processed
   - Must be applied to getters using `@get:` syntax
   - Properties must use `List<T>` type

3. **Type Support:**
   - Primitive types: `String`, `Int`, `Double`, `Boolean`
   - Collections: `List<T>` where T is supported
   - Domain objects: Interfaces annotated with `@RdfClass`

## Common Patterns

### Single Value Properties

Use `List<T>` and access with `firstOrNull()`:

```kotlin
@RdfClass(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>  // Single name
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/age")
    val age: List<Int>      // Single age
}

// Usage
val person: Person = materializeFromRdf(...)
val name = person.name.firstOrNull() ?: "Unknown"
val age = person.age.firstOrNull() ?: 0
```

### Multiple Value Properties

Use `List<T>` for multiple values:

```kotlin
@RdfClass(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val names: List<String>     // Multiple names
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/mbox")
    val emails: List<String>    // Multiple email addresses
}

// Usage
val person: Person = materializeFromRdf(...)
val allNames = person.names
val primaryEmail = person.emails.firstOrNull()
```

### Object Properties

Map to domain interfaces:

```kotlin
@RdfClass(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/knows")
    val friends: List<Person>   // Related Person objects
    
    @get:RdfProperty(iri = "http://example.org/employer")
    val employer: List<Organization>  // Related Organization objects
}

// Usage
val person: Person = materializeFromRdf(...)
val friends = person.friends
val employer = person.employer.firstOrNull()
```

### Inheritance

Inheritance is supported:

```kotlin
@RdfClass(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>
}

@RdfClass(iri = "http://example.org/Employee")
interface Employee : Person {
    @get:RdfProperty(iri = "http://example.org/employeeId")
    val employeeId: List<String>
    
    @get:RdfProperty(iri = "http://example.org/salary")
    val salary: List<Double>
}
```

## Configuration

### KSP Configuration

Configure KSP processor in `build.gradle.kts`:

```kotlin
ksp {
    arg("kastor.gen.package", "com.example.mydomain")
    arg("kastor.gen.generate.registry", "true")
    arg("kastor.gen.validation.enabled", "true")
}
```

### Available Arguments

- `kastor.gen.package` - Base package for generated code
- `kastor.gen.generate.registry` - Whether to generate registry entries
- `kastor.gen.validation.enabled` - Whether to enable validation
- `kastor.gen.cache.enabled` - Whether to enable caching

## Best Practices

### ✅ Do

- Use descriptive IRIs for classes and properties
- Apply `@RdfClass` to interfaces, not concrete classes
- Use `@get:` syntax for property annotations
- Use `List<T>` for all properties
- Group related properties logically
- Use inheritance for type hierarchies

### ❌ Don't

- Use invalid IRIs
- Apply annotations to concrete classes
- Forget the `@get:` prefix for properties
- Use nullable types directly
- Mix RDF types in domain interfaces
- Create deep inheritance hierarchies

## Error Handling

### Common Errors

1. **Missing IRI:**
   ```
   Error: @RdfProperty requires an IRI
   ```

2. **Invalid Target:**
   ```
   Error: @RdfProperty can only be applied to property getters
   ```

3. **Unsupported Type:**
   ```
   Error: Type 'CustomType' is not supported for RDF mapping
   ```

### Troubleshooting

1. **Annotations not processed:**
   - Check KSP configuration
   - Ensure annotations are imported correctly
   - Verify build completes successfully

2. **Properties not mapped:**
   - Check `@get:` syntax
   - Verify IRI is valid
   - Ensure property uses `List<T>` type

3. **Inheritance issues:**
   - Check parent class annotations
   - Verify type compatibility
   - Ensure proper interface inheritance

## Examples

### Complete Example

```kotlin
// Domain interfaces
@RdfClass(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/age")
    val age: List<Int>
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/mbox")
    val email: List<String>
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/knows")
    val friends: List<Person>
}

@RdfClass(iri = "http://example.org/Organization")
interface Organization {
    @get:RdfProperty(iri = "http://example.org/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://example.org/employee")
    val employees: List<Person>
}

// Usage
val person: Person = materializeFromRdf(...)
val name = person.name.firstOrNull() ?: "Unknown"
val friends = person.friends
val employer = person.asRdf().extras.objects(EMPLOYER, Organization::class.java).firstOrNull()
```

### Complex Example

```kotlin
@RdfClass(iri = "http://example.org/Project")
interface Project {
    @get:RdfProperty(iri = "http://example.org/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://example.org/description")
    val description: List<String>
    
    @get:RdfProperty(iri = "http://example.org/startDate")
    val startDate: List<String>
    
    @get:RdfProperty(iri = "http://example.org/endDate")
    val endDate: List<String>
    
    @get:RdfProperty(iri = "http://example.org/manager")
    val manager: List<Person>
    
    @get:RdfProperty(iri = "http://example.org/teamMember")
    val teamMembers: List<Person>
    
    @get:RdfProperty(iri = "http://example.org/task")
    val tasks: List<Task>
    
    @get:RdfProperty(iri = "http://example.org/budget")
    val budget: List<Double>
    
    @get:RdfProperty(iri = "http://example.org/isActive")
    val isActive: List<Boolean>
}

@RdfClass(iri = "http://example.org/Task")
interface Task {
    @get:RdfProperty(iri = "http://example.org/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://example.org/status")
    val status: List<String>
    
    @get:RdfProperty(iri = "http://example.org/assignee")
    val assignee: List<Person>
    
    @get:RdfProperty(iri = "http://example.org/dueDate")
    val dueDate: List<String>
}
```

This comprehensive annotation system provides a clean, type-safe way to map RDF data to Kotlin domain objects while maintaining the purity of domain interfaces.



