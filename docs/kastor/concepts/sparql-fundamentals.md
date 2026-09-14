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
import com.geoknoesis.kastor.rdf.vocab.FOAF

val query = select("name", "age") {
    version("1.2")  // Explicit SPARQL 1.2 declaration
    prefix("foaf", FOAF.namespace)
    where {
        triple(var_("person"), FOAF.name, var_("name"))
        triple(var_("person"), FOAF.age, var_("age"))
    }
}
```

This generates:
```sparql
VERSION "1.2"
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?name ?age
WHERE {
  ?person <http://xmlns.com/foaf/0.1/name> ?name .
  ?person <http://xmlns.com/foaf/0.1/age> ?age .
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
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF

val query = select("name", "type") {
    addCommonPrefixes("foaf", "rdf", "rdfs")  // Common vocabularies
    prefix("ex", "http://example.org/")       // Custom prefix
    where {
        triple(`var`("person"), FOAF.name, `var`("name"))
        triple(`var`("person"), RDF.type, `var`("type"))
    }
}
```

The renderer emits the `PREFIX` declarations (in the order they were added) but always writes IRIs in full, for example `?person <http://xmlns.com/foaf/0.1/name> ?name .`. The generated query is therefore equivalent to the hand-written one above, not textually identical.

### RDF Triples

RDF data consists of triples: (Subject, Predicate, Object)

```kotlin
// Using Kastor QueryTerms API
val triple = TriplePatternAst(
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
triple(personVar, namePred, nameVar)
triple(personVar, agePred, ageVar)
```

## Query Structure

### SELECT Query

The most common type of SPARQL query that returns variable bindings.

```kotlin
val query = select("name", "age") {
    where {
        triple(personVar, namePred, nameVar)
        triple(personVar, agePred, ageVar)
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
val pattern = TriplePatternAst(
    subject = personVar,
    predicate = namePred,
    obj = nameVar
)

// DSL syntax (recommended)
triple(personVar, namePred, nameVar)
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
triple(personVar, namePred, nameVar)

// Multiple patterns
triple(personVar, namePred, nameVar)
triple(personVar, agePred, ageVar)
triple(personVar, emailPred, emailVar)
```

### Complex Patterns

#### OPTIONAL Patterns

Match patterns if possible, but don't fail if they don't match.

```kotlin
optional {
    triple(personVar, emailPred, `var`("email"))
}
```

#### UNION Patterns

Match any of two or more alternative patterns. Give every branch explicitly:

```kotlin
union(
    { triple(personVar, emailPred, `var`("contact")) },
    { triple(personVar, iri("http://example.org/phone"), `var`("contact")) },
)

// Equivalent, convenient for multi-pattern branches (at least two branch { } blocks)
unionOf {
    branch { triple(personVar, emailPred, `var`("contact")) }
    branch { triple(personVar, iri("http://example.org/phone"), `var`("contact")) }
}
```

Both render `{ … } UNION { … }` at the position where they are called. The single-block `union { }` is deprecated (level WARNING) because it turns only the one pattern added immediately before it into the left branch, which silently detaches earlier filters or triples.

#### MINUS Patterns

Exclude solutions that match a pattern.

```kotlin
triple(personVar, namePred, nameVar)
minus {
    triple(personVar, iri("http://example.org/deleted"), string("true"))
}
```

`minus { }` renders a bare `MINUS { … }` that, as in SPARQL, subtracts from everything before it in the same group. A MINUS with nothing before it removes nothing. In the AST, `MinusPatternAst(left, right)` with a non-empty `left` renders `{ left MINUS { right } }`, so only `left` is affected.

#### VALUES Patterns

Specify a set of values for variables.

```kotlin
values(nameVar, string("John"), string("Jane"), string("Bob"))
```

#### GRAPH Patterns

Restrict patterns to a specific named graph.

```kotlin
graph(`var`("graph")) {
    triple(personVar, namePred, nameVar)
}
```

## Filters

### Comparison Operators

```kotlin
// Short operators
filter(ageVar gt 18)
filter(ageVar lte 65)
filter(nameVar eq "John")
filter(nameVar ne "Jane")

// Logical operators
filter((ageVar gt 18) and (ageVar lt 65))
filter((nameVar eq "John") or (nameVar eq "Jane"))
filter(not(ageVar lt 18))
```

### Built-in Functions

```kotlin
// String functions
filter(regex(nameVar, "John.*"))
filter(strlen(nameVar.expr()) gt TermExpressionAst(5.toLiteral()))

// Type checking
filter(isIRI(personVar.expr()))
filter(isLiteral(nameVar.expr()))
filter(bound(emailVar))
```

## Built-in Functions

### String Functions

```kotlin
// In BIND expressions
bind(`var`("upperName"), ucase(nameVar.expr()))
bind(`var`("nameLength"), strlen(nameVar.expr()))
bind(`var`("fullName"), concat(nameVar.expr(), TermExpressionAst(string(" ")), `var`("lastName").expr()))
```

### Numeric Functions

The DSL has no helpers for `ABS`, `ROUND`, `CEIL` or `FLOOR`; write those in a SPARQL string, or combine expressions with the arithmetic operators `plus`, `minus`, `times` and `div`:

```kotlin
bind(`var`("ageNextYear"), ageVar.expr() plus TermExpressionAst(1.toLiteral()))
```

### Date/Time Functions

The DSL has no `YEAR`/`MONTH`/`DAY` helpers. It provides `now()`, `timezone(expr)`, `tz(expr)` and the XSD casts `dateTime(expr)`, `date(expr)` and `time(expr)`:

```kotlin
bind(`var`("birthDay"), date(birthDateVar.expr()))
bind(`var`("birthTz"), tz(birthDateVar.expr()))
```

### SPARQL 1.2 Enhanced Functions

SPARQL 1.2 introduces many new built-in functions:

#### Enhanced String Functions
```kotlin
val query = select {
    version("1.2")
    expression(replace(var_("text").expr(), "old", "new"), "replaced")   // REPLACE
    expression(encodeForUri(var_("text").expr()), "encoded")             // ENCODE_FOR_URI
    expression(contains(var_("text").expr(), "substring"), "hasSubstring")
    expression(startsWith(var_("text").expr(), "prefix"), "hasPrefix")   // STRSTARTS
    expression(endsWith(var_("text").expr(), "suffix"), "hasSuffix")     // STRENDS
    where {
        triple(var_("s"), iri("ex:text"), var_("text"))
    }
}
```

`replaceAll(...)` is a deprecated alias that also renders `REPLACE` (SPARQL `REPLACE` already replaces every match). `decodeForUri(...)` is deprecated with level ERROR: SPARQL has no `DECODE_FOR_URI` function.

#### Enhanced Numeric Functions
```kotlin
val query = select {
    version("1.2")
    expression(rand(), "randomValue")                 // RAND()
    expression(now(), "currentTime")                  // NOW()
    expression(timezone(var_("when").expr()), "timezone")    // TIMEZONE(?when)
    expression(dateTime(var_("text").expr()), "asDateTime")  // xsd:dateTime(?text) cast; also date(...), time(...)
    where {
        triple(var_("s"), iri("ex:value"), var_("value"))
    }
}
```

`random()` and the zero-argument `timezone()` are deprecated with level ERROR because standard SPARQL has no equivalent: use `rand()` and `timezone(expr)`.

#### Literal Base Direction Functions
```kotlin
val query = select("text") {
    version("1.2")
    where {
        triple(var_("s"), iri("ex:text"), var_("text"))
        filter(hasLang(var_("text").expr(), "en"))
        filter(hasLangdir(var_("text").expr(), "rtl"))
    }
}
```

The one-argument forms `hasLang(x)` / `hasLangdir(x)` render the SPARQL 1.2 built-ins `hasLANG(?x)` / `hasLANGDIR(?x)`. The two-argument forms are portable shortcuts: `hasLang(x, "en")` renders `LANGMATCHES(LANG(?x), "en")` and `hasLangdir(x, "rtl")` renders `LANGDIR(?x) = "rtl"` (only `ltr` and `rtl` are accepted).

#### RDF-star Functions
```kotlin
val query = select {
    version("1.2")
    expression(triple(var_("s"), var_("p"), var_("o")), "tripleTerm")
    expression(subject(var_("triple").expr()), "subj")
    expression(predicate(var_("triple").expr()), "pred")
    expression(`object`(var_("triple").expr()), "obj")
    where {
        triple(var_("s"), var_("p"), var_("o"))
        filter(isTriple(var_("triple").expr()))
    }
}
```

## Aggregation

### Aggregate Functions

```kotlin
// In SELECT
select {
    aggregate(AggregateFunction.AVG, ageVar.expr(), "avgAge")
    aggregate(AggregateFunction.MAX, ageVar.expr(), "maxAge")
    expression(countAll(), "count")
    where {
        triple(personVar, agePred, ageVar)
    }
}

// In HAVING (renders HAVING (COUNT(?age) > "10"^^<http://www.w3.org/2001/XMLSchema#integer>))
having {
    filter(count(ageVar.expr()) gt TermExpressionAst(10.toLiteral()))
}
```

### Available Aggregates

- `count()`: Count results
- `countAll()`: `COUNT(*)` (`countAll(distinct = true)` for `COUNT(DISTINCT *)`)
- `count(expr, distinct = true)`: Count unique values
- `sum()`: Sum of values
- `avg()`: Average of values
- `min()`: Minimum value
- `max()`: Maximum value
- `groupConcat()`: Concatenate values; `groupConcat(expr, separator)` renders `GROUP_CONCAT(?x ; SEPARATOR="...")`

## SubSelect

### Nested Queries

SubSelect allows using a SELECT query as part of a larger query.

```kotlin
subSelect {
    expression(avg(`var`("age").expr()), "avgAge")
    where {
        triple(personVar, agePred, `var`("age"))
    }
}
```

### Projection Rules

The renderer rejects projections that SPARQL does not allow (`IllegalArgumentException`):

- With `groupBy(...)` or `having { }`, list the grouped variables and aggregates explicitly. `SELECT *` or an empty projection (which renders `SELECT *`) is rejected.
- `*` (`WildcardSelectItemAst`) cannot be combined with other projection items.

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
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF

val query = select("person", "name") {
    version("1.2")
    prefix("foaf", FOAF.namespace)
    prefix("ex", "http://example.org/")
    where {
        // Reified-triple pattern: matches any reifier of the triple
        quotedTriple(var_("person"), FOAF.name, var_("name"))
    }
}
```

This generates:
```sparql
VERSION "1.2"
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX ex: <http://example.org/>

SELECT ?person ?name
WHERE {
  << ?person <http://xmlns.com/foaf/0.1/name> ?name >> .
}
```

`quotedTriple(...)` renders the SPARQL 1.2 reified triple `<< s p o >>`. A bare triple term `<<( s p o )>>` is not a valid standalone graph pattern and is rejected by the renderer; to bind the reifier, use `ReifierPatternAst`, which renders `?r <http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies> <<( s p o )>> .` (`rdf:reifies` is always written as a full IRI).

### Binding the Reifier

`quotedTriple(...)` matches any reifier. To bind the reifier and read its annotations, build the query from AST nodes (`where { }` has no helper for this):

```kotlin
import com.geoknoesis.kastor.rdf.sparql.*

val person = var_("person")
val name = var_("name")
val statement = var_("statement")
val confidence = var_("confidence")

val query = SelectQueryAst(
    selectItems = listOf(VariableSelectItemAst(person), VariableSelectItemAst(confidence)),
    version = "1.2",
    where = GroupPatternAst(listOf(
        ReifierPatternAst(statement, TripleTermPatternAst(person, FOAF.name, name)),
        TriplePatternAst(statement, iri("http://example.org/confidence"), confidence),
    )),
).toSparqlSelect()
```

This generates (layout may differ slightly):
```sparql
VERSION "1.2"

SELECT ?person ?confidence
WHERE {
  ?statement <http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies> <<( ?person <http://xmlns.com/foaf/0.1/name> ?name )>> .
  ?statement <http://example.org/confidence> ?confidence .
}
```

### Use Cases

1. **Statement Metadata**: Add confidence scores to statements
2. **Provenance**: Track the source of information
3. **Temporal Information**: Add timestamps to statements
4. **Annotations**: Add comments or notes to triples

## Blank Nodes in Queries and Updates

SPARQL scopes a blank node label to one basic graph pattern, so the renderer rejects (`IllegalArgumentException`):

- the same blank node label in two basic graph patterns of one query. FILTER, BIND and VALUES do not end a basic graph pattern; OPTIONAL, UNION, MINUS, GRAPH, SERVICE, sub-groups and sub-selects do. Use a variable to join across patterns;
- the same blank node label in two operations of one update request;
- blank nodes in expressions (FILTER, BIND, SELECT, ORDER BY, HAVING). Use a variable or the `BNODE()` function;
- blank nodes or variables inside a triple term in `VALUES` (plain blank nodes and variables are not allowed there either).

## Updates

`update { }` builds a SPARQL Update request. Graph management operations:

```kotlin
val request = update {
    clear(Iri("http://example.org/g"))      // CLEAR GRAPH <http://example.org/g>
    clear()                                 // CLEAR DEFAULT
    clear(GraphScope.NAMED, silent = true)  // CLEAR SILENT NAMED
    drop(GraphScope.ALL)                    // DROP ALL
    copy(null, Iri("http://example.org/backup"))  // COPY DEFAULT TO <http://example.org/backup>
    move(Iri("http://example.org/tmp"), null)     // MOVE <http://example.org/tmp> TO DEFAULT
    add(null, Iri("http://example.org/all"))      // ADD DEFAULT TO <http://example.org/all>
}
```

`GraphScope` (`DEFAULT`, `NAMED`, `ALL`) selects the target of `clear` / `drop` when no graph IRI is given. In the AST, `ClearOperationAst` and `DropOperationAst` take either `graph` or `scope`, not both. For `copy`, `move` and `add` (and `CopyOperationAst`, `MoveOperationAst`, `AddOperationAst`), a `null` source or destination means the default graph. Operations are joined with ` ;` and a newline.

## Literal Escaping

Every literal the DSL renders (and every literal the SPARQL endpoint adapter sends) is escaped by the same shared helper:

- `\t`, `\b`, `\n`, `\r`, `\f`, `"` and `\` use the SPARQL escapes; other control characters and DEL use `\u00XX`.
- A `u` or `U` directly after a backslash in the text is written as `\u0075` / `\u0055`. SPARQL 1.1 lets servers decode `\uXXXX` sequences over the whole query text before parsing it. Without this rule, a pre-pass could turn the text backslash-`u0022` into a quote and change the value. The encoded form reads back as the same text whether or not the server does such a pre-pass.
- Unpaired UTF-16 surrogates are rejected.

For example, the Kotlin string `"C:\\users"` (text `C:\users`) renders as `"C:\\\u0075sers"`.

The helper, `com.geoknoesis.kastor.rdf.sparql.internal.SparqlLexical` in the `rdf-sparql-contract` module, is **not public API**. It is public only so that the renderer and the endpoint adapter can share it, and it may change without notice.

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
val complexQuery = select("name", "email", "age") {
    where {
        // Basic patterns
        triple(personVar, namePred, nameVar)
        triple(personVar, agePred, ageVar)
        
        // Optional email
        optional {
            triple(personVar, emailPred, `var`("email"))
            filter(`var`("email") ne "")
        }
        
        
        // Values constraint
        values(nameVar, string("John"), string("Jane"))
        
        // Filters
        filter(ageVar gt 18)
    }
    orderBy(ageVar, OrderDirection.DESC)
    limit(10)
}
```

This comprehensive SPARQL API provides all the tools needed to work with RDF data effectively and efficiently.




