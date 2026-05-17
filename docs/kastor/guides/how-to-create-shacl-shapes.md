# How to Create SHACL Shapes

{% include version-banner.md %}

> **Documentation mode: How-to guide.** **Explanation:** what SHACL shapes express → [SHACL validation feature](../features/shacl-validation.md), [**Glossary**](../concepts/glossary.md) (**shape**, **focus node**). **Reference:** full DSL surface → [SHACL DSL Guide](../api/shacl-dsl-guide.md).

## Problem

- Author **SHACL shapes** graphs with the Kotlin **`shacl { }`** DSL instead of hand-writing RDF triples: declare **node shapes**, attach **property shapes**, then validate **data** graphs.

## Prerequisites

- **`rdf-core`** plus **`rdf-shacl-dsl`** (`com.geoknoesis.kastor:rdf-shacl-dsl`) — the **`shacl { }`** / **`Rdf.shacl`** DSL lives there (**`api`** **`sparql-lang`** for embedded SPARQL constraints), not in **`rdf-core`** alone.
- Add **`shacl-validation`** when you call **`ShaclValidation.validator()`** (coordinates **`com.geoknoesis.kastor:shacl-validation:0.2.0`**, aligned with your BOM).

## Steps

### Step 1: Declare a node shape with property constraints

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.XSD

val shapesGraph = shacl {
    nodeShape("http://example.org/PersonShape") {
        targetClass(FOAF.Person)

        property(FOAF.name) {
            minCount = 1
            datatype = XSD.string
        }
    }
}
```

### Step 2: Tighten constraints on properties

```kotlin
val personShapes = shacl {
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
            minCount = 0
            maxCount = 1
            pattern = "^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$"
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

### Step 3: Reuse common constraint patterns

#### Required vs optional properties

```kotlin
shacl {
    nodeShape("http://example.org/Shape") {
        property("http://example.org/required") {
            minCount = 1
        }

        property("http://example.org/optional") {
            minCount = 0
        }

        property("http://example.org/multiple") {
            minCount = 0
            // maxCount = null means unlimited
        }
    }
}
```

#### String validation

```kotlin
property("http://example.org/email") {
    minLength = 5
    maxLength = 100
    pattern = "^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$"
    flags = "i"  // Case-insensitive
}
```

#### Numeric ranges

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

#### Value sets

```kotlin
property("http://example.org/status") {
    `in`("active", "inactive", "pending")
}

property("http://example.org/priority") {
    `in`(1, 2, 3, 4, 5)
}
```

### Step 4: Validate data against your shapes

```kotlin
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation

val shapesGraph = shacl {
    nodeShape("http://example.org/PersonShape") {
        targetClass(FOAF.Person)
        property(FOAF.name) {
            minCount = 1
        }
    }
}

val dataGraph = Rdf.graph {
    val person = iri("http://example.org/alice")
    person `is` FOAF.Person
    // Missing name — should fail validation
}

val validator = ShaclValidation.validator()
val report = validator.validate(dataGraph, shapesGraph)

if (!report.isValid) {
    report.violations.forEach { violation ->
        println("Violation: ${violation.message}")
    }
}
```

## Validation

Expect **`report.isValid == false`** when mandatory properties are missing and **`true`** once the data graph satisfies every declared constraint.

## Troubleshooting

- **DSL vs manual RDF:** Prefer **`shacl { }`** for readability; drop to triple builders only when the DSL lacks a niche constraint—see **Reference:** [SHACL DSL Guide](../api/shacl-dsl-guide.md).
- **Validator dependency:** Runtime validation requires the **`shacl-validation`** artifact on the classpath ([How to Validate with SHACL](how-to-validate-shacl.md)).

## Related

- [SHACL DSL Guide](../api/shacl-dsl-guide.md)
- [How to Validate with SHACL](how-to-validate-shacl.md)
- [SHACL Validation Features](../features/shacl-validation.md)
- [How to Check Ontology Quality](how-to-ontology-quality.md)
