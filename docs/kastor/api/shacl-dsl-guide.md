# SHACL DSL Guide

{% include version-banner.md %}

The SHACL DSL provides a type-safe, natural language syntax for creating SHACL shapes graphs in Kotlin. Instead of manually creating RDF triples, you can use an intuitive DSL that makes SHACL constraint definitions readable and maintainable.

## Overview

The SHACL DSL allows you to define validation constraints using Kotlin code that reads like natural language. It supports all SHACL Core constraints and generates standard RDF that works with any SHACL validator.

### Benefits

- **Type-safe**: Compile-time validation of constraint combinations
- **Readable**: Natural language syntax that's easy to understand
- **Maintainable**: Less boilerplate than manual RDF triple creation
- **Complete**: Supports all SHACL Core constraint types
- **Standard**: Generates standard RDF/Turtle that works with any validator

## Quick Start

### Basic Example

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.XSD

val shapesGraph = shacl {
    nodeShape("http://example.org/PersonShape") {
        targetClass(FOAF.Person)
        
        property(FOAF.name) {
            minCount = 1
            maxCount = 1
            datatype = XSD.string
            minLength = 1
            maxLength = 100
        }
        
        property(FOAF.age) {
            minCount = 0
            maxCount = 1
            datatype = XSD.integer
            minInclusive = 0.0
            maxInclusive = 150.0
        }
    }
}
```

### Using Rdf.shacl()

You can also use the `Rdf.shacl()` function for consistency with other Kastor APIs:

```kotlin
val shapesGraph = Rdf.shacl {
    nodeShape("http://example.org/Shape") {
        targetClass("http://example.org/Class")
    }
}
```

## Node Shapes

### Basic Node Shape

```kotlin
val shapesGraph = shacl {
    nodeShape("http://example.org/PersonShape") {
        targetClass(FOAF.Person)
    }
}
```

### Target Declarations

Node shapes can target resources in several ways:

```kotlin
nodeShape("http://example.org/Shape") {
    // Target all instances of a class
    targetClass("http://example.org/Person")
    targetClass(FOAF.Person)  // Using IRI constant
    
    // Target specific nodes
    targetNode(Iri("http://example.org/alice"))
    targetNode("http://example.org/bob")
    
    // Target objects of a property
    targetObjectsOf("http://example.org/knows")
    
    // Target subjects of a property
    targetSubjectsOf("http://example.org/author")
}
```

### Closed Shapes

Closed shapes enforce that resources can only have properties that are explicitly declared in the shape. This is useful for strict data models where additional properties should be rejected.

```kotlin
nodeShape("http://example.org/ClosedShape") {
    closed(true)
    ignoredProperties(
        "http://example.org/allowed1",
        "http://example.org/allowed2"
    )
}
```

**What it does:**
- `closed(true)`: Enables closed shape validation - only declared properties are allowed
- `ignoredProperties`: Lists properties that are allowed even if not explicitly declared
- When closed, any property not declared in the shape (and not in ignoredProperties) causes a validation failure

**Example validation:**
```kotlin
// Shape: closed=true, properties: [name, email], ignoredProperties: [rdf:type]
// Valid: resource has name, email, rdf:type (all allowed)
// Invalid: resource has name, email, age (age not declared and not ignored)
// Invalid: resource has name, email, custom:prop (custom:prop not declared)
```

**When to use:**
- Strict data models where you want to reject unknown properties
- API contracts where additional fields should be errors
- Data quality enforcement where extra properties indicate data issues

### Deactivating Shapes

```kotlin
nodeShape("http://example.org/DeactivatedShape") {
    deactivated(true)
}
```

## Property Constraints

### Cardinality Constraints

Cardinality constraints control how many values a property can have. They're essential for defining required vs optional properties and single vs multiple values.

```kotlin
property(FOAF.name) {
    minCount = 1      // At least 1 value required
    maxCount = 1      // At most 1 value allowed
}

