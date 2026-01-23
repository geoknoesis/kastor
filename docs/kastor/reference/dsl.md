# DSL Reference

## TripleDsl and GraphDsl API Reference

### Prefix Mapping Configuration

#### `prefixes(configure: MutableMap<String, String>.() -> Unit)`
Configure multiple prefix mappings at once.

```kotlin
import com.geoknoesis.kastor.rdf.vocab.DCAT
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF

repo.add {
    prefixes {
        "foaf" to FOAF.namespace
        "dcat" to DCAT.namespace
        "dcterms" to DCTERMS.namespace
    }
    
    val person = iri("http://example.org/person")
    person - "foaf:name" - "Alice"
}
```

#### `prefix(name: String, namespace: String)`
Add a single prefix mapping.

```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF

repo.add {
    prefix("foaf", FOAF.namespace)
    
    val person = iri("http://example.org/person")
    person - "foaf:name" - "Alice"
}
```

#### `qname(iriOrQName: String): Iri`
Create an IRI from a QName or full IRI string.

```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF

repo.add {
    prefix("foaf", FOAF.namespace)
    
    val nameIri = qname("foaf:name")  // Returns Iri("http://xmlns.com/foaf/0.1/name")
    val person = iri("http://example.org/person")
    person - nameIri - "Alice"
}
```

### Triple Creation Syntax

#### Ultra-Compact Syntax (Bracket Notation)

```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF

// With vocabulary constants
person[FOAF.name] = "Alice"
person[FOAF.age] = 30

// With QNames (requires prefix mapping)
repo.add {
    prefix("foaf", FOAF.namespace)
    
    person["foaf:name"] = "Alice"
    person["foaf:age"] = 30
}

// With IRI objects
val name = FOAF.name
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
import com.geoknoesis.kastor.rdf.vocab.FOAF

// With vocabulary constants
person has FOAF.name with "Alice"

// With QNames (requires prefix mapping)
repo.add {
    prefix("foaf", FOAF.namespace)
    
    person has "foaf:name" with "Alice"
    person has "foaf:age" with 30
}

// With IRI objects
val name = FOAF.name
person has name with "Alice"
```

#### Minus Operator Syntax

```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF

// With vocabulary constants
person - FOAF.name - "Alice"

// With QNames (requires prefix mapping)
repo.add {
    prefix("foaf", FOAF.namespace)
    
    person - "foaf:name" - "Alice"
    person - "foaf:age" - 30
}

// With IRI objects
val name = FOAF.name
person - name - "Alice"
```

### QName Resolution

#### Automatic Resolution
QNames are automatically resolved to full IRIs using the configured prefix mappings.

```kotlin
import com.geoknoesis.kastor.rdf.vocab.DCAT
import com.geoknoesis.kastor.rdf.vocab.FOAF

repo.add {
    prefixes {
        "foaf" to FOAF.namespace
        "dcat" to DCAT.namespace
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
import com.geoknoesis.kastor.rdf.vocab.FOAF

repo.add {
    prefix("foaf", FOAF.namespace)
    
    // This will throw IllegalArgumentException: Unknown prefix: 'unknown'
    person - "unknown:name" - "Alice"
}
```

### Standalone Graph Creation

#### `Rdf.graph { }`
Create a standalone RDF graph with the same DSL syntax.

```kotlin
import com.geoknoesis.kastor.rdf.vocab.DCAT
import com.geoknoesis.kastor.rdf.vocab.FOAF

val graph = Rdf.graph {
    prefixes {
        "foaf" to FOAF.namespace
        "dcat" to DCAT.namespace
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
import com.geoknoesis.kastor.rdf.vocab.DCAT
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SCHEMA

repo.add {
    // Configure all prefixes at the beginning
    prefixes {
        "foaf" to FOAF.namespace
        "dcat" to DCAT.namespace
        "dcterms" to DCTERMS.namespace
        "rdf" to RDF.namespace
        "schema" to SCHEMA.namespace
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
import com.geoknoesis.kastor.rdf.vocab.FOAF

repo.add {
    prefix("foaf", FOAF.namespace)
    
    val person = iri("http://example.org/person")
    
    // Use QNames for standard vocabularies
    person - "foaf:name" - "Alice"
    
    // Use full IRIs for custom properties
    val customProp = iri("http://example.org/customProperty")
    person - customProp - "value"
    
    // Use qname() for dynamic QName creation
    val dynamicProp = qname("foaf:${propertyName}")
    person - dynamicProp - "value"
}
```

#### 3. Use Consistent Naming
```kotlin
// Good: Consistent prefix names
import com.geoknoesis.kastor.rdf.vocab.DCAT
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF

prefixes {
    "foaf" to FOAF.namespace
    "dcat" to DCAT.namespace
    "dcterms" to DCTERMS.namespace
}

// Avoid: Inconsistent prefix names
prefixes {
    "f" to FOAF.namespace                 // Too short
    "data-catalog" to DCAT.namespace      // Too long
    "DC" to DCTERMS.namespace             // Inconsistent case
}
```

### Performance Considerations

#### Prefix Mapping Scope
Prefix mappings are scoped to the DSL block where they're defined.

```kotlin
// Each add block has its own prefix mappings
import com.geoknoesis.kastor.rdf.vocab.DCAT
import com.geoknoesis.kastor.rdf.vocab.FOAF

repo.add {
    prefix("foaf", FOAF.namespace)
    person - "foaf:name" - "Alice"
}

repo.add {
    // This block doesn't have the foaf prefix
    // person - "foaf:name" - "Bob"  // ❌ Would throw error
    
    prefix("dcat", DCAT.namespace)
    catalog - "dcat:title" - "My Catalog"
}
```

#### QName Resolution Performance
QName resolution is performed during triple creation and is very fast. The resolution logic is optimized for common cases.

```kotlin
// Efficient: QName resolution happens once per triple
import com.geoknoesis.kastor.rdf.vocab.FOAF

repo.add {
    prefix("foaf", FOAF.namespace)
    
    // Each of these resolves "foaf:name" once
    person1 - "foaf:name" - "Alice"
    person2 - "foaf:name" - "Bob"
    person3 - "foaf:name" - "Charlie"
}
```



