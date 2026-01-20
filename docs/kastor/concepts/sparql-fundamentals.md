# SPARQL Fundamentals

This document provides a comprehensive overview of SPARQL (SPARQL Protocol and RDF Query Language) and how to use it effectively with the Kastor QueryTerms API. Kastor supports **SPARQL 1.2**, including all the latest features such as RDF-star, enhanced functions, and improved syntax.

## Table of Contents

1. [Introduction](#introduction)
2. [Basic Concepts](#basic-concepts)
   - [SPARQL 1.2 Version Declaration](#sparql-12-version-declaration)
   - [Prefix Declarations](#prefix-declarations)
3. [Query Structure](#query-structure)
4. [Variables](#variables)
5. [Triple Patterns](#triple-patterns)
6. [Graph Patterns](#graph-patterns)
7. [Filters](#filters)
8. [Built-in Functions](#built-in-functions)
   - [SPARQL 1.2 Enhanced Functions](#sparql-12-enhanced-functions)
9. [Aggregation](#aggregation)
10. [SubSelect](#subselect)
11. [RDF-star (SPARQL 1.2)](#rdf-star-sparql-12)
12. [Best Practices](#best-practices)

## Introduction

SPARQL is the standard query language for RDF (Resource Description Framework) data. It allows you to retrieve and manipulate data stored in RDF format using a SQL-like syntax.

### Key Features of SPARQL 1.2

- **Pattern Matching**: Find data that matches specific triple patterns
- **Filtering**: Restrict results based on conditions
- **Aggregation**: Group and summarize data
- **Joins**: Combine data from multiple sources
- **Optional Matching**: Include data that may not exist
- **Union**: Match multiple alternative patterns
- **Subqueries**: Nest queries within queries
- **RDF-star**: Work with quoted triples and triple terms
- **Enhanced Functions**: New built-in functions for string manipulation, date/time handling, and RDF-star operations
- **Literal Base Direction**: Support for text direction in literals
- **Version Declaration**: Explicit SPARQL version specification

## Basic Concepts

### SPARQL 1.2 Version Declaration

SPARQL 1.2 introduces explicit version declarations to specify which version of SPARQL to use:

```kotlin
val query = select("name", "age") {
    version("1.2")  // Explicit SPARQL 1.2 declaration
    prefix("foaf", "http://xmlns.com/foaf/0.1/")
    where {
        pattern(var_("person"), iri("foaf:name"), var_("name"))
        pattern(var_("person"), iri("foaf:age"), var_("age"))
    }
}
```

This generates:
```sparql
VERSION 1.2

PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?name ?age
WHERE {
  ?person foaf:name ?name .
  ?person foaf:age ?age .
}
```

### Prefix Declarations

SPARQL supports prefix declarations to make queries more readable by using shortened names instead of full IRIs:

```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?name ?type
WHERE {
  ?person foaf:name ?name .
  ?person rdf:type ?type .
}
```

In Kastor, you can add prefixes using:

```kotlin
val query = select("name", "type") {
    addCommonPrefixes("foaf", "rdf", "rdfs")  // Common vocabularies
    prefix("ex", "http://example.org/")       // Custom prefix
    where {
        `var`("person") has iri("foaf:name") with `var`("name")
        `var`("person") has iri("rdf:type") with `var`("type")
    }
}
```

### RDF Triples

RDF data consists of triples: (Subject, Predicate, Object)

```kotlin
// Using Kastor QueryTerms API
val triple = TriplePattern(
    subject = iri("http://example.org/person/1"),
    predicate = iri("http://example.org/name"),
    obj = string("John Doe")
)
```

### SPARQL Variables

Variables are placeholders that can be bound to values during query execution.

```kotlin
// Create variables
val personVar = `var`("person")
val nameVar = `var`("name")
val ageVar = `var`("age")

// Use in patterns
personVar has namePred with nameVar
personVar has agePred with ageVar
```

## Query Structure

### SELECT Query

The most common type of SPARQL query that returns variable bindings.

```kotlin
val query = select("name", "age") {
    where {
        personVar has namePred with nameVar
        personVar has agePred with ageVar
    }
}
```

### Query Components

1. **SELECT**: Variables to return
2. **WHERE**: Graph patterns to match
3. **FILTER**: Conditions to apply
4. **ORDER BY**: Sort results
5. **LIMIT/OFFSET**: Pagination
6. **GROUP BY**: Group results
7. **HAVING**: Filter groups

## Variables

### Creating Variables

```kotlin
// Short form (recommended)
val nameVar = `var`("name")

// Long form (backward compatibility)
val ageVar = sparqlVar("age")
```

### Variable Naming Rules

- Must start with a letter or digit
- Cannot be blank
- Case-sensitive
- No special characters (except underscore)

## Triple Patterns

### Basic Triple Patterns

```kotlin
// Direct construction
val pattern = TriplePattern(
    subject = personVar,
    predicate = namePred,
    obj = nameVar
)

// DSL syntax (recommended)
personVar has namePred with nameVar
```

### Pattern Types

1. **Variable-Variable-Variable**: `?s ?p ?o`
2. **IRI-Variable-Variable**: `<http://example.org/name> ?p ?o`
3. **Variable-IRI-Variable**: `?s <http://example.org/name> ?o`
4. **Variable-Variable-Literal**: `?s ?p "John"`

## Graph Patterns

### Basic Patterns

```kotlin
// Simple pattern
personVar has namePred with nameVar

// Multiple patterns
personVar has namePred with nameVar
personVar has agePred with ageVar
personVar has emailPred with emailVar
```

### Complex Patterns

#### OPTIONAL Patterns

Match patterns if possible, but don't fail if they don't match.

```kotlin
optional {
    personVar has emailPred with `var`("email")
}
```

#### UNION Patterns

Match either of two patterns.

```kotlin
union {
    personVar has emailPred with `var`("contact")
}
union {
    personVar has iri("http://example.org/phone") with `var`("contact")
}
```

#### MINUS Patterns

Exclude solutions that match a pattern.

```kotlin
minus {
    personVar has iri("http://example.org/deleted") with string("true")
}
```

#### VALUES Patterns

Specify a set of values for variables.

```kotlin
values(nameVar, string("John"), string("Jane"), string("Bob"))
```

#### GRAPH Patterns

Restrict patterns to a specific named graph.

```kotlin
graph(`var`("graph")) {
    personVar has namePred with nameVar
}
```

## Filters

### Comparison Operators

```kotlin
// Short operators
filter(ageVar gt 18)
filter(ageVar lte 65)
filter(nameVar eq "John")
filter(nameVar ne string("Jane"))

// Logical operators
filter(ageVar gt 18 and ageVar lt 65)
filter(nameVar eq "John" or nameVar eq "Jane")
filter(not(ageVar lt 18))
```

### Built-in Functions

```kotlin
// String functions
filter(nameVar like "John*")
filter(regex(nameVar, "John.*"))
filter(strlen(nameVar) gt 5)

// Type checking
filter(isIRI(personVar))
filter(isLiteral(nameVar))
filter(bound(emailVar))
```

## Built-in Functions

### String Functions

```kotlin
// In BIND expressions
bind(`var`("upperName"), ucase(nameVar))
bind(`var`("nameLength"), strlen(nameVar))
bind(`var`("fullName"), concat(nameVar, string(" "), `var`("lastName")))
```

### Numeric Functions

```kotlin
bind(`var`("absAge"), abs(ageVar))
bind(`var`("roundedAge"), round(ageVar))
bind(`var`("ceilingAge"), ceil(ageVar))
```

### Date/Time Functions

```kotlin
bind(`var`("birthYear"), year(birthDateVar))
bind(`var`("birthMonth"), month(birthDateVar))
bind(`var`("birthDay"), day(birthDateVar))
```

### SPARQL 1.2 Enhanced Functions

SPARQL 1.2 introduces many new built-in functions:

#### Enhanced String Functions
```kotlin
val query = select {
    version("1.2")
    expression(replaceAll(var_("text"), "old", "new"), "replaced")
    expression(encodeForUri(var_("text")), "encoded")
    expression(decodeForUri(var_("text")), "decoded")
    expression(contains(var_("text"), "substring"), "hasSubstring")
    expression(startsWith(var_("text"), "prefix"), "hasPrefix")
    expression(endsWith(var_("text"), "suffix"), "hasSuffix")
    where {
        pattern(var_("s"), iri("ex:text"), var_("text"))
    }
}
```

#### Enhanced Numeric Functions
```kotlin
val query = select {
    version("1.2")
    expression(rand(), "randomValue")
    expression(random(), "randomValue2")
    expression(now(), "currentTime")
    expression(timezone(), "timezone")
    where {
        pattern(var_("s"), iri("ex:value"), var_("value"))
    }
}
```

#### Literal Base Direction Functions
```kotlin
val query = select(SparqlSelectQuery("text"))) {
    version("1.2")
    where {
        pattern(var_("s"), iri("ex:text"), var_("text"))
        filter(hasLang(var_("text"), "en"))
        filter(hasLangdir(var_("text"), "rtl"))
    }
}
```

#### RDF-star Functions
```kotlin
val query = select {
    version("1.2")
    expression(triple(var_("s"), var_("p"), var_("o")), "tripleTerm")
    expression(subject(var_("triple")), "subj")
    expression(predicate(var_("triple")), "pred")
    expression(`object`(var_("triple")), "obj")
    where {
        pattern(var_("s"), var_("p"), var_("o"))
        filter(isTriple(var_("triple")))
    }
}
```

## Aggregation

### Aggregate Functions

```kotlin
// In SELECT
select("avgAge", "maxAge", "count") {
    where {
        personVar has agePred with ageVar
    }
    groupBy()
}

// In HAVING
having {
    filter(count(ageVar) gt 10)
}
```

### Available Aggregates

- `count()`: Count results
- `countDistinct()`: Count unique values
- `sum()`: Sum of values
- `avg()`: Average of values
- `min()`: Minimum value
- `max()`: Maximum value
- `groupConcat()`: Concatenate values

## SubSelect

### Nested Queries

SubSelect allows using a SELECT query as part of a larger query.

```kotlin
subSelect {
    select(SparqlSelectQuery("avgAge"))) {
        where {
            personVar has agePred with `var`("age")
        }
        groupBy()
    }
}
```

### Use Cases

1. **Complex Aggregations**: Calculate averages within groups
2. **Data Validation**: Check for specific conditions
3. **Performance Optimization**: Pre-filter large datasets
4. **Complex Joins**: Combine data from different sources

## RDF-star (SPARQL 1.2)

SPARQL 1.2 introduces comprehensive support for RDF-star, allowing you to work with quoted triples and triple terms.

### Quoted Triples

RDF-star allows using triples as subjects or objects:

```kotlin
val query = select(SparqlSelectQuery("confidence"))) {
    version("1.2")
    where {
        // Quoted triple pattern
        quotedTriple(var_("person"), iri("foaf:name"), var_("name"))
        
        // Use quoted triple in patterns
        pattern(quotedTriple(var_("person"), iri("foaf:name"), var_("name")), 
                iri("ex:confidence"), var_("confidence"))
    }
}
```

This generates:
```sparql
VERSION 1.2

SELECT ?confidence
WHERE {
  << ?person foaf:name ?name >> ex:confidence ?confidence .
}
```

### SPARQL 1.2 RDF-star Functions

SPARQL 1.2 introduces new functions for working with triple terms:

```kotlin
val query = select {
    version("1.2")
    expression(triple(var_("s"), var_("p"), var_("o")), "tripleTerm")
    expression(subject(var_("triple")), "subj")
    expression(predicate(var_("triple")), "pred")
    expression(`object`(var_("triple")), "obj")
    where {
        pattern(var_("s"), var_("p"), var_("o"))
        pattern(var_("triple"), iri("rdf:type"), iri("rdf:Statement"))
        filter(isTriple(var_("triple")))
    }
}
```

### DSL Syntax

```kotlin
// Using DSL for quoted triples
var_("statement") quoted iri("http://example.org/confidence") with var_("confidence")
```

### Use Cases

1. **Statement Metadata**: Add confidence scores to statements
2. **Provenance**: Track the source of information
3. **Temporal Information**: Add timestamps to statements
4. **Annotations**: Add comments or notes to triples

## Best Practices

### Performance

1. **Use Specific Patterns**: Avoid `?s ?p ?o` when possible
2. **Limit Results**: Use LIMIT and OFFSET for pagination
3. **Optimize Filters**: Place filters early in the query
4. **Use Indexes**: Ensure proper indexing on frequently queried properties

### Readability

1. **Meaningful Variable Names**: Use descriptive names
2. **Consistent Formatting**: Follow a consistent style
3. **Comments**: Add comments for complex queries
4. **Modular Design**: Break complex queries into smaller parts

### Error Handling

1. **Validate Input**: Check for null or invalid values
2. **Handle Missing Data**: Use OPTIONAL for non-essential data
3. **Test Queries**: Verify queries work with your data
4. **Monitor Performance**: Track query execution times

## Complete Example

```kotlin
val complexQuery = select("name", "email", "age", "confidence") {
    where {
        // Basic patterns
        personVar has namePred with nameVar
        personVar has agePred with ageVar
        
        // Optional email
        optional {
            personVar has emailPred with `var`("email")
            filter(`var`("email") ne string(""))
        }
        
        // RDF-star confidence
        `var`("statement") quoted iri("http://example.org/confidence") with `var`("confidence")
        `var`("statement") quoted iri("http://example.org/subject") with personVar
        
        // Values constraint
        values(nameVar, string("John"), string("Jane"))
        
        // Filters
        filter(ageVar gt 18)
        filter(`var`("confidence") gt 0.8)
    }
    orderBy(ageVar, OrderDirection.DESC)
    limit(10)
}
```

This comprehensive SPARQL API provides all the tools needed to work with RDF data effectively and efficiently.