property(FOAF.knows) {
    minCount = 0      // Optional
    maxCount = null   // Unlimited (default)
}
```

**What they do:**
- `minCount`: Specifies the minimum number of values that must be present. If a resource has fewer values than `minCount`, validation fails.
- `maxCount`: Specifies the maximum number of values allowed. If a resource has more values than `maxCount`, validation fails.

**Common patterns:**
- `minCount = 1, maxCount = 1`: Required single value (e.g., a person must have exactly one name)
- `minCount = 0, maxCount = 1`: Optional single value (e.g., a person may have zero or one email)
- `minCount = 0, maxCount = null`: Optional, unlimited values (e.g., a person can have any number of friends)
- `minCount = 1, maxCount = null`: Required, unlimited values (e.g., a product must have at least one review)

**Example validation:**
```kotlin
// Shape: minCount = 1, maxCount = 1
// Valid: person has exactly 1 name
// Invalid: person has 0 names (violates minCount)
// Invalid: person has 2 names (violates maxCount)
```

### Type Constraints

Type constraints ensure that property values have the correct RDF type. They're fundamental for data integrity.

#### Datatype

The `datatype` constraint ensures that all values are literals of a specific XML Schema datatype.

```kotlin
property(FOAF.name) {
    datatype = XSD.string
}

// Or using string QName
property("http://example.org/age") {
    datatype("xsd:integer")
}
```

**What it does:**
- Validates that all property values are literals with the specified datatype
- Rejects values with different datatypes or non-literal values (IRIs, blank nodes)
- Works with XSD datatypes like `xsd:string`, `xsd:integer`, `xsd:date`, `xsd:boolean`, etc.

**Example validation:**
```kotlin
// Shape: datatype = XSD.integer
// Valid: age = 30 (xsd:integer)
// Invalid: age = "30" (xsd:string, not xsd:integer)
// Invalid: age = <http://example.org/30> (IRI, not a literal)
```

#### Class

The `class` constraint ensures that all property values are resources (IRIs or blank nodes) that are instances of a specific class.

```kotlin
property(FOAF.knows) {
    `class` = FOAF.Person
}

// Or using string QName
property(FOAF.knows) {
    `class`("foaf:Person")
}
```

**What it does:**
- Validates that all property values are resources (not literals)
- Checks that each resource has `rdf:type` equal to the specified class (or a subclass via RDFS reasoning)
- Useful for object properties that reference other resources

**Example validation:**
```kotlin
// Shape: class = FOAF.Person
// Valid: knows = <http://example.org/alice> where alice rdf:type foaf:Person
// Invalid: knows = "Alice" (literal, not a resource)
// Invalid: knows = <http://example.org/alice> where alice rdf:type foaf:Organization
```

#### Node Kind

The `nodeKind` constraint restricts the RDF term type that values can have, without specifying a particular datatype or class.

```kotlin
property("http://example.org/resource") {
    nodeKind = NodeKind.IRI
}

// Available node kinds:
// - NodeKind.IRI
// - NodeKind.BlankNode
// - NodeKind.Literal
// - NodeKind.BlankNodeOrIRI
// - NodeKind.BlankNodeOrLiteral
// - NodeKind.IRIOrLiteral
```

**What it does:**
- Validates the structural type of RDF terms (IRI, blank node, or literal)
- More general than `datatype` or `class` - just checks the term type
- Useful when you care about the term structure but not the specific value type

**Example validation:**
```kotlin
// Shape: nodeKind = NodeKind.IRI
// Valid: resource = <http://example.org/resource1> (IRI)
// Invalid: resource = "http://example.org/resource1" (literal)
// Invalid: resource = _:b1 (blank node)

