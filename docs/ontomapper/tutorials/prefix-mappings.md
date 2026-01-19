# Prefix Mappings and QNames

This guide explains how to use prefix mappings and QNames (qualified names) in OntoMapper annotations to make your code more readable and maintainable.

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
@RdfClass(iri = "http://www.w3.org/ns/dcat#Catalog")
interface Catalog {
    @get:RdfProperty(iri = "http://purl.org/dc/terms/title")
    val title: String
}
```

You can use QNames with prefix mappings:

```kotlin
@PrefixMapping(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#"),
        Prefix("dcterms", "http://purl.org/dc/terms/")
    ]
)
@RdfClass(iri = "dcat:Catalog")
interface Catalog {
    @get:RdfProperty(iri = "dcterms:title")
    val title: String
}
```

## Basic Usage

### Declaring Prefix Mappings

Use the `@PrefixMapping` annotation to declare prefix mappings:

```kotlin
@PrefixMapping(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#"),
        Prefix("dcterms", "http://purl.org/dc/terms/"),
        Prefix("foaf", "http://xmlns.com/foaf/0.1/")
    ]
)
```

### Using QNames in Annotations

Once declared, you can use QNames in `@RdfClass` and `@RdfProperty` annotations:

```kotlin
@PrefixMapping(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#"),
        Prefix("dcterms", "http://purl.org/dc/terms/")
    ]
)
@RdfClass(iri = "dcat:Catalog")
interface Catalog {
    @get:RdfProperty(iri = "dcterms:title")
    val title: String
    
    @get:RdfProperty(iri = "dcterms:description")
    val description: String
    
    @get:RdfProperty(iri = "dcat:dataset")
    val dataset: List<Dataset>
}
```

### File-Level Prefix Mappings

You can declare prefix mappings at the file level for all classes in the file:

```kotlin
@file:PrefixMapping(
    prefixes = [
        Prefix("vcard", "http://www.w3.org/2006/vcard/ns#"),
        Prefix("org", "http://www.w3.org/ns/org#")
    ]
)

@RdfClass(iri = "vcard:Individual")
interface Person {
    @get:RdfProperty(iri = "vcard:fn")
    val fullName: String
}

@RdfClass(iri = "org:Organization")
interface Organization {
    @get:RdfProperty(iri = "vcard:fn")
    val name: String
}
```

## Advanced Features

### Mixed Usage

You can mix QNames and full IRIs in the same interface:

```kotlin
@PrefixMapping(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#"),
        Prefix("dcterms", "http://purl.org/dc/terms/")
    ]
)
@RdfClass(iri = "http://www.w3.org/ns/dcat#Catalog") // Full IRI
interface Catalog {
    @get:RdfProperty(iri = "dcterms:title") // QName
    val title: String
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/description") // Full IRI
    val description: String
    
    @get:RdfProperty(iri = "dcat:dataset") // QName
    val dataset: List<Dataset>
}
```

### Custom Prefixes

Define your own prefixes for custom vocabularies:

```kotlin
@PrefixMapping(
    prefixes = [
        Prefix("ex", "http://example.org/vocab#"),
        Prefix("schema", "http://schema.org/"),
        Prefix("geo", "http://www.w3.org/2003/01/geo/wgs84_pos#")
    ]
)
@RdfClass(iri = "ex:Location")
interface Location {
    @get:RdfProperty(iri = "ex:name")
    val name: String
    
    @get:RdfProperty(iri = "schema:address")
    val address: String
    
    @get:RdfProperty(iri = "geo:lat")
    val latitude: Double
    
    @get:RdfProperty(iri = "geo:long")
    val longitude: Double
}
```

### Multiple Prefix Mappings

You can declare multiple prefix mappings for different contexts:

```kotlin
// For DCAT vocabulary
@PrefixMapping(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#"),
        Prefix("dcterms", "http://purl.org/dc/terms/")
    ]
)
@RdfClass(iri = "dcat:Catalog")
interface Catalog { /* ... */ }

