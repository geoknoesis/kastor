# DSL Reference

## TripleDsl and GraphDsl API Reference

### Prefix Mapping Configuration

#### `prefixes(configure: MutableMap<String, String>.() -> Unit)`
Configure multiple prefix mappings at once.

```kotlin
repo.add {
    prefixes {
        "foaf" to "http://xmlns.com/foaf/0.1/"
        "dcat" to "http://www.w3.org/ns/dcat#"
        "dcterms" to "http://purl.org/dc/terms/"
    }
    
    val person = iri("http://example.org/person")
    person - "foaf:name" - "Alice"
}
```

#### `prefix(name: String, namespace: String)`
Add a single prefix mapping.

```kotlin
repo.add {
    prefix("foaf", "http://xmlns.com/foaf/0.1/")
    
    val person = iri("http://example.org/person")
    person - "foaf:name" - "Alice"
}
```

#### `qname(iriOrQName: String): Iri`
Create an IRI from a QName or full IRI string.

```kotlin
repo.add {
    prefix("foaf", "http://xmlns.com/foaf/0.1/")
    
    val nameIri = qname("foaf:name")  // Returns Iri("http://xmlns.com/foaf/0.1/name")
    val person = iri("http://example.org/person")
    person - nameIri - "Alice"
}
```

### Triple Creation Syntax

#### Ultra-Compact Syntax (Bracket Notation)

```kotlin
// With full IRIs
person["http://xmlns.com/foaf/0.1/name"] = "Alice"
person["http://xmlns.com/foaf/0.1/age"] = 30

// With QNames (requires prefix mapping)
repo.add {
    prefix("foaf", "http://xmlns.com/foaf/0.1/")
    
    person["foaf:name"] = "Alice"
    person["foaf:age"] = 30
}

// With IRI objects
val name = iri("http://xmlns.com/foaf/0.1/name")
person[name] = "Alice"
```

**Supported value types:**
- `String` → `Literal(value, XSD.string)`
- `Int` → `Literal(value.toString(), XSD.integer)`
- `Double` → `Literal(value.toString(), XSD.double)`
- `Boolean` → `Literal(value.toString(), XSD.boolean)`
- `RdfTerm` → used as-is
- Other types → `Literal(value.toString(), XSD.string)`

#### Natural Language Syntax

```kotlin
// With full IRIs
person has iri("http://xmlns.com/foaf/0.1/name") with "Alice"

// With QNames (requires prefix mapping)
repo.add {
    prefix("foaf", "http://xmlns.com/foaf/0.1/")
    
    person has "foaf:name" with "Alice"
    person has "foaf:age" with 30
}

// With IRI objects
val name = iri("http://xmlns.com/foaf/0.1/name")
person has name with "Alice"
```

#### Minus Operator Syntax

```kotlin
// With full IRIs
person - iri("http://xmlns.com/foaf/0.1/name") - "Alice"

// With QNames (requires prefix mapping)
repo.add {
    prefix("foaf", "http://xmlns.com/foaf/0.1/")
    
    person - "foaf:name" - "Alice"
    person - "foaf:age" - 30
}

// With IRI objects
val name = iri("http://xmlns.com/foaf/0.1/name")
person - name - "Alice"
```

### QName Resolution

#### Automatic Resolution
QNames are automatically resolved to full IRIs using the configured prefix mappings.

```kotlin
repo.add {
    prefixes {
        "foaf" to "http://xmlns.com/foaf/0.1/"
        "dcat" to "http://www.w3.org/ns/dcat#"
    }
    
    // These QNames are automatically resolved:
    person - "foaf:name" - "Alice"           // → http://xmlns.com/foaf/0.1/name
    person - "dcat:keyword" - "example"      // → http://www.w3.org/ns/dcat#keyword
}
```

#### QName Detection Rules
A string is considered a QName if:
- It contains a colon (`:`)
- It doesn't start with common IRI schemes (`http://`, `https://`, `urn:`, `file:`, `data:`)
- It doesn't start or end with a colon

