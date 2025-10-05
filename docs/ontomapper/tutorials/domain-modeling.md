# Domain Modeling with OntoMapper

This tutorial covers best practices for creating domain interfaces that work well with OntoMapper's side-channel architecture.

## Principles of Domain Modeling

### 1. Keep Interfaces Pure

Domain interfaces should contain only business logic and use pure Kotlin types:

```kotlin
// ✅ Good - Pure domain interface
@RdfClass(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/age")
    val age: List<Int>
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/knows")
    val friends: List<Person>
}

// ❌ Bad - RDF types in domain interface
@RdfClass(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    val name: List<String>
    val rdfNode: RdfTerm        // Don't do this!
    val rdfGraph: RdfGraph      // Don't do this!
}
```

### 2. Use Appropriate Collection Types

Choose the right collection type for your use case:

```kotlin
interface Product {
    // Single value (use List and take firstOrNull())
    @get:RdfProperty(iri = "http://example.org/name")
    val name: List<String>
    
    // Multiple values
    @get:RdfProperty(iri = "http://example.org/tags")
    val tags: List<String>
    
    // Related objects
    @get:RdfProperty(iri = "http://example.org/category")
    val categories: List<Category>
    
    // Optional single object (use List and take firstOrNull())
    @get:RdfProperty(iri = "http://example.org/manufacturer")
    val manufacturer: List<Manufacturer>
}
```

### 3. Design for Extensibility

Design interfaces that can grow over time:

```kotlin
// ✅ Good - Extensible design
@RdfClass(iri = "http://example.org/Product")
interface Product {
    // Core properties
    @get:RdfProperty(iri = "http://example.org/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://example.org/price")
    val price: List<Double>
    
    // Related objects
    @get:RdfProperty(iri = "http://example.org/category")
    val categories: List<Category>
    
    // New properties can be added without breaking existing code
    // Access via side-channel: product.asRdf().extras
}
```

## Property Types and Mapping

### Primitive Types

Map RDF literals to appropriate Kotlin types:

```kotlin
interface Example {
    // String literals
    @get:RdfProperty(iri = "http://example.org/name")
    val name: List<String>
    
    // Numeric literals
    @get:RdfProperty(iri = "http://example.org/age")
    val age: List<Int>
    
    @get:RdfProperty(iri = "http://example.org/height")
    val height: List<Double>
    
    // Boolean literals
    @get:RdfProperty(iri = "http://example.org/isActive")
    val isActive: List<Boolean>
    
    // Date literals (as strings, parse as needed)
    @get:RdfProperty(iri = "http://example.org/birthDate")
    val birthDate: List<String>
}
```

### Object Properties

Map RDF objects to domain interfaces:

```kotlin
@RdfClass(iri = "http://example.org/Person")
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>
    
    // Single related object
    @get:RdfProperty(iri = "http://example.org/employer")
    val employer: List<Organization>
    
    // Multiple related objects
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/knows")
    val friends: List<Person>
    
    // Self-referential properties
    @get:RdfProperty(iri = "http://example.org/parent")
    val parents: List<Person>
    
    @get:RdfProperty(iri = "http://example.org/child")
    val children: List<Person>
}
```

### Complex Relationships

Model complex relationships between entities:

```kotlin
@RdfClass(iri = "http://example.org/Order")
interface Order {
    @get:RdfProperty(iri = "http://example.org/orderNumber")
    val orderNumber: List<String>
    
    @get:RdfProperty(iri = "http://example.org/customer")
    val customer: List<Customer>
    
    @get:RdfProperty(iri = "http://example.org/orderItem")
    val items: List<OrderItem>
    
    @get:RdfProperty(iri = "http://example.org/shippingAddress")
    val shippingAddress: List<Address>
}

@RdfClass(iri = "http://example.org/OrderItem")
interface OrderItem {
    @get:RdfProperty(iri = "http://example.org/product")
    val product: List<Product>
    
    @get:RdfProperty(iri = "http://example.org/quantity")
    val quantity: List<Int>
    
    @get:RdfProperty(iri = "http://example.org/unitPrice")
    val unitPrice: List<Double>
}
```

## Handling Optional Values

### Single Optional Values

Use `List<T>` and access with `firstOrNull()`:

```kotlin
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/age")
    val age: List<Int>
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/mbox")
    val email: List<String>
}

// Usage
val person: Person = materializeFromRdf(...)
val name = person.name.firstOrNull() ?: "Unknown"
val age = person.age.firstOrNull() ?: 0
val email = person.email.firstOrNull()
```

### Multiple Optional Values

Use `List<T>` and filter as needed:

```kotlin
interface Product {
    @get:RdfProperty(iri = "http://example.org/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://example.org/description")
    val descriptions: List<String>
    
    @get:RdfProperty(iri = "http://example.org/tags")
    val tags: List<String>
}

// Usage
val product: Product = materializeFromRdf(...)
val primaryName = product.name.firstOrNull() ?: "Unnamed Product"
val allDescriptions = product.descriptions.filter { it.isNotBlank() }
val activeTags = product.tags.filter { it.isNotEmpty() }
```

## Inheritance and Polymorphism

### Interface Inheritance

Use interface inheritance to model type hierarchies:

```kotlin
@RdfClass(iri = "http://example.org/Person")
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/age")
    val age: List<Int>
}

@RdfClass(iri = "http://example.org/Employee")
interface Employee : Person {
    @get:RdfProperty(iri = "http://example.org/employeeId")
    val employeeId: List<String>
    
    @get:RdfProperty(iri = "http://example.org/salary")
    val salary: List<Double>
}

@RdfClass(iri = "http://example.org/Customer")
interface Customer : Person {
    @get:RdfProperty(iri = "http://example.org/customerId")
    val customerId: List<String>
    
    @get:RdfProperty(iri = "http://example.org/loyaltyPoints")
    val loyaltyPoints: List<Int>
}
```

### Polymorphic Collections

Handle collections of different types:

```kotlin
@RdfClass(iri = "http://example.org/Organization")
interface Organization {
    @get:RdfProperty(iri = "http://example.org/name")
    val name: List<String>
    
    // Collection of different person types
    @get:RdfProperty(iri = "http://example.org/member")
    val members: List<Person>  // Can be Employee, Customer, or Person
}

// Usage
val org: Organization = materializeFromRdf(...)
org.members.forEach { member ->
    when (member) {
        is Employee -> println("Employee: ${member.employeeId.firstOrNull()}")
        is Customer -> println("Customer: ${member.customerId.firstOrNull()}")
        else -> println("Person: ${member.name.firstOrNull()}")
    }
}
```

## Validation and Constraints

### Domain-Level Validation

Add validation logic to your domain interfaces:

```kotlin
@RdfClass(iri = "http://example.org/Product")
interface Product {
    @get:RdfProperty(iri = "http://example.org/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://example.org/price")
    val price: List<Double>
    
    @get:RdfProperty(iri = "http://example.org/stockQuantity")
    val stockQuantity: List<Int>
    
    // Validation helper methods (optional)
    fun isValid(): Boolean {
        val name = name.firstOrNull()
        val price = price.firstOrNull()
        val stock = stockQuantity.firstOrNull()
        
        return name != null && 
               price != null && price > 0 && 
               stock != null && stock >= 0
    }
    
    fun getValidationErrors(): List<String> {
        val errors = mutableListOf<String>()
        
        if (name.firstOrNull() == null) {
            errors.add("Product name is required")
        }
        
        val price = price.firstOrNull()
        if (price == null || price <= 0) {
            errors.add("Product price must be positive")
        }
        
        val stock = stockQuantity.firstOrNull()
        if (stock == null || stock < 0) {
            errors.add("Stock quantity cannot be negative")
        }
        
        return errors
    }
}
```

### SHACL Integration

Use SHACL for structural validation:

```kotlin
// Materialize with validation
val product: Product = rdfRef.asType(validate = true)

// Or validate manually
val rdfHandle = product.asRdf()
rdfHandle.validateOrThrow()
```

## Performance Considerations

### Lazy Evaluation

Properties are evaluated lazily, so expensive operations are deferred:

```kotlin
interface LargeDataset {
    @get:RdfProperty(iri = "http://example.org/item")
    val items: List<Item>  // Only evaluated when accessed
    
    @get:RdfProperty(iri = "http://example.org/statistics")
    val statistics: List<String>  // Only evaluated when accessed
}

// Usage
val dataset: LargeDataset = materializeFromRdf(...)
// No expensive operations yet

val firstItem = dataset.items.firstOrNull()  // Now items are loaded
val stats = dataset.statistics.firstOrNull()  // Now statistics are loaded
```

### Caching

Results are cached after first access:

```kotlin
val person: Person = materializeFromRdf(...)

// First access - queries RDF graph
val name1 = person.name.firstOrNull()

// Second access - uses cached result
val name2 = person.name.firstOrNull()

// name1 === name2 (same reference)
```

## Testing Domain Interfaces

### Unit Testing

Test domain interfaces independently of RDF:

```kotlin
class PersonTest {
    @Test
    fun `person validation works correctly`() {
        // Create a mock person (no RDF involved)
        val person = object : Person {
            override val name = listOf("John Doe")
            override val age = listOf(30)
            override val friends = emptyList()
        }
        
        // Test domain logic
        assertTrue(person.name.firstOrNull() == "John Doe")
        assertTrue(person.age.firstOrNull() == 30)
    }
}
```

### Integration Testing

Test the full materialization process:

```kotlin
class PersonIntegrationTest {
    @Test
    fun `person materialization works end-to-end`() {
        val repo = Rdf.memory()
        repo.add {
            val person = iri("http://example.org/person")
            person - FOAF.name - "John Doe"
            person - FOAF.age - 30
        }
        
        val personRef = RdfRef(iri("http://example.org/person"), repo.defaultGraph)
        val person: Person = personRef.asType()
        
        assertEquals("John Doe", person.name.firstOrNull())
        assertEquals(30, person.age.firstOrNull())
    }
}
```

## Best Practices Summary

### ✅ Do

- Keep domain interfaces pure (no RDF types)
- Use `List<T>` for all properties (single or multiple values)
- Access single values with `firstOrNull()`
- Design for extensibility
- Use interface inheritance for type hierarchies
- Add domain-level validation methods
- Test domain interfaces independently

### ❌ Don't

- Include RDF types in domain interfaces
- Use `Optional<T>` or nullable types directly
- Assume properties always have values
- Mix RDF operations with domain logic
- Create deep inheritance hierarchies
- Ignore validation requirements

## Next Steps

- **Explore [RDF Integration](rdf-integration.md)** - Advanced side-channel usage
- **Learn about [Validation](validation.md)** - SHACL validation patterns
- **Check out [Advanced Usage](advanced-usage.md)** - Complex scenarios
- **See [Practical Examples](../examples/README.md)** - Real-world use cases
