# Kastor Query DSL Tutorial

{% include version-banner.md %}

> **Documentation mode: Tutorial** — learn the Kotlin query DSL alongside SPARQL concepts. **Reference:** [Compact DSL Guide](../api/compact-dsl-guide.md). **Terms:** [**Glossary**](../concepts/glossary.md).

### Goal

Write **`select { … }`** (and related DSL) queries using **`var`**, triple patterns, **`FILTER`**, **`OPTIONAL`**, and aggregations, and understand how they map to SPARQL text.

## Prerequisites

Add **`rdf-sparql-lang`** to Gradle or Maven (`com.geoknoesis.kastor:rdf-sparql-lang`, version aligned with **`rdf-core`** or via the [Kastor BOM](../getting-started/installation.md)). The **`select {}`** builders and **`com.geoknoesis.kastor.rdf.sparql`** package are **not** part of **`rdf-core`** alone ([architecture](../concepts/architecture.md)).

## Introduction

Welcome to the Kastor Query DSL tutorial! This guide will teach you how to query RDF (Resource Description Framework) data using Kastor's intuitive Kotlin DSL, even if you've never used SPARQL before.

### What is RDF?

RDF is a way to represent information as **triples** - think of them as simple statements with three parts:
- **Subject**: What we're talking about
- **Predicate**: What property or relationship we're describing
- **Object**: The value or target of that property

For example:
- Subject: "John Smith" 
- Predicate: "has age"
- Object: "30"

In RDF, this becomes: `John Smith has age 30`

### What is the Kastor Query DSL?

The Kastor Query DSL is a Kotlin library that lets you write database queries in a natural, readable way. Instead of writing complex SQL-like queries, you can use simple Kotlin functions and operators.

## Prerequisites

- Basic Kotlin knowledge
- Understanding of data structures (lists, maps, etc.)
- No SPARQL knowledge required!

## Getting Started

### 1. Basic Setup

First, let's set up our environment:

```kotlin
import com.geoknoesis.kastor.rdf.*

// This is where we'll write our queries
```

### 2. Understanding Variables

In Kastor, we use **variables** to represent unknown values we want to find. Think of them as placeholders:

```kotlin
// Create variables for things we want to find
val personVar = `var`("person")  // Will hold a person
val nameVar = `var`("name")       // Will hold a name
val ageVar = `var`("age")         // Will hold an age

println(personVar) // Output: ?person
println(nameVar)    // Output: ?name
println(ageVar)     // Output: ?age
```

**Key Concept**: Variables start with `?` in the generated query and represent values we want to retrieve.

### 3. Understanding Resources

Resources are the "things" in our data - people, places, concepts, etc. We identify them using IRIs (like URLs):

```kotlin
// Define some resources (like database tables or object types)
val person = iri("http://example.org/person")
val namePred = iri("http://example.org/name")
val agePred = iri("http://example.org/age")

println(person)    // Output: <http://example.org/person>
println(namePred)  // Output: <http://example.org/name>
println(agePred)   // Output: <http://example.org/age>
```

**Key Concept**: IRIs are like unique identifiers for resources, similar to how URLs identify web pages.

### 4. Using Prefix Declarations

Prefixes make queries more readable by allowing shortened names (QNames) instead of full IRIs:

```kotlin
val query = select("name", "age") {
    prefix("foaf", "http://xmlns.com/foaf/0.1/")
    prefix("xsd", "http://www.w3.org/2001/XMLSchema#")
    where {
        triple(`var`("person"), iri("foaf:name"), `var`("name"))
        triple(`var`("person"), iri("foaf:age"), `var`("age"))
    }
}
```

**Generated SPARQL**:
```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>

SELECT ?name ?age
WHERE {
  ?person foaf:name ?name .
  ?person foaf:age ?age .
}
```

**Key Concept**: Prefixes make queries shorter and more readable. When you declare a prefix with `prefix("foaf", "http://xmlns.com/foaf/0.1/")`, you can then use QNames like `iri("foaf:name")` in your query, which will be expanded to the full IRI.

**Alternative: Using Vocabulary Constants**