// Shape: nodeKind = NodeKind.Literal
// Valid: value = "text" (literal)
// Valid: value = 42 (literal with datatype)
// Invalid: value = <http://example.org/value> (IRI)
```

### String Constraints

String constraints validate the content and structure of string literals. They're essential for data quality and format validation.

#### Length Constraints

```kotlin
property("http://example.org/email") {
    minLength = 5
    maxLength = 100
}
```

**What they do:**
- `minLength`: Ensures the string has at least N characters (after trimming, depending on validator)
- `maxLength`: Ensures the string has at most N characters
- Applied to the lexical form of the literal (the string value itself)

**Example validation:**
```kotlin
// Shape: minLength = 5, maxLength = 100
// Valid: "hello" (5 characters)
// Valid: "hello world" (11 characters)
// Invalid: "hi" (2 characters, violates minLength)
// Invalid: "a" * 101 (101 characters, violates maxLength)
```

#### Pattern Constraints

Pattern constraints validate strings against regular expressions.

```kotlin
property("http://example.org/email") {
    pattern = "^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$"
    flags = "i"  // Case-insensitive
}
```

**What it does:**
- Validates that the string matches the specified regular expression
- `flags`: Optional regex flags (e.g., "i" for case-insensitive, "m" for multiline)
- Uses XPath 2.0 regex syntax (similar to JavaScript regex)

**Example validation:**
```kotlin
// Shape: pattern = "^[A-Z][a-z]+$"
// Valid: "Alice" (matches pattern)
// Invalid: "alice" (doesn't start with uppercase)
// Invalid: "Alice123" (contains digits)

// With flags = "i" (case-insensitive)
// Valid: "Alice", "alice", "ALICE" (all match)
```

#### Language Constraints

Language constraints validate language-tagged strings.

```kotlin
property("http://example.org/label") {
    languageIn = listOf("en", "fr", "de")
    uniqueLang = true  // Each language tag can appear only once
}
```

**What they do:**
- `languageIn`: Restricts which language tags are allowed (e.g., "en", "fr", "de")
- `uniqueLang`: Ensures each language tag appears at most once per property
- Only applies to language-tagged literals (e.g., `"Hello"@en`)

**Example validation:**
```kotlin
// Shape: languageIn = ["en", "fr"]
// Valid: "Hello"@en
// Valid: "Bonjour"@fr
// Invalid: "Hola"@es (Spanish not in allowed list)

// Shape: uniqueLang = true
// Valid: "Hello"@en, "Bonjour"@fr (different languages)
// Invalid: "Hello"@en, "Hi"@en (same language appears twice)
```

### Numeric Constraints

Numeric constraints validate the value and precision of numeric literals. They ensure numbers fall within acceptable ranges and have appropriate precision.

#### Range Constraints

```kotlin
property("http://example.org/price") {
    datatype = XSD.decimal
    minInclusive = 0.0
    maxInclusive = 1000.0
}

property("http://example.org/score") {
    datatype = XSD.double
    minExclusive = 0.0  // Must be > 0
    maxExclusive = 100.0  // Must be < 100
}
```

**What they do:**
- `minInclusive`: Value must be >= the specified number (inclusive boundary)
- `maxInclusive`: Value must be <= the specified number (inclusive boundary)
- `minExclusive`: Value must be > the specified number (exclusive boundary)
- `maxExclusive`: Value must be < the specified number (exclusive boundary)

**Example validation:**
```kotlin
// Shape: minInclusive = 0.0, maxInclusive = 100.0
// Valid: 0.0, 50.0, 100.0 (all within range, inclusive)
// Invalid: -1.0 (below minimum)
// Invalid: 101.0 (above maximum)

// Shape: minExclusive = 0.0, maxExclusive = 100.0
// Valid: 0.1, 50.0, 99.9 (all within range, exclusive)
// Invalid: 0.0 (equals minimum, but exclusive)
// Invalid: 100.0 (equals maximum, but exclusive)
```

#### Precision Constraints

Precision constraints control the number of digits in numeric values.

```kotlin
property("http://example.org/amount") {
    datatype = XSD.decimal
    totalDigits = 10
    fractionDigits = 2
}
```

**What they do:**
- `totalDigits`: Maximum total number of digits (including both integer and fractional parts)
- `fractionDigits`: Maximum number of digits after the decimal point
- Useful for currency, measurements, and other values requiring specific precision

**Example validation:**
```kotlin
// Shape: totalDigits = 10, fractionDigits = 2
// Valid: 12345678.90 (10 total digits, 2 fractional)
// Valid: 123.45 (5 total digits, 2 fractional)
// Invalid: 12345678901.23 (12 total digits, exceeds totalDigits)
// Invalid: 123.456 (3 fractional digits, exceeds fractionDigits)
```

### Value Constraints

Value constraints restrict property values to specific allowed values or require exact matches.

#### Has Value

The `hasValue` constraint requires that the property has at least one value that exactly matches the specified value.

```kotlin
property("http://example.org/status") {
    hasValue("active")
}