// For FOAF vocabulary
@PrefixMapping(
    prefixes = [
        Prefix("foaf", "http://xmlns.com/foaf/0.1/")
    ]
)
@RdfClass(iri = "foaf:Person")
interface Person { /* ... */ }
```

## Best Practices

### 1. Use Standard Prefixes

Use well-known prefixes for common vocabularies:

```kotlin
// ✅ Good: Standard prefixes
@PrefixMapping(
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
@PrefixMapping(
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
@PrefixMapping(
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
@file:PrefixMapping(
    prefixes = [
        Prefix("vcard", "http://www.w3.org/2006/vcard/ns#"),
        Prefix("org", "http://www.w3.org/ns/org#")
    ]
)

// All classes in this file can use vcard: and org: prefixes
@RdfClass(iri = "vcard:Individual")
interface Person { /* ... */ }

@RdfClass(iri = "org:Organization")
interface Organization { /* ... */ }
```

### 4. Validate Prefix Mappings

Ensure your prefix mappings are correct:

```kotlin
// ✅ Good: Verified namespace URIs
@PrefixMapping(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#"), // Verified
        Prefix("dcterms", "http://purl.org/dc/terms/") // Verified
    ]
)

// ❌ Avoid: Unverified or incorrect URIs
@PrefixMapping(
    prefixes = [
        Prefix("dcat", "http://example.org/dcat#"), // Wrong namespace
        Prefix("dcterms", "http://purl.org/dc/terms") // Missing trailing slash
    ]
)
```

## Examples

### Complete DCAT Example

```kotlin
@PrefixMapping(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#"),
        Prefix("dcterms", "http://purl.org/dc/terms/"),
        Prefix("foaf", "http://xmlns.com/foaf/0.1/"),
        Prefix("skos", "http://www.w3.org/2004/02/skos/core#")
    ]
)

@RdfClass(iri = "dcat:Catalog")
interface Catalog {
    @get:RdfProperty(iri = "dcterms:title")
    val title: String
    
    @get:RdfProperty(iri = "dcterms:description")
    val description: String
    
    @get:RdfProperty(iri = "dcterms:publisher")
    val publisher: Agent
    
    @get:RdfProperty(iri = "dcat:dataset")
    val dataset: List<Dataset>
    
    @get:RdfProperty(iri = "skos:altLabel")
    val alternativeLabels: List<String>
}

@RdfClass(iri = "dcat:Dataset")
interface Dataset {
    @get:RdfProperty(iri = "dcterms:title")
    val title: String
    
    @get:RdfProperty(iri = "dcterms:description")
    val description: String
    
    @get:RdfProperty(iri = "dcat:distribution")
    val distribution: List<Distribution>
    
    @get:RdfProperty(iri = "dcterms:keyword")
    val keywords: List<String>
}

@RdfClass(iri = "dcat:Distribution")
interface Distribution {
    @get:RdfProperty(iri = "dcterms:title")
    val title: String
    
    @get:RdfProperty(iri = "dcat:downloadURL")
    val downloadUrl: String
    
    @get:RdfProperty(iri = "dcat:mediaType")
    val mediaType: String
    
    @get:RdfProperty(iri = "dcterms:format")
    val format: String
}

@RdfClass(iri = "foaf:Agent")
interface Agent {
    @get:RdfProperty(iri = "foaf:name")
    val name: String
    
    @get:RdfProperty(iri = "foaf:homepage")
    val homepage: String
}
```

### Schema.org Example

```kotlin
@PrefixMapping(
    prefixes = [
        Prefix("schema", "http://schema.org/"),
        Prefix("geo", "http://www.w3.org/2003/01/geo/wgs84_pos#")
    ]
)

@RdfClass(iri = "schema:Place")
interface Place {
    @get:RdfProperty(iri = "schema:name")
    val name: String
    
    @get:RdfProperty(iri = "schema:address")
    val address: String
    
    @get:RdfProperty(iri = "geo:lat")
    val latitude: Double
    
    @get:RdfProperty(iri = "geo:long")
    val longitude: Double
}

@RdfClass(iri = "schema:Person")
interface Person {
    @get:RdfProperty(iri = "schema:name")
    val name: String
    
    @get:RdfProperty(iri = "schema:email")
    val email: String
    
    @get:RdfProperty(iri = "schema:worksFor")
    val worksFor: Organization
}

@RdfClass(iri = "schema:Organization")
interface Organization {
    @get:RdfProperty(iri = "schema:name")
    val name: String
    
    @get:RdfProperty(iri = "schema:address")
    val address: String
}
```

### Custom Vocabulary Example

```kotlin
@PrefixMapping(
    prefixes = [
        Prefix("ex", "http://example.org/vocab#"),
        Prefix("time", "http://www.w3.org/2006/time#"),
        Prefix("qudt", "http://qudt.org/schema/qudt/")
    ]
)

@RdfClass(iri = "ex:Event")
interface Event {
    @get:RdfProperty(iri = "ex:name")
    val name: String
    
    @get:RdfProperty(iri = "ex:description")
    val description: String
    
    @get:RdfProperty(iri = "time:hasBeginning")
    val startTime: String
    
    @get:RdfProperty(iri = "time:hasEnd")
    val endTime: String
    
    @get:RdfProperty(iri = "ex:location")
    val location: Place
}

@RdfClass(iri = "ex:Place")
interface Place {
    @get:RdfProperty(iri = "ex:name")
    val name: String
    
    @get:RdfProperty(iri = "qudt:latitude")
    val latitude: Double
    
    @get:RdfProperty(iri = "qudt:longitude")
    val longitude: Double
}
```

## Troubleshooting

### Common Issues

#### 1. Unknown Prefix Error

**Error**: `Unknown prefix: dcat in QName: dcat:Catalog`

**Solution**: Ensure the prefix is declared in a `@PrefixMapping` annotation:

```kotlin
// ✅ Correct
@PrefixMapping(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#")
    ]
)
@RdfClass(iri = "dcat:Catalog")

