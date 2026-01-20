# Kastor Query DSL Tutorial

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

Prefixes make queries more readable by allowing shortened names instead of full IRIs:

```kotlin
val query = select("name", "age") {
    prefix("foaf", "http://xmlns.com/foaf/0.1/")
    prefix("xsd", "http://www.w3.org/2001/XMLSchema#")
    where {
        `var`("person") has iri("foaf:name") with `var`("name")
        `var`("person") has iri("foaf:age") with `var`("age")
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

**Key Concept**: Prefixes make queries shorter and more readable. You can also use common prefixes with `addCommonPrefixes()`.

### 4.1. Common Prefixes

Kastor provides built-in support for common vocabularies:

```kotlin
val query = select("name", "type") {
    addCommonPrefixes("foaf", "rdf", "rdfs")  // Add multiple common prefixes
    where {
        `var`("person") has iri("foaf:name") with `var`("name")
        `var`("person") has iri("rdf:type") with `var`("type")
    }
}
```

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

## Your First Query

### 5. Simple SELECT Query

Let's write our first query to find people and their names:

```kotlin
val query = select(SparqlSelectQuery("name"))) {
    where {
        personVar has namePred with nameVar
    }
}

println(query)
```

**What this does**:
- `select(SparqlSelectQuery("name")))` - We want to find names
- `where { ... }` - Here are the conditions
- `personVar has namePred with nameVar` - Find people who have names

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
        personVar has namePred with nameVar
        personVar has agePred with ageVar
    }
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
```

**In plain English**: "Find all people and their names and ages"

## Adding Conditions (Filters)

### 7. Simple Filtering

Now let's find only people over 18:

```kotlin
val query = select("name", "age") {
    where {
        personVar has namePred with nameVar
        personVar has agePred with ageVar
        filter(ageVar gt 18)
    }
}

println(query)
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
        personVar has namePred with nameVar
        personVar has agePred with ageVar
        filter(ageVar gt 18)
        filter(ageVar lte 65)
        filter(nameVar eq "John")
    }
}

println(query)
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
        personVar has namePred with nameVar
        personVar has friendPred with `var`("friend")
        `var`("friend") has namePred with `var`("friendName")
    }
}

println(query)
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
        personVar has namePred with nameVar
        optional {
            personVar has emailPred with `var`("email")
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
        personVar has namePred with nameVar
        personVar has agePred with ageVar
        bind(`var`("ageGroup"), 
            if_(ageVar gt 65, string("senior"), 
                if_(ageVar gt 18, string("adult"), string("minor"))))
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
        personVar has namePred with nameVar
        union {
            personVar has emailPred with `var`("contact")
        }
        union {
            personVar has phonePred with `var`("contact")
        }
    }
}

println(query)
```

**Generated SPARQL**:
```sparql
SELECT ?name ?contact
WHERE {
  ?person <http://example.org/name> ?name .
  {
    ?person <http://example.org/email> ?contact .
  }
  UNION
  {
    ?person <http://example.org/phone> ?contact .
  }
}
```

**In plain English**: "Find people and their contact information (either email or phone)"

### 14. Excluding Data (MINUS)

Let's find people who are NOT marked as deleted:

```kotlin
val deletedPred = iri("http://example.org/deleted")

val query = select(SparqlSelectQuery("name"))) {
    where {
        personVar has namePred with nameVar
        minus {
            personVar has deletedPred with string("true")
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

## Advanced Graph Navigation

### 15. Property Paths (SPARQL 1.2)

Property paths let you navigate relationships more powerfully. Let's find friends of friends:

```kotlin
val friendPred = iri("http://example.org/friend")