property("http://example.org/type") {
    hasValue(Iri("http://example.org/Type1"))
}

property("http://example.org/count") {
    hasValue(42)
}
```

**What it does:**
- Validates that at least one property value exactly equals the specified value
- Works with literals (strings, numbers) and resources (IRIs)
- Useful for fixed/enum-like values or default values

**Example validation:**
```kotlin
// Shape: hasValue = "active"
// Valid: status = "active" (exact match)
// Valid: status = "active", "inactive" (has the required value)
// Invalid: status = "inactive" (doesn't have "active")
// Invalid: status = "Active" (case-sensitive, not exact match)
```

#### In (Value Set)

The `in` constraint restricts property values to a specific set of allowed values.

```kotlin
property("http://example.org/status") {
    `in`("active", "inactive", "pending")
}

property("http://example.org/priority") {
    `in`(1, 2, 3, 4, 5)
}

property("http://example.org/category") {
    `in`(listOf(
        Iri("http://example.org/Cat1"),
        Iri("http://example.org/Cat2")
    ))
}
```

**What it does:**
- Validates that all property values are members of the specified set
- Each value must exactly match one of the values in the set
- Useful for enumerations and controlled vocabularies

**Example validation:**
```kotlin
// Shape: in = ["active", "inactive", "pending"]
// Valid: status = "active" (in the set)
// Valid: status = "active", "pending" (both in the set)
// Invalid: status = "archived" (not in the set)
// Invalid: status = "Active" (case-sensitive, not exact match)
```

### Value Comparison Constraints

Value comparison constraints validate relationships between different properties of the same resource. They're powerful for expressing business rules that span multiple properties.

#### Less Than / Less Than Or Equals

These constraints ensure that values of one property are less than (or equal to) values of another property.

```kotlin
property("http://example.org/startDate") {
    lessThan("http://example.org/endDate")
}

property("http://example.org/startDate") {
    lessThanOrEquals("http://example.org/endDate")
}
```

**What they do:**
- `lessThan`: For each value of this property, there must be a value of the other property that is greater
- `lessThanOrEquals`: For each value of this property, there must be a value of the other property that is greater or equal
- Works with comparable datatypes (numbers, dates, strings)
- Useful for date ranges, numeric relationships, ordering constraints

**Example validation:**
```kotlin
// Shape: startDate lessThan endDate
// Valid: startDate = 2024-01-01, endDate = 2024-12-31
// Invalid: startDate = 2024-12-31, endDate = 2024-01-01 (start after end)
// Invalid: startDate = 2024-06-15, endDate = 2024-06-15 (equal, not less than)