// ❌ Incorrect - missing prefix declaration
@RdfClass(iri = "dcat:Catalog")
```

#### 2. Case Sensitivity Issues

**Error**: `Unknown prefix: DCAT in QName: DCAT:Catalog`

**Solution**: Prefix names are case-sensitive. Use consistent casing:

```kotlin
// ✅ Correct
@PrefixMapping(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#")
    ]
)
@RdfClass(iri = "dcat:Catalog")

// ❌ Incorrect - case mismatch
@RdfClass(iri = "DCAT:Catalog")
```

#### 3. Missing Namespace URI

**Error**: `Prefix annotation missing 'namespace' property`

**Solution**: Ensure both `name` and `namespace` are provided:

```kotlin
// ✅ Correct
@PrefixMapping(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#")
    ]
)

// ❌ Incorrect - missing namespace
@PrefixMapping(
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
@RdfClass(iri = "dcat:Catalog")

// ❌ Incorrect - missing prefix
@RdfClass(iri = ":Catalog")

// ❌ Incorrect - missing local name
@RdfClass(iri = "dcat:")
```

### Debugging Tips

#### 1. Verify Prefix Mappings

Check that your prefix mappings are correct:

```kotlin
@PrefixMapping(
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
@RdfClass(iri = "http://www.w3.org/ns/dcat#Catalog")

// Then convert to QName
@RdfClass(iri = "dcat:Catalog")
```

#### 3. Check Annotation Processing

Ensure KSP is processing your annotations:

```kotlin
// Add logging to see if annotations are processed
@PrefixMapping(
    prefixes = [
        Prefix("dcat", "http://www.w3.org/ns/dcat#")
    ]
)
@RdfClass(iri = "dcat:Catalog")
interface Catalog {
    // Check build logs for annotation processing messages
}
```

## Conclusion

Prefix mappings and QNames make your OntoMapper annotations more readable and maintainable. By using well-known prefixes and following best practices, you can create clean, consistent domain interfaces that are easy to understand and modify.

For more information, see:
- [Domain Modeling](domain-modeling.md)
- [Core Concepts](core-concepts.md)
- [Best Practices](../best-practices.md)
- [FAQ](../faq.md)