val query = select("name", "friendOfFriend") {
    where {
        personVar has namePred with nameVar
        personVar has path(friendPred).oneOrMore() with `var`("friendOfFriend")
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

### 16. Alternative Relationships

Let's find people connected by either friendship OR colleague relationships:

```kotlin
val colleaguePred = iri("http://example.org/colleague")

val query = select("name", "contact") {
    where {
        personVar has namePred with nameVar
        personVar has path(friendPred).alternative(path(colleaguePred)) with `var`("contact")
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

RDF-star lets you attach information to statements themselves. Let's find statements with confidence scores:

```kotlin
val query = select("statement", "confidence") {
    where {
        `var`("statement") quoted iri("http://example.org/confidence") with `var`("confidence")
        `var`("statement") quoted iri("http://example.org/subject") with personVar
        `var`("statement") quoted iri("http://example.org/predicate") with namePred
        `var`("statement") quoted iri("http://example.org/object") with nameVar
        filter(`var`("confidence") gt 0.8)
    }
}

println(query)
```

**Generated SPARQL**:
```sparql
SELECT ?statement ?confidence
WHERE {
  ?statement << ?person <http://example.org/name> ?name >> .
  ?statement <http://example.org/confidence> ?confidence .
  FILTER(?confidence > 0.8)
}
```

**In plain English**: "Find statements about people's names that have high confidence scores (over 0.8)"

## Organizing Results

### 18. Sorting and Limiting

Let's find the oldest people, limited to 10 results:

```kotlin
val query = select("name", "age") {
    where {
        personVar has namePred with nameVar
        personVar has agePred with ageVar
    }
    orderBy(ageVar, OrderDirection.DESC)
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
        personVar has namePred with nameVar
        personVar has agePred with ageVar
    }
    orderBy(ageVar, OrderDirection.DESC)
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
            personVar has namePred with nameVar
            personVar has agePred with ageVar
            optional {
                personVar has emailPred with `var`("email")
                filter(`var`("email") ne string(""))
            }
        }
        values(nameVar, string("John"), string("Jane"))
        filter(ageVar gt 18)
    }
    orderBy(ageVar, OrderDirection.DESC)
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
        personVar has namePred with nameVar
        personVar has agePred with ageVar
        
        // Optional contact information
        optional {
            personVar has emailPred with `var`("email")
        }
        
        // Filters
        filter(ageVar gt 18)
        filter(nameVar ne string(""))
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
val query = select("name", "age") {
    addCommonPrefixes("foaf", "rdf")
    prefix("ex", "http://example.org/")
    where {
        `var`("person") has iri("foaf:name") with `var`("name")
        `var`("person") has iri("ex:age") with `var`("age")
    }
}

// Avoid - without prefixes
val query = select("name", "age") {
    where {
        `var`("person") has iri("http://xmlns.com/foaf/0.1/name") with `var`("name")
        `var`("person") has iri("http://example.org/age") with `var`("age")
    }
}
```

## Common Patterns

### 22. Finding Related Data

```kotlin
// Find people and their friends' friends
val query = select("name", "friendOfFriend") {
    where {
        personVar has namePred with nameVar
        personVar has friendPred with `var`("friend")
        `var`("friend") has friendPred with `var`("friendOfFriend")
        `var`("friendOfFriend") has namePred with `var`("friendOfFriendName")
    }
}
```

### 23. Aggregating Data

```kotlin
// Find average age by age group
val query = select("ageGroup", "avgAge") {
    where {
        personVar has agePred with ageVar
        bind(`var`("ageGroup"), 
            if_(ageVar gt 65, string("senior"), 
                if_(ageVar gt 18, string("adult"), string("minor"))))
    }
    groupBy(`var`("ageGroup"))
}
```

### 24. Conditional Queries

```kotlin
// Find people with different contact methods
val query = select("name", "contactType", "contactValue") {
    where {
        personVar has namePred with nameVar
        union {
            personVar has emailPred with `var`("contactValue")
            bind(`var`("contactType"), string("email"))
        }
        union {
            personVar has phonePred with `var`("contactValue")
            bind(`var`("contactType"), string("phone"))
        }
    }
}
```

## Conclusion

Congratulations! You've learned how to use the Kastor Query DSL to query RDF data. Here's what you can now do:

✅ **Write basic queries** to find data  
✅ **Add filters** to narrow down results  
✅ **Work with relationships** between entities  
✅ **Use optional data** that might not exist  
✅ **Create computed values** using BIND  
✅ **Navigate complex relationships** with property paths  
✅ **Work with metadata** using RDF-star  
✅ **Organize results** with sorting and pagination  
✅ **Write complex queries** combining multiple features  
✅ **Use prefix declarations** for readable queries  

The Kastor Query DSL makes RDF querying accessible and intuitive, even for those new to SPARQL. The Kotlin syntax feels natural and the generated queries are optimized for performance.

### Next Steps

- Practice with your own data
- Explore more advanced features like aggregation and subqueries
- Learn about RDF data modeling
- Check out the full API documentation

Happy querying! 🚀