// Shape: startDate lessThanOrEquals endDate
// Valid: startDate = 2024-01-01, endDate = 2024-12-31
// Valid: startDate = 2024-06-15, endDate = 2024-06-15 (equal is allowed)
```

#### Equals

The `equals` constraint ensures that two properties have the same set of values.

```kotlin
property("http://example.org/name1") {
    equals("http://example.org/name2")
}
```

**What it does:**
- Validates that the value sets of both properties are identical
- Both properties must have exactly the same values (order doesn't matter)
- Useful for ensuring consistency across different property representations

**Example validation:**
```kotlin
// Shape: name1 equals name2
// Valid: name1 = "Alice", name2 = "Alice" (same values)
// Valid: name1 = ["Alice", "Bob"], name2 = ["Bob", "Alice"] (same set)
// Invalid: name1 = "Alice", name2 = "Bob" (different values)
// Invalid: name1 = "Alice", name2 = ["Alice", "Bob"] (different sets)
```

#### Disjoint

The `disjoint` constraint ensures that two properties have no values in common.

```kotlin
property("http://example.org/type1") {
    disjoint("http://example.org/type2")
}
```

**What it does:**
- Validates that the value sets of both properties have no overlap
- No value can appear in both properties
- Useful for mutually exclusive categories or types

**Example validation:**
```kotlin
// Shape: type1 disjoint type2
// Valid: type1 = "A", type2 = "B" (no overlap)
// Valid: type1 = ["A", "B"], type2 = ["C", "D"] (no overlap)
// Invalid: type1 = "A", type2 = "A" (same value in both)
// Invalid: type1 = ["A", "B"], type2 = ["B", "C"] (B appears in both)
```

### Qualified Value Shapes

Qualified value shapes allow you to specify constraints that must be satisfied by a certain number of values, rather than all values. This is useful for properties that can have multiple values where only some need to meet specific criteria.

```kotlin
property("http://example.org/review") {
    qualifiedValueShape {
        property("http://example.org/rating") {
            minInclusive = 1.0
            maxInclusive = 5.0
        }
    }
    qualifiedMinCount = 1
    qualifiedMaxCount = 10
}

// Or with a shape reference
property("http://example.org/review") {
    qualifiedValueShape("http://example.org/ReviewShape")
    qualifiedMinCount = 1
    qualifiedValueShapesDisjoint = true
}
```

**What it does:**
- `qualifiedValueShape`: Defines a shape that some (not necessarily all) values must conform to
- `qualifiedMinCount`: Minimum number of values that must satisfy the qualified shape
- `qualifiedMaxCount`: Maximum number of values that can satisfy the qualified shape
- `qualifiedValueShapesDisjoint`: If multiple qualified shapes exist, their satisfied value sets must be disjoint

**Example validation:**
```kotlin
// Shape: review with qualifiedValueShape (rating 1-5), qualifiedMinCount = 1
// Valid: review = [review1, review2] where review1.rating = 4, review2.rating = 6
//        (at least 1 review has rating 1-5)
// Valid: review = [review1] where review1.rating = 3
//        (exactly 1 review, and it satisfies the shape)
// Invalid: review = [review1, review2] where review1.rating = 6, review2.rating = 7
//          (no reviews satisfy the shape, violates qualifiedMinCount = 1)

// Shape: qualifiedMinCount = 2, qualifiedMaxCount = 3
// Valid: 2-3 values satisfy the qualified shape
// Invalid: Only 1 value satisfies (violates qualifiedMinCount)
// Invalid: 4 values satisfy (violates qualifiedMaxCount)
```

**Use cases:**
- Product reviews: At least 1 review must have a valid rating
- Contact information: At least 1 contact method must be provided
- Qualifications: Between 2-5 values must meet certain criteria

### Node Constraints

Node constraints require that all values of a property conform to a specific shape. This is useful for validating the structure of related resources.

```kotlin
property("http://example.org/child") {
    node {
        property("http://example.org/name") {
            minCount = 1
        }
    }
}

// Or with a shape reference
property("http://example.org/child") {
    node("http://example.org/ChildShape")
}
```

**What it does:**
- Validates that each value of the property (which must be a resource, not a literal) conforms to the specified shape
- The shape is applied to each value independently
- Useful for validating nested object structures

**Example validation:**
```kotlin
// Shape: child node { name minCount = 1 }
// Valid: child = <http://example.org/alice> where alice.name = "Alice"
//        (the child resource conforms to the nested shape)
// Invalid: child = <http://example.org/alice> where alice has no name
//          (the child resource doesn't conform - missing required name)
// Invalid: child = "Alice" (literal, not a resource that can be validated)
```

**Difference from `class` constraint:**
- `class`: Only checks that the resource has the correct `rdf:type`
- `node`: Validates the resource against a full shape with all its constraints

## Logical Constraints

Logical constraints combine multiple shapes or constraints using boolean logic. They allow you to express complex validation rules.

### AND Constraint

The `and` constraint requires that **all** nested constraints must be satisfied. It's useful for requiring multiple conditions simultaneously.

```kotlin
nodeShape("http://example.org/Shape") {
    and {
        property("http://example.org/name") {
            minCount = 1
        }
        property("http://example.org/email") {
            minCount = 1
        }
    }
}