```kotlin
// QNames (will be resolved)
"foaf:name"        // ✅ QName
"dcat:keyword"     // ✅ QName
"schema:Person"    // ✅ QName

// Full IRIs (used as-is)
"http://example.org/name"           // ✅ Full IRI
"https://example.org/name"          // ✅ Full IRI
"urn:uuid:12345678-1234-1234-1234-123456789abc"  // ✅ Full IRI

// Invalid (will cause errors)
":name"            // ❌ Invalid (starts with colon)
"foaf:"            // ❌ Invalid (ends with colon)
"name"             // ❌ Not a QName (no colon)
```

#### Error Handling
```kotlin
repo.add {
    prefix("foaf", "http://xmlns.com/foaf/0.1/")
    
    // This will throw IllegalArgumentException: Unknown prefix: 'unknown'
    person - "unknown:name" - "Alice"
}
```

### Standalone Graph Creation

#### `Rdf.graph { }`
Create a standalone RDF graph with the same DSL syntax.

```kotlin
val graph = Rdf.graph {
    prefixes {
        "foaf" to "http://xmlns.com/foaf/0.1/"
        "dcat" to "http://www.w3.org/ns/dcat#"
    }
    
    val person = iri("http://example.org/person")
    person - "foaf:name" - "Alice"
    person["foaf:age"] = 30
    person has "dcat:keyword" with "example"
}

// Use the graph
println("Graph has ${graph.getTriples().size} triples")
```

### Best Practices

#### 1. Configure Prefixes at the Top
```kotlin
repo.add {
    // Configure all prefixes at the beginning
    prefixes {
        "foaf" to "http://xmlns.com/foaf/0.1/"
        "dcat" to "http://www.w3.org/ns/dcat#"
        "dcterms" to "http://purl.org/dc/terms/"
        "schema" to "http://schema.org/"
    }
    
    // Use QNames throughout
    val person = iri("http://example.org/person")
    person - "rdf:type" - "foaf:Person"
    person - "foaf:name" - "Alice"
    person - "schema:email" - "alice@example.com"
}
```

#### 2. Mix QNames and Full IRIs When Needed
```kotlin
repo.add {
    prefix("foaf", "http://xmlns.com/foaf/0.1/")
    
    val person = iri("http://example.org/person")
    
    // Use QNames for standard vocabularies
    person - "foaf:name" - "Alice"
    
    // Use full IRIs for custom properties
    person - "http://example.org/customProperty" - "value"
    
    // Use qname() for dynamic QName creation
    val dynamicProp = qname("foaf:${propertyName}")
    person - dynamicProp - "value"
}
```

#### 3. Use Consistent Naming
```kotlin
// Good: Consistent prefix names
prefixes {
    "foaf" to "http://xmlns.com/foaf/0.1/"
    "dcat" to "http://www.w3.org/ns/dcat#"
    "dcterms" to "http://purl.org/dc/terms/"
}

// Avoid: Inconsistent prefix names
prefixes {
    "f" to "http://xmlns.com/foaf/0.1/"           // Too short
    "data-catalog" to "http://www.w3.org/ns/dcat#"  // Too long
    "DC" to "http://purl.org/dc/terms/"           // Inconsistent case
}
```

### Performance Considerations

#### Prefix Mapping Scope
Prefix mappings are scoped to the DSL block where they're defined.

```kotlin
// Each add block has its own prefix mappings
repo.add {
    prefix("foaf", "http://xmlns.com/foaf/0.1/")
    person - "foaf:name" - "Alice"
}

repo.add {
    // This block doesn't have the foaf prefix
    // person - "foaf:name" - "Bob"  // ❌ Would throw error
    
    prefix("dcat", "http://www.w3.org/ns/dcat#")
    catalog - "dcat:title" - "My Catalog"
}
```

#### QName Resolution Performance
QName resolution is performed during triple creation and is very fast. The resolution logic is optimized for common cases.

```kotlin
// Efficient: QName resolution happens once per triple
repo.add {
    prefix("foaf", "http://xmlns.com/foaf/0.1/")
    
    // Each of these resolves "foaf:name" once
    person1 - "foaf:name" - "Alice"
    person2 - "foaf:name" - "Bob"
    person3 - "foaf:name" - "Charlie"
}
```



