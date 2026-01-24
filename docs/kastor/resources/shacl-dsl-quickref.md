# SHACL DSL Quick Reference

{% include version-banner.md %}

Quick reference for the SHACL DSL syntax.

## Basic Structure

```kotlin
val shapesGraph = shacl {
    nodeShape("http://example.org/Shape") {
        // Shape configuration
    }
}
```

## Node Shape Targets

```kotlin
nodeShape("Shape") {
    targetClass("http://example.org/Class")
    targetNode("http://example.org/node")
    targetObjectsOf("http://example.org/prop")
    targetSubjectsOf("http://example.org/prop")
}
```

## Property Constraints

### Cardinality
```kotlin
property("prop") {
    minCount = 1
    maxCount = 1
}
```

### Type Constraints
```kotlin
property("prop") {
    datatype = XSD.string
    `class` = FOAF.Person
    nodeKind = NodeKind.IRI
}
```

### String Constraints
```kotlin
property("prop") {
    minLength = 1
    maxLength = 100
    pattern = "^[A-Z].*"
    flags = "i"
    languageIn = listOf("en", "fr")
    uniqueLang = true
}
```

### Numeric Constraints
```kotlin
property("prop") {
    minInclusive = 0.0
    maxInclusive = 100.0
    minExclusive = 0.0
    maxExclusive = 100.0
    totalDigits = 10
    fractionDigits = 2
}
```

### Value Constraints
```kotlin
property("prop") {
    hasValue("value")
    hasValue(42)
    hasValue(Iri("http://example.org/value"))
    `in`("val1", "val2", "val3")
    `in`(1, 2, 3)
    `in`(listOf(Iri("v1"), Iri("v2")))
}
```

### Value Comparison
```kotlin
property("prop1") {
    equals("prop2")
    disjoint("prop2")
    lessThan("prop2")
    lessThanOrEquals("prop2")
}
```

### Qualified Value Shapes
```kotlin
property("prop") {
    qualifiedValueShape {
        property("nested") { minCount = 1 }
    }
    qualifiedValueShape("http://example.org/Shape")
    qualifiedMinCount = 1
    qualifiedMaxCount = 10
    qualifiedValueShapesDisjoint = true
}
```

### Node Constraints
```kotlin
property("prop") {
    node {
        property("nested") { minCount = 1 }
    }
    node("http://example.org/Shape")
}
```

## Logical Constraints

```kotlin
nodeShape("Shape") {
    and {
        property("prop1") { minCount = 1 }
        property("prop2") { minCount = 1 }
    }
    or {
        property("prop1") { minCount = 1 }
        property("prop2") { minCount = 1 }
    }
    xone {
        property("prop1") { minCount = 1 }
        property("prop2") { minCount = 1 }
    }
    not {
        property("prop") { minCount = 1 }
    }
}
```

## Shape Configuration

```kotlin
nodeShape("Shape") {
    closed(true)
    ignoredProperties("prop1", "prop2")
    deactivated(true)
    message("Custom message")
    message("Message", "en")
    severity(Severity.Warning)
}
```

## Property Metadata

```kotlin
property("prop") {
    name("Property Name")
    name("Name", "en")
    description("Description")
    description("Description", "en")
    order(1)
    group("http://example.org/group")
    message("Message")
    severity(Severity.Info)
    deactivated(true)
}
```

## Node Kinds

```kotlin
NodeKind.IRI
NodeKind.BlankNode
NodeKind.Literal
NodeKind.BlankNodeOrIRI
NodeKind.BlankNodeOrLiteral
NodeKind.IRIOrLiteral
```

## Severity Levels

```kotlin
Severity.Violation  // Default
Severity.Warning
Severity.Info
```

## Prefixes

```kotlin
shacl {
    prefixes {
        put("ex", "http://example.org/")
    }
    nodeShape("ex:Shape") {
        targetClass("ex:Class")
    }
}
```

## Standalone Property Shapes

```kotlin
shacl {
    propertyShape("http://example.org/Property") {
        path = Iri("http://example.org/prop")
        minCount = 1
    }
}
```

## See Also

- [SHACL DSL Guide](../api/shacl-dsl-guide.md) - Complete documentation
- [How to Create SHACL Shapes](../guides/how-to-create-shacl-shapes.md) - Step-by-step guide