// Or with shape references
nodeShape("http://example.org/Shape") {
    and(listOf(
        Iri("http://example.org/Shape1"),
        Iri("http://example.org/Shape2")
    ))
}
```

**What it does:**
- Validates that the resource conforms to ALL nested shapes/constraints
- All conditions must be true simultaneously
- Useful for requiring multiple properties or combining shape definitions

**Example validation:**
```kotlin
// Shape: and { name minCount=1, email minCount=1 }
// Valid: resource has name AND email (both present)
// Invalid: resource has name but no email (email constraint fails)
// Invalid: resource has email but no name (name constraint fails)
// Invalid: resource has neither (both constraints fail)
```

### OR Constraint

The `or` constraint requires that **at least one** nested constraint must be satisfied. It's useful for providing alternative validation paths.

```kotlin
nodeShape("http://example.org/Shape") {
    or {
        property("http://example.org/phone") {
            minCount = 1
        }
        property("http://example.org/mobile") {
            minCount = 1
        }
    }
}
```

**What it does:**
- Validates that the resource conforms to AT LEAST ONE nested shape/constraint
- Any one condition being true is sufficient
- Useful for optional alternatives or flexible validation rules

**Example validation:**
```kotlin
// Shape: or { phone minCount=1, mobile minCount=1 }
// Valid: resource has phone (first condition satisfied)
// Valid: resource has mobile (second condition satisfied)
// Valid: resource has both (both conditions satisfied)
// Invalid: resource has neither phone nor mobile (no condition satisfied)
```

### XONE Constraint

The `xone` (exactly one) constraint requires that **exactly one** nested constraint must be satisfied. It's useful for mutually exclusive options.

```kotlin
nodeShape("http://example.org/Shape") {
    xone {
        property("http://example.org/option1") {
            minCount = 1
        }
        property("http://example.org/option2") {
            minCount = 1
        }
    }
}
```

**What it does:**
- Validates that the resource conforms to EXACTLY ONE nested shape/constraint
- One and only one condition must be true
- Useful for mutually exclusive choices or alternative representations

**Example validation:**
```kotlin
// Shape: xone { option1 minCount=1, option2 minCount=1 }
// Valid: resource has option1 but not option2 (exactly one)
// Valid: resource has option2 but not option1 (exactly one)
// Invalid: resource has both option1 and option2 (more than one)
// Invalid: resource has neither (less than one)
```

### NOT Constraint

The `not` constraint requires that the nested constraint must **not** be satisfied. It's useful for prohibiting certain patterns.

```kotlin
nodeShape("http://example.org/Shape") {
    not {
        property("http://example.org/secret") {
            minCount = 1
        }
    }
}

