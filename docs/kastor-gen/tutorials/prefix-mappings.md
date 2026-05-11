# Prefix Mappings and QNames

This guide explains how to use prefix mappings and QNames (qualified names) in Kastor Gen annotations to make your code more readable and maintainable.

## Table of Contents

- [Overview](#overview)
- [Basic Usage](#basic-usage)
- [Advanced Features](#advanced-features)
- [Best Practices](#best-practices)
- [Examples](#examples)
- [Troubleshooting](#troubleshooting)

## Overview

Instead of using full IRIs in your annotations:

```kotlin
@Rdf(iri = "http://www.w3.org/ns/dcat#Catalog")
interface Catalog {
    @Rdf(iri = "http://purl.org/dc/terms/title")
    val title: String
}
```

You can use QNames when prefixes are in scope. Prefer **`@file:Rdf(prefixes = …)`** at the top of the Kotlin file (applies to every `@Rdf(iri = …)` in that file), or pass **`prefixes = […]`** on the domain **`@Rdf`** for type-local bindings:

```kotlin
@file:Rdf(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#"),
        Prefix("dcterms", "http://purl.org/dc/terms/")
    ]
)

package com.example

import com.geoknoesis.kastor.gen.annotations.Prefix
import com.geoknoesis.kastor.gen.annotations.Rdf

@Rdf(iri = "dcat:Catalog")
interface Catalog {
    @Rdf(iri = "dcterms:title")
    val title: String
}
```

## Basic Usage

### Declaring prefix mappings

Declare prefixes with **`@file:Rdf(prefixes = …)`** (recommended for a whole file) or with **`prefixes = […]`** on a domain **`@Rdf`** alongside **`iri`**:

```kotlin
@Rdf(
    iri = "dcat:Catalog",
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#"),
        Prefix("dcterms", "http://purl.org/dc/terms/"),
        Prefix("foaf", "http://xmlns.com/foaf/0.1/")
    ],
)
interface Catalog { /* … */ }
```

### Using QNames in Annotations

Once declared, you can use QNames in **`@Rdf(iri = …)`** on types and properties:

```kotlin
@file:Rdf(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#"),
        Prefix("dcterms", "http://purl.org/dc/terms/")
    ]
)

package com.example

import com.geoknoesis.kastor.gen.annotations.Prefix
import com.geoknoesis.kastor.gen.annotations.Rdf

@Rdf(iri = "dcat:Catalog")
interface Catalog {
    @Rdf(iri = "dcterms:title")
    val title: String
    
    @Rdf(iri = "dcterms:description")
    val description: String
    
    @Rdf(iri = "dcat:dataset")
    val dataset: List<Dataset>
}
```

### File-Level Prefix Mappings

You can declare prefix mappings at the file level for all classes in the file:

```kotlin
@file:Rdf(
    prefixes = [
        Prefix("vcard", "http://www.w3.org/2006/vcard/ns#"),
        Prefix("org", "http://www.w3.org/ns/org#")
    ]
)

package com.example

import com.geoknoesis.kastor.gen.annotations.Prefix
import com.geoknoesis.kastor.gen.annotations.Rdf

@Rdf(iri = "vcard:Individual")
interface Person {
    @Rdf(iri = "vcard:fn")
    val fullName: String
}

@Rdf(iri = "org:Organization")
interface Organization {
    @Rdf(iri = "vcard:fn")
    val name: String
}
```

## Advanced Features

### Mixed Usage

You can mix QNames and full IRIs in the same interface:

```kotlin
@Rdf(
    iri = "http://www.w3.org/ns/dcat#Catalog",
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#"),
        Prefix("dcterms", "http://purl.org/dc/terms/")
    ],
)
interface Catalog {
    @Rdf(iri = "dcterms:title") // QName
    val title: String
    
    @Rdf(iri = "http://purl.org/dc/terms/description") // Full IRI
    val description: String
    
    @Rdf(iri = "dcat:dataset") // QName
    val dataset: List<Dataset>
}
```

### Custom Prefixes

Define your own prefixes for custom vocabularies:

```kotlin
@Rdf(
    iri = "ex:Location",
    prefixes = [
        Prefix("ex", "http://example.org/vocab#"),
        Prefix("schema", "http://schema.org/"),
        Prefix("geo", "http://www.w3.org/2003/01/geo/wgs84_pos#")
    ],
)
interface Location {
    @Rdf(iri = "ex:name")
    val name: String
    
    @Rdf(iri = "schema:address")
    val address: String
    
    @Rdf(iri = "geo:lat")
    val latitude: Double
    
    @Rdf(iri = "geo:long")
    val longitude: Double
}
```

### Multiple Prefix Mappings

You can declare multiple prefix mappings for different contexts:

```kotlin
// For DCAT vocabulary
@Rdf(
    iri = "dcat:Catalog",
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#"),
        Prefix("dcterms", "http://purl.org/dc/terms/")
    ],
)
interface Catalog { /* ... */ }

// For FOAF vocabulary
@Rdf(
    iri = "foaf:Person",
    prefixes = [
        Prefix("foaf", "http://xmlns.com/foaf/0.1/")
    ],
)
interface Person { /* ... */ }
```

## Best Practices

### 1. Use Standard Prefixes

Use well-known prefixes for common vocabularies:

```kotlin
// ✅ Good: Standard prefixes
@file:Rdf(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#"),
        Prefix("dcterms", "http://purl.org/dc/terms/"),
        Prefix("foaf", "http://xmlns.com/foaf/0.1/"),
        Prefix("skos", "http://www.w3.org/2004/02/skos/core#"),
        Prefix("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#"),
        Prefix("rdfs", "http://www.w3.org/2000/01/rdf-schema#")
    ]
)

// ❌ Avoid: Non-standard prefixes
@file:Rdf(
    prefixes = [
        Prefix("dc", "http://www.w3.org/ns/dcat#"), // Should be "dcat"
        Prefix("dublin", "http://purl.org/dc/terms/") // Should be "dcterms"
    ]
)
```

### 2. Group Related Prefixes

Group related prefixes together:

```kotlin
// ✅ Good: Grouped by domain
@file:Rdf(
    prefixes = [
        // Data catalog vocabulary
        Prefix("dcat", "http://www.w3.org/ns/dcat#"),
        Prefix("dcterms", "http://purl.org/dc/terms/"),
        
        // Social vocabulary
        Prefix("foaf", "http://xmlns.com/foaf/0.1/"),
        
        // Schema vocabulary
        Prefix("schema", "http://schema.org/"),
        
        // Custom vocabulary
        Prefix("ex", "http://example.org/vocab#")
    ]
)
```

### 3. Use File-Level Mappings for Consistency

Use file-level mappings when multiple classes share the same prefixes:

```kotlin
@file:Rdf(
    prefixes = [
        Prefix("vcard", "http://www.w3.org/2006/vcard/ns#"),
        Prefix("org", "http://www.w3.org/ns/org#")
    ]
)

// All classes in this file can use vcard: and org: prefixes
@Rdf(iri = "vcard:Individual")
interface Person { /* ... */ }

@Rdf(iri = "org:Organization")
interface Organization { /* ... */ }
```

### 4. Validate Prefix Mappings

Ensure your prefix mappings are correct:

```kotlin
// ✅ Good: Verified namespace URIs
@file:Rdf(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#"), // Verified
        Prefix("dcterms", "http://purl.org/dc/terms/") // Verified
    ]
)

// ❌ Avoid: Unverified or incorrect URIs
@file:Rdf(
    prefixes = [
        Prefix("dcat", "http://example.org/dcat#"), // Wrong namespace
        Prefix("dcterms", "http://purl.org/dc/terms") // Missing trailing slash
    ]
)
```

## Examples

### Complete DCAT Example

```kotlin
@file:Rdf(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#"),
        Prefix("dcterms", "http://purl.org/dc/terms/"),
        Prefix("foaf", "http://xmlns.com/foaf/0.1/"),
        Prefix("skos", "http://www.w3.org/2004/02/skos/core#")
    ]
)

@Rdf(iri = "dcat:Catalog")
interface Catalog {
    @Rdf(iri = "dcterms:title")
    val title: String
    
    @Rdf(iri = "dcterms:description")
    val description: String
    
    @Rdf(iri = "dcterms:publisher")
    val publisher: Agent
    
    @Rdf(iri = "dcat:dataset")
    val dataset: List<Dataset>
    
    @Rdf(iri = "skos:altLabel")
    val alternativeLabels: List<String>
}

@Rdf(iri = "dcat:Dataset")
interface Dataset {
    @Rdf(iri = "dcterms:title")
    val title: String
    
    @Rdf(iri = "dcterms:description")
    val description: String
    
    @Rdf(iri = "dcat:distribution")
    val distribution: List<Distribution>
    
    @Rdf(iri = "dcterms:keyword")
    val keywords: List<String>
}

@Rdf(iri = "dcat:Distribution")
interface Distribution {
    @Rdf(iri = "dcterms:title")
    val title: String
    
    @Rdf(iri = "dcat:downloadURL")
    val downloadUrl: String
    
    @Rdf(iri = "dcat:mediaType")
    val mediaType: String
    
    @Rdf(iri = "dcterms:format")
    val format: String
}

@Rdf(iri = "foaf:Agent")
interface Agent {
    @Rdf(iri = "foaf:name")
    val name: String
    
    @Rdf(iri = "foaf:homepage")
    val homepage: String
}
```

### Schema.org Example

```kotlin
@file:Rdf(
    prefixes = [
        Prefix("schema", "http://schema.org/"),
        Prefix("geo", "http://www.w3.org/2003/01/geo/wgs84_pos#")
    ]
)

@Rdf(iri = "schema:Place")
interface Place {
    @Rdf(iri = "schema:name")
    val name: String
    
    @Rdf(iri = "schema:address")
    val address: String
    
    @Rdf(iri = "geo:lat")
    val latitude: Double
    
    @Rdf(iri = "geo:long")
    val longitude: Double
}

@Rdf(iri = "schema:Person")
interface Person {
    @Rdf(iri = "schema:name")
    val name: String
    
    @Rdf(iri = "schema:email")
    val email: String
    
    @Rdf(iri = "schema:worksFor")
    val worksFor: Organization
}

@Rdf(iri = "schema:Organization")
interface Organization {
    @Rdf(iri = "schema:name")
    val name: String
    
    @Rdf(iri = "schema:address")
    val address: String
}
```

### Custom Vocabulary Example

```kotlin
@file:Rdf(
    prefixes = [
        Prefix("ex", "http://example.org/vocab#"),
        Prefix("time", "http://www.w3.org/2006/time#"),
        Prefix("qudt", "http://qudt.org/schema/qudt/")
    ]
)

@Rdf(iri = "ex:Event")
interface Event {
    @Rdf(iri = "ex:name")
    val name: String
    
    @Rdf(iri = "ex:description")
    val description: String
    
    @Rdf(iri = "time:hasBeginning")
    val startTime: String
    
    @Rdf(iri = "time:hasEnd")
    val endTime: String
    
    @Rdf(iri = "ex:location")
    val location: Place
}

@Rdf(iri = "ex:Place")
interface Place {
    @Rdf(iri = "ex:name")
    val name: String
    
    @Rdf(iri = "qudt:latitude")
    val latitude: Double
    
    @Rdf(iri = "qudt:longitude")
    val longitude: Double
}
```

## Troubleshooting

### Common Issues

#### 1. Unknown Prefix Error

**Error**: `Unknown prefix: dcat in QName: dcat:Catalog`

**Solution**: Declare the prefix with **`@file:Rdf(prefixes = …)`** on the Kotlin file, or with **`prefixes = […]`** on the domain **`@Rdf`** next to **`iri`**:

```kotlin
// ✅ Correct — file-level prefixes
@file:Rdf(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#")
    ]
)

package com.example

import com.geoknoesis.kastor.gen.annotations.Prefix
import com.geoknoesis.kastor.gen.annotations.Rdf

@Rdf(iri = "dcat:Catalog")
interface Catalog

// ❌ Incorrect — missing prefix declaration
@Rdf(iri = "dcat:Catalog")
interface BrokenCatalog
```

#### 2. Case Sensitivity Issues

**Error**: `Unknown prefix: DCAT in QName: DCAT:Catalog`

**Solution**: Prefix names are case-sensitive. Use consistent casing:

```kotlin
// ✅ Correct
@file:Rdf(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#")
    ]
)

package com.example

import com.geoknoesis.kastor.gen.annotations.Prefix
import com.geoknoesis.kastor.gen.annotations.Rdf

@Rdf(iri = "dcat:Catalog")
interface Catalog

// ❌ Incorrect - case mismatch
@Rdf(iri = "DCAT:Catalog")
interface BrokenCatalog
```

#### 3. Missing Namespace URI

**Error**: `Prefix annotation missing 'namespace' property`

**Solution**: Ensure both `name` and `namespace` are provided:

```kotlin
// ✅ Correct
@file:Rdf(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#")
    ]
)

// ❌ Incorrect - missing namespace
@file:Rdf(
    prefixes = [
        Prefix("dcat", "") // Empty namespace
    ]
)
```

#### 4. Invalid QName Format

**Error**: `Invalid QName format: :Catalog`

**Solution**: QNames must have both prefix and local name:

```kotlin
// ✅ Correct
@Rdf(iri = "dcat:Catalog")

// ❌ Incorrect - missing prefix
@Rdf(iri = ":Catalog")

// ❌ Incorrect - missing local name
@Rdf(iri = "dcat:")
```

### Debugging Tips

#### 1. Verify Prefix Mappings

Check that your prefix mappings are correct:

```kotlin
@file:Rdf(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#"), // Should end with #
        Prefix("dcterms", "http://purl.org/dc/terms/") // Should end with /
    ]
)
```

#### 2. Use Full IRIs for Testing

If QNames aren't working, try using full IRIs temporarily:

```kotlin
// Test with full IRI
@Rdf(iri = "http://www.w3.org/ns/dcat#Catalog")

// Then convert to QName
@Rdf(iri = "dcat:Catalog")
```

#### 3. Check Annotation Processing

Ensure KSP is processing your annotations:

```kotlin
// Add logging to see if annotations are processed
@file:Rdf(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#")
    ]
)
@Rdf(iri = "dcat:Catalog")
interface Catalog {
    // Check build logs for annotation processing messages
}
```

## Conclusion

Prefix mappings and QNames make your Kastor Gen annotations more readable and maintainable. By using well-known prefixes and following best practices, you can create clean, consistent domain interfaces that are easy to understand and modify.

For more information, see:
- [Domain Modeling](domain-modeling.md)
- [Core Concepts](core-concepts.md)
- [Best Practices](../best-practices.md)
- [FAQ](../faq.md)