You can also use vocabulary constants directly (like `FOAF.name`) without declaring prefixes, as the vocabulary objects already contain the full IRIs:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF

val query = select("name", "age") {
    where {
        triple(`var`("person"), FOAF.name, `var`("name"))
        triple(`var`("person"), FOAF.age, `var`("age"))
    }
}
```

This approach is type-safe and doesn't require prefix declarations, but the generated SPARQL will use full IRIs unless you also declare the prefix. You can combine both approaches:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF

val query = select("name", "age") {
    prefix("foaf", FOAF.namespace)  // Declare prefix for readable output
    where {
        triple(`var`("person"), FOAF.name, `var`("name"))  // Use vocabulary constant
        triple(`var`("person"), FOAF.age, `var`("age"))
    }
}
```

This will generate SPARQL with the `foaf:` prefix, making it more readable while still using type-safe vocabulary constants in your Kotlin code.

**The empty prefix**: `prefix("", "http://example.org/fn#")` declares `PREFIX : <http://example.org/fn#>`, so that names such as `:double` can be used, for example as a function name: `function(":double", x)`. Every query form and `update { }` accept it.

### 4.1. Common Prefixes

Kastor provides built-in support for common vocabularies. You can use `addCommonPrefixes()` to add multiple standard prefixes at once:

```kotlin
val query = select("name", "type") {
    addCommonPrefixes("foaf", "rdf", "rdfs")  // Add multiple common prefixes
    where {
        triple(`var`("person"), iri("foaf:name"), `var`("name"))
        triple(`var`("person"), iri("rdf:type"), `var`("type"))
    }
}
```

**Note**: When using `addCommonPrefixes()`, you still need to use QNames (like `iri("foaf:name")`) in your query. If you prefer to use vocabulary constants (like `FOAF.name`), declare the prefix explicitly with `prefix("foaf", FOAF.namespace)` instead.

**Available Common Prefixes**:
- `foaf`: Friend of a Friend vocabulary
- `rdf`: RDF vocabulary  
- `rdfs`: RDF Schema vocabulary
- `owl`: OWL vocabulary
- `xsd`: XML Schema Definition
- `dc`: Dublin Core
- `dcterms`: Dublin Core Terms
- `schema`: Schema.org
- `dbpedia`: DBpedia ontology
- `wikidata`: Wikidata
- `skos`: SKOS vocabulary
- `prov`: W3C PROV-O (`http://www.w3.org/ns/prov#`)
- `dcat`: DCAT (`http://www.w3.org/ns/dcat#`)
- `void`: VoID (`http://rdfs.org/ns/void#`)
- `geo`: GeoSPARQL (`http://www.opengis.net/ont/geosparql#`)
- `time`: OWL-Time (`http://www.w3.org/2006/time#`)

## Your First Query

### 5. Simple SELECT Query

Let's write our first query to find people and their names:

```kotlin
val query = select("name") {
    where {
        triple(`var`("person"), namePred, `var`("name"))
    }
}

println(query.sparql)
```

**What this does**:
- `select(SparqlSelectQuery("name")))` - We want to find names
- `where { ... }` - Here are the conditions
- `triple(personVar, namePred, nameVar)` - Find people who have names

**Generated SPARQL**:
```sparql
SELECT ?name
WHERE {
  ?person <http://example.org/name> ?name .
}
```

**In plain English**: "Find all names in our data"

### 6. Multiple Variables

Let's find both names and ages:

```kotlin
val query = select("name", "age") {
    where {
        triple(`var`("person"), namePred, `var`("name"))
        triple(`var`("person"), agePred, `var`("age"))
    }
}

println(query.sparql)
```

**Generated SPARQL**:
```sparql
SELECT ?name ?age
WHERE {
  ?person <http://example.org/name> ?name .
  ?person <http://example.org/age> ?age .
}
```

**In plain English**: "Find all people and their names and ages"

## Adding Conditions (Filters)

### 7. Simple Filtering

Now let's find only people over 18:

```kotlin
val query = select("name", "age") {
    where {
        triple(`var`("person"), namePred, `var`("name"))
        triple(`var`("person"), agePred, `var`("age"))
        filter(`var`("age") gt 18)
    }
}

println(query.sparql)
```

**Generated SPARQL**:
```sparql
SELECT ?name ?age
WHERE {
  ?person <http://example.org/name> ?name .
  ?person <http://example.org/age> ?age .
  FILTER(?age > 18)
}
```

**In plain English**: "Find all people over 18 and their names and ages"

### 8. Multiple Filters

Let's add more conditions:

```kotlin
val query = select("name", "age") {
    where {
        triple(`var`("person"), namePred, `var`("name"))
        triple(`var`("person"), agePred, `var`("age"))
        filter(`var`("age") gt 18)
        filter(`var`("age") lte 65)
        filter(`var`("name") eq "John")
    }
}

println(query.sparql)
```

**Generated SPARQL**:
```sparql
SELECT ?name ?age
WHERE {
  ?person <http://example.org/name> ?name .
  ?person <http://example.org/age> ?age .
  FILTER(?age > 18)
  FILTER(?age <= 65)
  FILTER(?name = "John")
}
```

**In plain English**: "Find people named John who are between 18 and 65 years old"

## Working with Relationships

### 9. Finding Friends

Let's find people and their friends:

```kotlin
val friendPred = iri("http://example.org/friend")

val query = select("name", "friendName") {
    where {
        triple(`var`("person"), namePred, `var`("name"))
        triple(`var`("person"), friendPred, `var`("friend"))
        triple(`var`("friend"), namePred, `var`("friendName"))
    }
}

println(query.sparql)
```

**Generated SPARQL**:
```sparql
SELECT ?name ?friendName
WHERE {
  ?person <http://example.org/name> ?name .
  ?person <http://example.org/friend> ?friend .
  ?friend <http://example.org/name> ?friendName .
}
```

**In plain English**: "Find all people and the names of their friends"

### 10. Optional Information

Sometimes we want information that might not exist. Let's find people and their emails (if they have one):

```kotlin
val emailPred = iri("http://example.org/email")

val query = select("name", "email") {
    where {
        triple(`var`("person"), namePred, `var`("name"))
        optional {
            triple(`var`("person"), emailPred, `var`("email"))
        }
    }
}

println(query)
```

**Generated SPARQL**:
```sparql
SELECT ?name ?email
WHERE {
  ?person <http://example.org/name> ?name .
  OPTIONAL {
    ?person <http://example.org/email> ?email .
  }
}
```

**In plain English**: "Find all people and their emails (if they have one)"

## Advanced Features

### 11. Computed Values (BIND)

Let's create computed values, like combining first and last names:

```kotlin
val firstNamePred = iri("http://example.org/firstName")
val lastNamePred = iri("http://example.org/lastName")

val query = select("firstName", "lastName", "fullName") {
    where {
        personVar has firstNamePred with `var`("firstName")
        personVar has lastNamePred with `var`("lastName")
        bind(`var`("fullName"), concat(`var`("firstName"), string(" "), `var`("lastName")))
    }
}

println(query)
```

**Generated SPARQL**:
```sparql
SELECT ?firstName ?lastName ?fullName
WHERE {
  ?person <http://example.org/firstName> ?firstName .
  ?person <http://example.org/lastName> ?lastName .
  BIND(CONCAT(?firstName, " ", ?lastName) AS ?fullName)
}
```

**In plain English**: "Find people's first and last names, and create a full name by combining them"

### 12. Conditional Values

Let's create age groups based on age:

```kotlin
val query = select("name", "age", "ageGroup") {
    where {
        triple(`var`("person"), namePred, `var`("name"))
        triple(`var`("person"), agePred, `var`("age"))
        bind(`var`("ageGroup"), 
            if_(`var`("age") gt 65, string("senior"), 
                if_(`var`("age") gt 18, string("adult"), string("minor"))))
    }
}

println(query)
```