// Or with a shape reference
nodeShape("http://example.org/Shape") {
    not("http://example.org/ForbiddenShape")
}
```

**What it does:**
- Validates that the resource does NOT conform to the nested shape/constraint
- The nested condition must be false
- Useful for prohibiting certain properties or patterns

**Example validation:**
```kotlin
// Shape: not { secret minCount=1 }
// Valid: resource has no secret property (constraint not satisfied, which is good)
// Invalid: resource has secret property (constraint is satisfied, which violates "not")
```

## Property Metadata

Property metadata provides human-readable information about properties. While not used for validation, they're essential for documentation, UI generation, and developer experience.

```kotlin
property("http://example.org/prop") {
    name("Property Name")
    name("Property Name", "en")  // With language tag
    
    description("Property description")
    description("Property description", "en")
    
    order(1)  // Display order
    
    group("http://example.org/group")  // Group for UI organization
}
```

**What they do:**
- `name`: Human-readable name for the property (can be multilingual)
- `description`: Detailed description of what the property represents (can be multilingual)
- `order`: Numeric value indicating display/processing order (lower numbers first)
- `group`: IRI identifying a group for organizing related properties in UIs

**Use cases:**
- **Documentation**: Generate human-readable documentation from shapes
- **UI Generation**: Create forms and interfaces automatically
- **Developer Tools**: Provide hints and descriptions in IDEs
- **Internationalization**: Support multiple languages for names and descriptions

## Messages and Severity

### Custom Messages

Custom messages provide human-readable descriptions of validation failures. They help users understand what went wrong and how to fix it.

```kotlin
nodeShape("http://example.org/Shape") {
    message("This is a custom validation message")
    message("French message", "fr")
    
    property("http://example.org/prop") {
        message("Property-specific message")
    }
}
```

**What it does:**
- Provides custom error messages when validation fails
- Can be multilingual (specify language tag)
- Overrides default validator messages
- Property-level messages apply to that specific property's violations

**Example:**
```kotlin
// Shape: name minCount=1, message="Name is required"
// When validation fails: "Name is required" (custom message)
// Instead of default: "Property has 0 values, but minimum is 1"
```

### Severity Levels

Severity levels indicate how serious a validation failure is. They help prioritize issues and provide different handling based on severity.

```kotlin
nodeShape("http://example.org/Shape") {
    severity(Severity.Violation)  // Default
    severity(Severity.Warning)
    severity(Severity.Info)
    
    property("http://example.org/prop") {
        severity(Severity.Warning)
    }
}
```

**What they do:**
- `Severity.Violation`: Critical error - data doesn't conform (default)
- `Severity.Warning`: Non-critical issue - data may be problematic but not invalid
- `Severity.Info`: Informational - provides guidance without indicating an error

**Use cases:**
- **Violation**: Required fields, type mismatches, critical business rules
- **Warning**: Deprecated properties, best practice violations, data quality issues
- **Info**: Suggestions, hints, optional recommendations

**Example:**
```kotlin
// Shape: deprecatedProp severity=Warning
// Result: Validation reports warning but doesn't fail
// Use case: Mark deprecated properties without breaking existing data
```

## Prefix Management

You can declare prefixes for QName resolution:

```kotlin
val shapesGraph = shacl {
    prefixes {
        put("ex", "http://example.org/")
        put("foaf", "http://xmlns.com/foaf/0.1/")
    }
    
    nodeShape("ex:PersonShape") {
        targetClass("foaf:Person")
        property("foaf:name") {
            minCount = 1
        }
    }
}
```

Built-in prefixes (rdf, rdfs, owl, sh, xsd) are available automatically.

## Standalone Property Shapes

You can create property shapes that aren't part of a node shape:

```kotlin
val shapesGraph = shacl {
    propertyShape("http://example.org/StandaloneProperty") {
        path = Iri("http://example.org/prop")
        minCount = 1
        datatype = XSD.string
    }
}
```

## Complex Examples

### Complete Person Shape

```kotlin
val personShape = shacl {
    nodeShape("http://example.org/PersonShape") {
        targetClass(FOAF.Person)
        
        property(FOAF.name) {
            minCount = 1
            maxCount = 1
            datatype = XSD.string
            minLength = 1
            maxLength = 100
        }
        
        property(FOAF.email) {
            minCount = 1
            pattern = "^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$"
            flags = "i"
        }
        
        property(FOAF.age) {
            minCount = 0
            maxCount = 1
            datatype = XSD.integer
            minInclusive = 0.0
            maxInclusive = 150.0
        }
        
        property(FOAF.knows) {
            minCount = 0
            `class` = FOAF.Person
        }
    }
}
```

### Complex Validation Rules

```kotlin
val complexShape = shacl {
    nodeShape("http://example.org/ProductShape") {
        targetClass("http://example.org/Product")
        
        // Name is required
        property("http://example.org/name") {
            minCount = 1
            datatype = XSD.string
        }
        
        // Price must be positive
        property("http://example.org/price") {
            minCount = 1
            datatype = XSD.decimal
            minExclusive = 0.0
            totalDigits = 10
            fractionDigits = 2
        }
        
        // Category must be from allowed list
        property("http://example.org/category") {
            minCount = 1
            `in`("Electronics", "Clothing", "Books")
        }
        
        // Reviews must have valid ratings
        property("http://example.org/review") {
            qualifiedValueShape {
                property("http://example.org/rating") {
                    minInclusive = 1.0
                    maxInclusive = 5.0
                }
            }
            qualifiedMinCount = 1
            qualifiedMaxCount = 10
        }
        
        // Either phone or email required
        or {
            property("http://example.org/phone") {
                minCount = 1
            }
            property("http://example.org/email") {
                minCount = 1
            }
        }
    }
}
```

### Nested Structures

```kotlin
val nestedShape = shacl {
    nodeShape("http://example.org/OrderShape") {
        targetClass("http://example.org/Order")
        
        property("http://example.org/item") {
            node {
                targetClass("http://example.org/OrderItem")
                property("http://example.org/product") {
                    minCount = 1
                }
                property("http://example.org/quantity") {
                    minCount = 1
                    datatype = XSD.integer
                    minInclusive = 1.0
                }
            }
        }
    }
}
```

## Integration with Validation

The shapes graph created with the DSL works seamlessly with Kastor's SHACL validation:

```kotlin
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation

// Create shapes using DSL
val shapesGraph = shacl {
    nodeShape("http://example.org/PersonShape") {
        targetClass(FOAF.Person)
        property(FOAF.name) {
            minCount = 1
        }
    }
}

// Create data graph
val dataGraph = Rdf.graph {
    val person = iri("http://example.org/alice")
    person `is` FOAF.Person
    // Missing name - will fail validation
}

// Validate
val validator = ShaclValidation.validator()
val report = validator.validate(dataGraph, shapesGraph)

if (!report.isValid) {
    report.violations.forEach { violation ->
        println("Violation: ${violation.message}")
    }
}
```

## Best Practices

1. **Use IRI constants**: Prefer vocabulary constants (like `FOAF.name`) over string IRIs when possible
2. **Organize with prefixes**: Use prefix declarations for cleaner code
3. **Group related constraints**: Use logical constraints to express complex validation rules
4. **Add metadata**: Use `name()` and `description()` for better documentation
5. **Set appropriate severity**: Use `severity()` to distinguish between errors and warnings
6. **Reuse shapes**: Reference existing shapes instead of duplicating constraints

## Comparison with Manual RDF

### Before (Manual RDF)

```kotlin
val shapesGraph = Rdf.graph {
    val shape = iri("http://example.org/PersonShape")
    val propertyShape = bnode("ageShape")
    
    shape - RDF.type - SHACL.NodeShape
    shape - SHACL.targetClass - FOAF.Person
    shape - SHACL.property - propertyShape
    
    propertyShape - RDF.type - SHACL.PropertyShape
    propertyShape - SHACL.path - FOAF.age
    propertyShape - SHACL.minCount - int(1)
    propertyShape - SHACL.datatype - XSD.integer
    propertyShape - SHACL.minInclusive - int(0)
    propertyShape - SHACL.maxInclusive - int(150)
}
```

### After (SHACL DSL)

```kotlin
val shapesGraph = shacl {
    nodeShape("http://example.org/PersonShape") {
        targetClass(FOAF.Person)
        property(FOAF.age) {
            minCount = 1
            datatype = XSD.integer
            minInclusive = 0.0
            maxInclusive = 150.0
        }
    }
}
```

**Benefits:**
- 60% less code
- More readable
- Type-safe
- Less error-prone

## See Also

- [SHACL Validation Guide](guides/how-to-validate-shacl.md) - How to use SHACL validation
- [SHACL Validation Features](features/shacl-validation.md) - SHACL validation capabilities
- [Compact DSL Guide](compact-dsl-guide.md) - General RDF DSL documentation