**Generated SPARQL**:
```sparql
SELECT ?name ?age ?ageGroup
WHERE {
  ?person <http://example.org/name> ?name .
  ?person <http://example.org/age> ?age .
  BIND(IF(?age > 65, "senior", IF(?age > 18, "adult", "minor")) AS ?ageGroup)
}
```

**In plain English**: "Find people and categorize them as minor (under 18), adult (18-65), or senior (over 65)"

## Working with Multiple Sources

### 13. Union Queries

Let's find people who have either an email OR a phone number:

```kotlin
val phonePred = iri("http://example.org/phone")

val query = select("name", "contact") {
    where {
        triple(`var`("person"), namePred, `var`("name"))
        union(
            { triple(`var`("person"), emailPred, `var`("contact")) },
            { triple(`var`("person"), phonePred, `var`("contact")) },
        )
    }
}

println(query.sparql)
```

**Generated SPARQL**:
```sparql
SELECT ?name ?contact
WHERE {
  ?person <http://example.org/name> ?name .
  {
    ?person <http://example.org/email> ?contact .
  } UNION {
    ?person <http://example.org/phone> ?contact .
  }
}
```

`union(first, second, ...)` takes every branch explicitly (two or more) and adds one `UNION` element at its position, so the order of the surrounding patterns does not matter. For branches with several patterns, `unionOf { branch { … }; branch { … } }` builds the same element (it requires at least two `branch { }` blocks; see [Conditional Queries](#24-conditional-queries)).

**Note**: the single-block `union { }` is deprecated (level WARNING). It turns only the one pattern added immediately before it into the left branch, so in `triple(a); filter(f); union { b }` the filter, not the triple, is unioned and stops constraining `a`. Replace it with `union({ … }, { … })` or `unionOf { }`.

**In plain English**: "Find people and their contact information (either email or phone)"

### 14. Excluding Data (MINUS)

Let's find people who are NOT marked as deleted:

```kotlin
val deletedPred = iri("http://example.org/deleted")

val query = select("name") {
    where {
        triple(`var`("person"), namePred, `var`("name"))
        minus {
            triple(`var`("person"), deletedPred, string("true"))
        }
    }
}

println(query)
```

**Generated SPARQL**:
```sparql
SELECT ?name
WHERE {
  ?person <http://example.org/name> ?name .
  MINUS {
    ?person <http://example.org/deleted> "true" .
  }
}
```

**In plain English**: "Find all people except those marked as deleted"

**Note**: as in SPARQL, `minus { }` subtracts from everything that precedes it in the same group. A `minus { }` with nothing before it in its group removes nothing, so put the patterns you want to filter first. To subtract from specific patterns only, build a `MinusPatternAst(left, right)` with a non-empty `left`. The renderer then wraps it in its own group, `{ left MINUS { right } }`, so the MINUS never applies to patterns outside `left`:

```sparql
  {
    ?s <urn:a> ?o .
    MINUS {
      ?s <urn:b> ?z .
    }
  }
```

## Advanced Graph Navigation

### 15. Property Paths (SPARQL 1.2)

Property paths let you navigate relationships more powerfully. Let's find friends of friends:

```kotlin
val friendPred = iri("http://example.org/friend")

val query = select("name", "friendOfFriend") {
    where {
        triple(`var`("person"), namePred, `var`("name"))
        propertyPath(`var`("person"), path(friendPred).oneOrMore(), `var`("friendOfFriend"))
    }
}

println(query)
```

**Generated SPARQL**:
```sparql
SELECT ?name ?friendOfFriend
WHERE {
  ?person <http://example.org/name> ?name .
  ?person <http://example.org/friend>+ ?friendOfFriend .
}
```

**In plain English**: "Find people and their friends, friends of friends, friends of friends of friends, etc."

**Note**: paths are parenthesised by operator precedence when rendered. `negation()` accepts only IRIs and inverse IRIs (`!(p|^q)`).

#### Bounded repetition

SPARQL 1.1/1.2 has no `{n,m}` quantifier (it was dropped from the final grammar). The helpers `exactly`, `atLeast`, `atMost` and `between` therefore write the repetition out as the sequence it stands for, which every endpoint understands:

| DSL | Rendered path |
|-----|---------------|
| `path(p).exactly(3)` | `p/p/p` |
| `path(p).between(1, 3)` | `p/p?/p?` |
| `path(p).atMost(2)` | `p?/p?` |
| `path(p).atLeast(2)` | `p/p+` |
| `path(p).atLeast(1)` / `atLeast(0)` | `p+` / `p*` |
| `(path(a) alternative path(b)).exactly(2)` | `(a\|b)/(a\|b)` |

Because every step is written, a repetition is limited to 64 steps (`MAX_PATH_REPETITION`) and all nested repetitions of one path to 1024 steps; use `oneOrMore()` / `zeroOrMore()` when the upper bound does not matter. Bounds that have no SPARQL form are rejected with `IllegalArgumentException` when the path is built: a negative bound, an upper bound below the lower one, and the empty path (`exactly(0)`, `atMost(0)`). As with `?` in SPARQL, `atMost(n)` and `between(0, n)` include zero steps, so the subject itself is among the results.

### 16. Alternative Relationships

Let's find people connected by either friendship OR colleague relationships:

```kotlin
val colleaguePred = iri("http://example.org/colleague")

val query = select("name", "contact") {
    where {
        triple(`var`("person"), namePred, `var`("name"))
        propertyPath(`var`("person"), path(friendPred).alternative(path(colleaguePred)), `var`("contact"))
    }
}

println(query)
```

**Generated SPARQL**:
```sparql
SELECT ?name ?contact
WHERE {
  ?person <http://example.org/name> ?name .
  ?person (<http://example.org/friend>|<http://example.org/colleague>) ?contact .
}
```

**In plain English**: "Find people and their friends OR colleagues"

## Working with Metadata (RDF-star)

### 17. Statement-level Information

RDF 1.2 attaches information to a statement through a **reifier**: `?statement rdf:reifies <<( subject predicate object )>>`. Let's find name statements with confidence scores above 0.8.

The `where { }` builder has no helper for a bound reifier, so build this query from the AST types in `com.geoknoesis.kastor.rdf.sparql` and convert it with `toSparqlSelect()`:

```kotlin
import com.geoknoesis.kastor.rdf.sparql.*

val statementVar = `var`("statement")
val confidenceVar = `var`("confidence")

val query = SelectQueryAst(
    selectItems = listOf(VariableSelectItemAst(statementVar), VariableSelectItemAst(confidenceVar)),
    where = GroupPatternAst(listOf(
        ReifierPatternAst(statementVar, TripleTermPatternAst(personVar, namePred, nameVar)),
        TriplePatternAst(statementVar, iri("http://example.org/confidence"), confidenceVar),
        FilterPatternAst(confidenceVar gt 0.8),
    )),
).toSparqlSelect()

println(query.sparql)
```

**Generated SPARQL** (layout may differ slightly):
```sparql
SELECT ?statement ?confidence
WHERE {
  ?statement <http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies> <<( ?person <http://example.org/name> ?name )>> .
  ?statement <http://example.org/confidence> ?confidence .
  FILTER(?confidence > "0.8"^^<http://www.w3.org/2001/XMLSchema#double>)
}
```

If you don't need the reifier itself, `quotedTriple(personVar, namePred, nameVar)` inside `where { }` renders the reified-triple pattern `<< ?person <http://example.org/name> ?name >> .`, which matches any reifier of the triple.

**In plain English**: "Find statements about people's names that have high confidence scores (over 0.8)"

## Organizing Results

### 18. Sorting and Limiting

Let's find the oldest people, limited to 10 results:

```kotlin
val query = select("name", "age") {
    where {
        triple(`var`("person"), namePred, `var`("name"))
        triple(`var`("person"), agePred, `var`("age"))
    }
    orderBy(`var`("age"), OrderDirection.DESC)
    limit(10)
}

println(query)
```

**Generated SPARQL**:
```sparql
SELECT ?name ?age
WHERE {
  ?person <http://example.org/name> ?name .
  ?person <http://example.org/age> ?age .
}
ORDER BY DESC(?age)
LIMIT 10
```

**In plain English**: "Find the 10 oldest people"

### 19. Pagination

Let's add pagination to get results 11-20:

```kotlin
val query = select("name", "age") {
    where {
        triple(`var`("person"), namePred, `var`("name"))
        triple(`var`("person"), agePred, `var`("age"))
    }
    orderBy(`var`("age"), OrderDirection.DESC)
    limit(10)
    offset(10)
}

println(query)
```

**Generated SPARQL**:
```sparql
SELECT ?name ?age
WHERE {
  ?person <http://example.org/name> ?name .
  ?person <http://example.org/age> ?age .
}
ORDER BY DESC(?age)
LIMIT 10
OFFSET 10
```

**In plain English**: "Find the 11th to 20th oldest people"

## Complex Queries

### 20. Putting It All Together

Let's create a complex query that demonstrates multiple features:

```kotlin
val query = select("name", "email", "age", "graph") {
    where {
        graph(`var`("graph")) {
            triple(`var`("person"), namePred, `var`("name"))
            triple(`var`("person"), agePred, `var`("age"))
            optional {
                triple(`var`("person"), emailPred, `var`("email"))
                filter(`var`("email") ne string(""))
            }
        }
        values(`var`("name"), string("John"), string("Jane"))
        filter(`var`("age") gt 18)
    }
    orderBy(`var`("age"), OrderDirection.DESC)
    limit(10)
}

println(query)
```

**Generated SPARQL**:
```sparql
SELECT ?name ?email ?age ?graph
WHERE {
  GRAPH ?graph {
    ?person <http://example.org/name> ?name .
    ?person <http://example.org/age> ?age .
    OPTIONAL {
      ?person <http://example.org/email> ?email .
      FILTER(?email != "")
    }
  }
  VALUES ?name { "John" "Jane" }
  FILTER(?age > 18)
}
ORDER BY DESC(?age)
LIMIT 10
```

**In plain English**: "In each graph, find people named John or Jane who are over 18, include their emails if they have one, and return the 10 oldest results"

## Best Practices

### 21. Writing Readable Queries

Here are some tips for writing clear, maintainable queries:

1. **Use descriptive variable names**:
```kotlin
// Good
val personVar = `var`("person")
val friendNameVar = `var`("friendName")

// Avoid
val p = `var`("p")
val fn = `var`("fn")
```

2. **Group related conditions**:
```kotlin
val query = select("name", "age", "email") {
    where {
        // Basic person information
        triple(`var`("person"), namePred, `var`("name"))
        triple(`var`("person"), agePred, `var`("age"))
        
        // Optional contact information
        optional {
            triple(`var`("person"), emailPred, `var`("email"))
        }
        
        // Filters
        filter(`var`("age") gt 18)
        filter(`var`("name") ne string(""))
    }
}
```

3. **Use meaningful resource names**:
```kotlin
// Good
val person = iri("http://example.org/person")
val namePred = iri("http://example.org/name")

// Avoid
val p = iri("http://example.org/p")
val n = iri("http://example.org/n")
```

4. **Use prefix declarations for readability**:
```kotlin
// Good - with prefixes
import com.geoknoesis.kastor.rdf.vocab.FOAF

val query = select("name", "age") {
    addCommonPrefixes("foaf", "rdf")
    prefix("ex", "http://example.org/")
    where {
        triple(`var`("person"), FOAF.name, `var`("name"))
        triple(`var`("person"), iri("ex:age"), `var`("age"))
    }
}

// Avoid - without prefixes
val query = select("name", "age") {
    where {
        triple(`var`("person"), FOAF.name, `var`("name"))
        triple(`var`("person"), iri("http://example.org/age"), `var`("age"))
    }
}
```

## Common Patterns

### 22. Finding Related Data

```kotlin
// Find people and their friends' friends
val query = select("name", "friendOfFriend") {
    where {
        triple(`var`("person"), namePred, `var`("name"))
        triple(`var`("person"), friendPred, `var`("friend"))
        triple(`var`("friend"), friendPred, `var`("friendOfFriend"))
        triple(`var`("friendOfFriend"), namePred, `var`("friendOfFriendName"))
    }
}
```

### 23. Aggregating Data

```kotlin
// Find average age by age group
val query = select("ageGroup") {
    aggregate(AggregateFunction.AVG, `var`("age").expr(), "avgAge")
    where {
        triple(`var`("person"), agePred, `var`("age"))
        bind(`var`("ageGroup"),
            if_(`var`("age") gt 65, string("senior").expr(),
                if_(`var`("age") gt 18, string("adult").expr(), string("minor").expr())))
    }
    groupBy(`var`("ageGroup"))
}

println(query.sparql)
```

**Generated SPARQL**:
```sparql
SELECT ?ageGroup (AVG(?age) AS ?avgAge)
WHERE {
  ?person <http://example.org/age> ?age .
  BIND(IF(?age > "65"^^<http://www.w3.org/2001/XMLSchema#integer>, "senior"^^<http://www.w3.org/2001/XMLSchema#string>, IF(?age > "18"^^<http://www.w3.org/2001/XMLSchema#integer>, "adult"^^<http://www.w3.org/2001/XMLSchema#string>, "minor"^^<http://www.w3.org/2001/XMLSchema#string>)) AS ?ageGroup)
}
GROUP BY ?ageGroup
```

`aggregate(...)` adds `(AVG(?age) AS ?avgAge)` after the listed variables. With `groupBy`, every projected variable must be grouped or computed by an aggregate. Literals are written with their datatype.

### 24. Conditional Queries

```kotlin
// Find people with different contact methods
val query = select("name", "contactType", "contactValue") {
    where {
        triple(`var`("person"), namePred, `var`("name"))
        unionOf {
            branch {
                triple(`var`("person"), emailPred, `var`("contactValue"))
                bind(`var`("contactType"), string("email"))
            }
            branch {
                triple(`var`("person"), phonePred, `var`("contactValue"))
                bind(`var`("contactType"), string("phone"))
            }
        }
    }
}
```

**Generated SPARQL**:
```sparql
SELECT ?name ?contactType ?contactValue
WHERE {
  ?person <http://example.org/name> ?name .
  {
    ?person <http://example.org/email> ?contactValue .
    BIND("email" AS ?contactType)
  } UNION {
    ?person <http://example.org/phone> ?contactValue .
    BIND("phone" AS ?contactType)
  }
}
```

### 25. EXISTS and NOT EXISTS

`exists { }` and `notExists { }` are conditions over a graph pattern: they are true for a solution when the pattern, with that solution's bindings, has (or has no) match. The block takes whatever a `where { }` block takes.

```kotlin
val query = select("person") {
    where {
        triple(`var`("person"), namePred, `var`("name"))
        filter { exists { triple(`var`("person"), emailPred, `var`("email")) } }
        filter { notExists { triple(`var`("person"), phonePred, `var`("phone")) } }
    }
}
```

**Generated SPARQL**:
```sparql
SELECT ?person
WHERE {
  ?person <http://example.org/name> ?name .
  FILTER(EXISTS {
  ?person <http://example.org/email> ?email .
})
  FILTER(NOT EXISTS {
  ?person <http://example.org/phone> ?phone .
})
}
```

They are expressions like any other: combine them with `and`, `or`, `not(...)` or `!`, and use them in `bind`, in a projection, in `having { }` and in `orderBy`.

### 26. IN, NOT IN, unary minus and NOT

```kotlin
val query = select("person") {
    where {
        triple(`var`("person"), agePred, `var`("age"))
        triple(`var`("person"), cityPred, `var`("city"))
        filter(`var`("city").isIn(iri("http://example.org/Paris"), iri("http://example.org/Rome")))   // ?city IN (<...>, <...>)
        filter { `var`("age").expr().isNotIn(18.toLiteral().expr(), 21.toLiteral().expr()) }          // ?age NOT IN (...)
        bind(`var`("debt"), -`var`("age").expr())                                                      // BIND(-?age AS ?debt)
        filter { !bound(`var`("nickname")) }                                                           // FILTER(!(BOUND(?nickname)))
    }
}
```

- `x.isIn(a, b)` / `x.isNotIn(a, b)` take expressions (or, on a variable, terms); `x isIn listOf(...)` takes a collection. An empty list is legal: `IN ()` is false and `NOT IN ()` true.
- `-expression` is arithmetic negation; `!condition` is the same as `not(condition)`.
- Operands are bracketed where SPARQL's precedence requires it (`-(?a + ?b)`, `(?a > ?b) IN (true)`), so the query means the expression tree you built.

### 27. Grouping by an expression

`groupBy(expression)` groups by a computed value, and `groupBy(expression, alias)` names it so that it can be projected:

```kotlin
val query = select {
    variable("decade")
    expression(countAll(), "people")
    where { triple(`var`("person"), agePred, `var`("age")) }
    groupBy(function("FLOOR", `var`("age").expr() div 10.toLiteral().expr()), `var`("decade"))
}
```

**Generated SPARQL** (literals abbreviated):
```sparql
SELECT ?decade (COUNT(*) AS ?people)
WHERE {
  ?person <http://example.org/age> ?age .
}
GROUP BY (FLOOR((?age / 10)) AS ?decade)
```

Plain variables (`groupBy(v1, v2)`) are written first, then the expressions, each in the order given.

### 28. SERVICE SILENT

`service(endpoint) { }` renders `SERVICE <endpoint> { ... }`. Pass `silent = true` for `SERVICE SILENT`, with which a remote service that fails contributes one solution without bindings instead of failing the whole query:

```kotlin
where {
    service(iri("https://query.example.org/sparql"), silent = true) {
        triple(`var`("s"), `var`("p"), `var`("o"))
    }
}
```

### 29. Function calls as conditions

`function(name, args...)` builds a call of a built-in, a prefixed name (declare the prefix) or an IRI. The call is an expression and a condition alike, so it can stand directly in a filter:

```kotlin
select("s") {
    prefix("ex", "http://example.org/fn#")
    where {
        triple(`var`("s"), namePred, `var`("name"))
        filter { function("ex:isCommon", `var`("name").expr()) and not(function("isBLANK", `var`("s").expr())) }
        bind(`var`("upper"), function("UCASE", `var`("name").expr()))
    }
}
```

## Query Rules Enforced by the Renderer

The renderer rejects queries that are not legal SPARQL with `IllegalArgumentException` instead of sending them:

- **Projections with GROUP BY / HAVING**: a query with `groupBy(...)` or `having { }` must list its projection explicitly. `SELECT *` or an empty projection (`select { }` with no variables or expressions, which renders `SELECT *`) is rejected.
- **`*` mixed with variables**: a `WildcardSelectItemAst` cannot be combined with other projection items.
- **Blank nodes**: a blank node label may be used in only one basic graph pattern of a query (FILTER, BIND and VALUES do not end a basic graph pattern, but OPTIONAL, UNION, MINUS, GRAPH, sub-groups and similar elements do), or in only one operation of an update request. Use a variable to join across patterns. Blank nodes are also rejected inside expressions (FILTER, BIND, SELECT, ORDER BY, HAVING; use a variable or `BNODE()`) and inside triple terms in `VALUES`. The pattern of an `exists { }` / `notExists { }` is a group of its own, so a label used there cannot also be used in the patterns around it.
- **Bounded path repetition**: bounds without a SPARQL form and repetitions of more than 64 steps (1024 for nested repetitions) are rejected; see [Bounded repetition](#bounded-repetition).
- **Builders and the AST**: `build()` on a builder returns an AST that later calls on the builder do not change.

## Validation

- Reproduce each major section (`SELECT`, `FILTER`, `OPTIONAL`, aggregates) in a small **`@Test`** or **`main`** using **`Rdf.memory()`** and assert on **`SparqlQueryResult`** size or bindings.

## Related

- [Compact DSL Guide](../api/compact-dsl-guide.md)
- [SPARQL fundamentals](../concepts/sparql-fundamentals.md)
- [Guides hub](guides.md)
