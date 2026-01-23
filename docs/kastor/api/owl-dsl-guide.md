# OWL DSL Guide

{% include version-banner.md %}

The OWL DSL provides a type-safe, natural language syntax for creating OWL 2 ontologies in Kotlin. Instead of manually creating RDF triples, you can use an intuitive DSL that makes OWL class expressions, properties, and axioms readable and maintainable.

## Overview

The OWL DSL allows you to define OWL 2 ontologies using Kotlin code that reads like natural language. It supports **OWL 2 constructs**, including classes, properties, restrictions, class expressions, and individuals. The DSL generates standard RDF that works with any OWL-compliant reasoner.

### Benefits

- **Type-safe**: Compile-time validation of OWL constructs
- **Readable**: Natural language syntax that's easy to understand
- **Maintainable**: Less boilerplate than manual RDF triple creation
- **Complete**: Supports OWL 2 class expressions, properties, and restrictions
- **Standard**: Generates standard RDF/Turtle that works with any OWL reasoner

## Quick Start

### Basic Example

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.dsl.owl
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.vocab.XSD

val ontology = owl {
    prefix("ex", "http://example.org/")
    
    ontology("ex:MyOntology") {
        versionInfo("1.0")
    }
    
    `class`("ex:Person") {
        label("Person", "en")
        subClassOf("ex:Animal")
    }
    
    objectProperty("ex:hasParent") {
        domain("ex:Person")
        range("ex:Person")
        transitive()
    }
    
    dataProperty("ex:age") {
        domain("ex:Person")
        range(XSD.integer)
        functional()
    }
    
    individual("ex:alice") {
        `is`("ex:Person")
        property("ex:age", 30)
    }
}
```

## Ontologies

### Basic Ontology Definition

```kotlin
val ontology = owl {
    ontology("http://example.org/MyOntology") {
        versionInfo("1.0")
    }
}
```

### Versioning

OWL supports ontology versioning with several properties:

```kotlin
ontology("http://example.org/MyOntology") {
    versionInfo("1.0")
    versionIRI("http://example.org/MyOntology/1.0")
    priorVersion("http://example.org/MyOntology/0.9")
    backwardCompatibleWith("http://example.org/MyOntology/0.9")
    incompatibleWith("http://example.org/OldOntology")
}
```

### Imports

Import other ontologies:

```kotlin
ontology("http://example.org/MyOntology") {
    imports("http://example.org/OtherOntology")
    imports("http://example.org/AnotherOntology")
}
```

## Classes

### Basic Class Definition

```kotlin
val ontology = owl {
    `class`("http://example.org/Person") {
        label("Person", "en")
        comment("A human being", "en")
    }
}
```

### Class Hierarchies

Define class hierarchies using `subClassOf`:

```kotlin
`class`("http://example.org/Person") {
    subClassOf("http://example.org/Animal")
}
```

### Equivalent Classes

Declare that two classes are equivalent:

```kotlin
`class`("http://example.org/Human") {
    equivalentClass("http://example.org/Person")
}
```

### Disjoint Classes

Declare that classes are mutually exclusive:

```kotlin
`class`("http://example.org/Person") {
    disjointWith("http://example.org/Animal")
}
```

### Complement Classes

Define a class as the complement of another:

```kotlin
`class`("http://example.org/NonPerson") {
    complementOf("http://example.org/Person")
}
```

## Class Expressions

OWL supports complex class expressions that allow you to define classes in terms of other classes.

### Union of Classes

Define a class as the union of multiple classes:

```kotlin
`class`("http://example.org/Pet") {
    equivalentClass {
        unionOf("http://example.org/Dog", "http://example.org/Cat")
    }
}
```

### Intersection of Classes

Define a class as the intersection of multiple classes:

```kotlin
`class`("http://example.org/Student") {
    equivalentClass {
        intersectionOf("http://example.org/Person", "http://example.org/Enrolled")
    }
}
```

### Enumeration (oneOf)

Define a class as an enumeration of specific individuals:

```kotlin
`class`("http://example.org/Status") {
    equivalentClass {
        oneOf("http://example.org/Active", "http://example.org/Inactive", "http://example.org/Pending")
    }
}
```

### Complement

Define a class as the complement of another class:

```kotlin
`class`("http://example.org/NonPerson") {
    equivalentClass {
        complementOf("http://example.org/Person")
    }
}
```

## Restrictions

Restrictions allow you to define classes based on property constraints.

### All Values From

All values of a property must be instances of a specific class:

```kotlin
`class`("http://example.org/Person") {
    equivalentClass {
        restriction("http://example.org/hasParent") {
            allValuesFrom("http://example.org/Person")
        }
    }
}
```

### Some Values From

At least one value of a property must be an instance of a specific class:

```kotlin
`class`("http://example.org/Person") {
    equivalentClass {
        restriction("http://example.org/hasChild") {
            someValuesFrom("http://example.org/Person")
        }
    }
}
```

### Has Value

A property must have a specific value:

```kotlin
`class`("http://example.org/ActivePerson") {
    equivalentClass {
        restriction("http://example.org/status") {
            hasValue(string("active"))
        }
    }
}
```

### Cardinality Constraints

Specify exact, minimum, or maximum cardinality:

```kotlin
`class`("http://example.org/Person") {
    equivalentClass {
        restriction("http://example.org/hasName") {
            cardinality(1)  // Exactly one
        }
    }
}

`class`("http://example.org/Person") {
    equivalentClass {
        restriction("http://example.org/hasEmail") {
            minCardinality(0)  // At least zero
            maxCardinality(5)  // At most five
        }
    }
}
```

### Qualified Cardinality

Cardinality constraints with a specific class:

```kotlin
`class`("http://example.org/Person") {
    equivalentClass {
        restriction("http://example.org/hasParent") {
            qualifiedCardinality(2, "http://example.org/Person")
            // Exactly 2 parents, both of type Person
        }
    }
}

`class`("http://example.org/Person") {
    equivalentClass {
        restriction("http://example.org/hasParent") {
            minQualifiedCardinality(1, "http://example.org/Person")
            maxQualifiedCardinality(2, "http://example.org/Person")
        }
    }
}
```

### Has Self

A property must relate an individual to itself:

```kotlin
`class`("http://example.org/SelfAware") {
    equivalentClass {
        restriction("http://example.org/knows") {
            hasSelf(true)
        }
    }
}
```

## Object Properties

### Basic Object Property

```kotlin
objectProperty("http://example.org/hasParent") {
    label("has parent", "en")
    domain("http://example.org/Person")
    range("http://example.org/Person")
}
```

### Property Characteristics

OWL supports various property characteristics:

#### Functional Property

A property that has at most one value per subject:

```kotlin
objectProperty("http://example.org/hasSSN") {
    functional()
}
```

#### Inverse Functional Property

A property whose inverse is functional:

```kotlin
objectProperty("http://example.org/hasSSN") {
    inverseFunctional()
}
```

#### Transitive Property

A property that is transitive:

```kotlin
objectProperty("http://example.org/ancestorOf") {
    transitive()
}
```

#### Symmetric Property

A property that is symmetric:

```kotlin
objectProperty("http://example.org/knows") {
    symmetric()
}
```

#### Asymmetric Property

A property that is asymmetric:

```kotlin
objectProperty("http://example.org/hasChild") {
    asymmetric()
}
```

#### Reflexive Property

A property that is reflexive:

```kotlin
objectProperty("http://example.org/relatedTo") {
    reflexive()
}
```

#### Irreflexive Property

A property that is irreflexive:

```kotlin
objectProperty("http://example.org/hasParent") {
    irreflexive()
}
```

### Property Relationships

#### Inverse Of

Declare that two properties are inverses:

```kotlin
objectProperty("http://example.org/hasParent") {
    inverseOf("http://example.org/hasChild")
}
```

#### Equivalent Property

Declare that two properties are equivalent:

```kotlin
objectProperty("http://example.org/hasParent") {
    equivalentProperty("http://example.org/parentOf")
}
```

#### Property Disjoint With

Declare that two properties are disjoint:

```kotlin
objectProperty("http://example.org/hasParent") {
    propertyDisjointWith("http://example.org/hasChild")
}
```

### Property Chain Axioms

Define a property as a chain of other properties:

```kotlin
objectProperty("http://example.org/hasGrandparent") {
    propertyChainAxiom("http://example.org/hasParent", "http://example.org/hasParent")
    // hasGrandparent is equivalent to hasParent o hasParent
}
```

## Datatype Properties

### Basic Datatype Property

```kotlin
dataProperty("http://example.org/age") {
    label("Age", "en")
    domain("http://example.org/Person")
    range(XSD.integer)
}
```

### Functional Datatype Property

```kotlin
dataProperty("http://example.org/age") {
    functional()  // A person can have at most one age
}
```

## Annotation Properties

Define annotation properties for metadata:

```kotlin
annotationProperty("http://example.org/comment") {
    label("Comment", "en")
    subPropertyOf(RDFS.comment)
}
```

## Individuals

### Basic Individual

```kotlin
individual("http://example.org/alice") {
    `is`("http://example.org/Person")
}
```

### Individual Relationships

#### Same As

Declare that two individuals are the same:

```kotlin
individual("http://example.org/alice") {
    sameAs("http://example.org/aliceSmith")
}
```

#### Different From

Declare that two individuals are different:

```kotlin
individual("http://example.org/alice") {
    differentFrom("http://example.org/bob")
}
```

### Property Assertions

Add property values to individuals:

```kotlin
individual("http://example.org/alice") {
    `is`("http://example.org/Person")
    property("http://example.org/age", 30)
    property("http://example.org/name", "Alice")
    property("http://example.org/hasParent", Iri("http://example.org/bob"))
}
```

## Prefixes and QNames

### Single Prefix

```kotlin
val ontology = owl {
    prefix("ex", "http://example.org/")
    
    `class`("ex:Person") {
        label("Person")
    }
}
```

### Multiple Prefixes

```kotlin
val ontology = owl {
    prefixes {
        put("ex", "http://example.org/")
        put("foaf", "http://xmlns.com/foaf/0.1/")
        put("owl", "http://www.w3.org/2002/07/owl#")
    }
    
    `class`("ex:Person") {
        subClassOf("foaf:Person")
    }
}
```

## Complete Example

Here's a complete OWL ontology example:

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.dsl.owl
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.XSD

val ontology = owl {
    prefix("ex", "http://example.org/")
    
    // Define ontology
    ontology("ex:MyOntology") {
        versionInfo("1.0")
        imports("http://example.org/OtherOntology")
    }
    
    // Define classes
    `class`("ex:Person") {
        label("Person", "en")
        comment("A human being", "en")
        subClassOf("ex:Animal")
        equivalentClass {
            intersectionOf("ex:Animal", "ex:HasName")
        }
    }
    
    `class`("ex:Student") {
        label("Student", "en")
        subClassOf("ex:Person")
        equivalentClass {
            intersectionOf("ex:Person", "ex:Enrolled")
        }
    }
    
    // Define object properties
    objectProperty("ex:hasParent") {
        label("has parent", "en")
        domain("ex:Person")
        range("ex:Person")
        inverseOf("ex:hasChild")
        transitive()
        irreflexive()
    }
    
    objectProperty("ex:hasChild") {
        label("has child", "en")
        domain("ex:Person")
        range("ex:Person")
    }
    
    objectProperty("ex:hasGrandparent") {
        label("has grandparent", "en")
        propertyChainAxiom("ex:hasParent", "ex:hasParent")
    }
    
    objectProperty("ex:knows") {
        label("knows", "en")
        domain("ex:Person")
        range("ex:Person")
        symmetric()
    }
    
    // Define datatype properties
    dataProperty("ex:age") {
        label("Age", "en")
        domain("ex:Person")
        range(XSD.integer)
        functional()
    }
    
    dataProperty("ex:name") {
        label("Name", "en")
        domain("ex:Person")
        range(XSD.string)
        functional()
    }
    
    // Define individuals
    individual("ex:alice") {
        `is`("ex:Person")
        property("ex:age", 30)
        property("ex:name", "Alice")
        property("ex:hasParent", Iri("ex:bob"))
    }
    
    individual("ex:bob") {
        `is`("ex:Person")
        property("ex:age", 50)
        property("ex:name", "Bob")
    }
}
```

## Advanced Usage

### Complex Class Expressions

Combine multiple class expressions:

```kotlin
`class`("ex:GraduateStudent") {
    equivalentClass {
        intersectionOf(
            "ex:Student",
            restriction("ex:hasDegree") {
                someValuesFrom("ex:Degree")
            }
        )
    }
}
```

### Multiple Restrictions

Combine multiple restrictions:

```kotlin
`class`("ex:Person") {
    equivalentClass {
        intersectionOf(
            restriction("ex:hasName") {
                cardinality(1)
            },
            restriction("ex:hasEmail") {
                minCardinality(0)
                maxCardinality(5)
            },
            restriction("ex:hasParent") {
                minQualifiedCardinality(0, "ex:Person")
                maxQualifiedCardinality(2, "ex:Person")
            }
        )
    }
}
```

### Direct Triple Addition

For advanced use cases, you can add triples directly:

```kotlin
val ontology = owl {
    val person = Iri("http://example.org/Person")
    triple(person, RDF.type, OWL.Class)
    triple(person, RDFS.label, string("Person"))
}
```

### Building the Graph

The `owl` function returns a `MutableRdfGraph` that you can use with any RDF system:

```kotlin
val ontology = owl {
    `class`("http://example.org/Person") {
        label("Person")
    }
}

// Use the graph
val triples = ontology.getTriples()
val repo = Rdf.memory()
repo.defaultGraph.addAll(ontology.getTriples())
```

## Best Practices

### 1. Use Prefixes

Always use prefixes to make your code more readable:

```kotlin
// Good
owl {
    prefix("ex", "http://example.org/")
    `class`("ex:Person") { ... }
}

// Less readable
owl {
    `class`("http://example.org/Person") { ... }
}
```

### 2. Provide Labels and Comments

Always provide labels and comments for better documentation:

```kotlin
`class`("ex:Person") {
    label("Person", "en")
    comment("A human being in our domain model", "en")
}
```

### 3. Organize by Type

Group related definitions together:

```kotlin
owl {
    // Ontology definition first
    ontology("ex:MyOntology") { ... }
    
    // Then classes
    `class`("ex:Person") { ... }
    `class`("ex:Animal") { ... }
    
    // Then object properties
    objectProperty("ex:hasParent") { ... }
    
    // Then datatype properties
    dataProperty("ex:age") { ... }
    
    // Finally individuals
    individual("ex:alice") { ... }
}
```

### 4. Use Class Expressions for Complex Definitions

Use class expressions to define classes in terms of other classes:

```kotlin
// Good: Clear and maintainable
`class`("ex:Student") {
    equivalentClass {
        intersectionOf("ex:Person", "ex:Enrolled")
    }
}

// Less clear: Manual triple creation
```

### 5. Use Property Characteristics Appropriately

Choose the right property characteristics for your domain:

```kotlin
// Functional: At most one value
dataProperty("ex:age") {
    functional()
}

// Transitive: If A hasParent B and B hasParent C, then A hasParent C
objectProperty("ex:ancestorOf") {
    transitive()
}

// Symmetric: If A knows B, then B knows A
objectProperty("ex:knows") {
    symmetric()
}
```

## API Reference

### Top-Level Function

- **`owl(block: OwlDsl.() -> Unit): MutableRdfGraph`** - Creates an OWL ontology graph

### OwlDsl Methods

- **`prefix(name: String, namespace: String)`** - Add a single prefix mapping
- **`prefixes(configure: MutableMap<String, String>.() -> Unit)`** - Configure multiple prefixes
- **`ontology(ontologyIri: String, configure: OwlOntologyDsl.() -> Unit)`** - Define an OWL ontology
- **`class(classIri: String, configure: OwlClassDsl.() -> Unit)`** - Define an OWL class
- **`objectProperty(propertyIri: String, configure: OwlObjectPropertyDsl.() -> Unit)`** - Define an OWL object property
- **`dataProperty(propertyIri: String, configure: OwlDataPropertyDsl.() -> Unit)`** - Define an OWL datatype property
- **`annotationProperty(propertyIri: String, configure: OwlAnnotationPropertyDsl.() -> Unit)`** - Define an OWL annotation property
- **`individual(individualIri: String, configure: OwlIndividualDsl.() -> Unit)`** - Define an OWL named individual
- **`triple(subject: RdfResource, predicate: Iri, obj: RdfTerm)`** - Add a direct triple

### OwlOntologyDsl Methods

- **`versionInfo(value: String)`** - Add owl:versionInfo
- **`versionIRI(iri: Iri)`** / **`versionIRI(iriString: String)`** - Add owl:versionIRI
- **`imports(iri: Iri)`** / **`imports(iriString: String)`** - Add owl:imports
- **`imports(vararg iris: Iri)`** / **`imports(vararg iriStrings: String)`** - Add multiple imports
- **`priorVersion(iri: Iri)`** / **`priorVersion(iriString: String)`** - Add owl:priorVersion
- **`backwardCompatibleWith(iri: Iri)`** / **`backwardCompatibleWith(iriString: String)`** - Add owl:backwardCompatibleWith
- **`incompatibleWith(iri: Iri)`** / **`incompatibleWith(iriString: String)`** - Add owl:incompatibleWith

### OwlClassDsl Methods

- **`label(value: String, language: String? = null)`** - Add an rdfs:label
- **`comment(value: String, language: String? = null)`** - Add an rdfs:comment
- **`subClassOf(superClass: RdfResource)`** / **`subClassOf(superClassIri: String)`** - Declare subclass relationship
- **`equivalentClass(otherClass: RdfResource)`** / **`equivalentClass(otherClassIri: String)`** - Declare equivalent class
- **`equivalentClass(block: OwlClassExpressionBuilder.() -> RdfResource)`** - Declare equivalent class expression
- **`disjointWith(otherClass: RdfResource)`** / **`disjointWith(otherClassIri: String)`** - Declare disjoint class
- **`complementOf(otherClass: RdfResource)`** / **`complementOf(otherClassIri: String)`** - Declare complement class

### OwlClassExpressionBuilder Methods

- **`unionOf(vararg classes: RdfResource)`** / **`unionOf(vararg classIris: String)`** - Create union class expression
- **`intersectionOf(vararg classes: RdfResource)`** / **`intersectionOf(vararg classIris: String)`** - Create intersection class expression
- **`complementOf(classResource: RdfResource)`** / **`complementOf(classIri: String)`** - Create complement class expression
- **`oneOf(vararg individuals: RdfResource)`** / **`oneOf(vararg individualIris: String)`** - Create enumeration class expression
- **`restriction(property: RdfResource, configure: OwlRestrictionBuilder.() -> Unit)`** / **`restriction(propertyIri: String, configure: OwlRestrictionBuilder.() -> Unit)`** - Create restriction class expression

### OwlRestrictionBuilder Methods

- **`allValuesFrom(classResource: RdfResource)`** / **`allValuesFrom(classIri: String)`** - Add allValuesFrom constraint
- **`someValuesFrom(classResource: RdfResource)`** / **`someValuesFrom(classIri: String)`** - Add someValuesFrom constraint
- **`hasValue(value: RdfTerm)`** - Add hasValue constraint
- **`cardinality(n: Int)`** - Add cardinality constraint
- **`minCardinality(n: Int)`** - Add minCardinality constraint
- **`maxCardinality(n: Int)`** - Add maxCardinality constraint
- **`qualifiedCardinality(n: Int, classResource: RdfResource)`** / **`qualifiedCardinality(n: Int, classIri: String)`** - Add qualifiedCardinality constraint
- **`minQualifiedCardinality(n: Int, classResource: RdfResource)`** / **`minQualifiedCardinality(n: Int, classIri: String)`** - Add minQualifiedCardinality constraint
- **`maxQualifiedCardinality(n: Int, classResource: RdfResource)`** / **`maxQualifiedCardinality(n: Int, classIri: String)`** - Add maxQualifiedCardinality constraint
- **`hasSelf(value: Boolean)`** - Add hasSelf constraint

### OwlObjectPropertyDsl Methods

- **`label(value: String, language: String? = null)`** - Add an rdfs:label
- **`comment(value: String, language: String? = null)`** - Add an rdfs:comment
- **`domain(domainClass: RdfResource)`** / **`domain(domainClassIri: String)`** - Declare domain
- **`range(rangeClass: RdfResource)`** / **`range(rangeClassIri: String)`** - Declare range
- **`subPropertyOf(superProperty: RdfResource)`** / **`subPropertyOf(superPropertyIri: String)`** - Declare subproperty relationship
- **`equivalentProperty(otherProperty: RdfResource)`** / **`equivalentProperty(otherPropertyIri: String)`** - Declare equivalent property
- **`inverseOf(otherProperty: RdfResource)`** / **`inverseOf(otherPropertyIri: String)`** - Declare inverse property
- **`propertyDisjointWith(otherProperty: RdfResource)`** / **`propertyDisjointWith(otherPropertyIri: String)`** - Declare disjoint property
- **`functional()`** - Declare functional property
- **`inverseFunctional()`** - Declare inverse functional property
- **`transitive()`** - Declare transitive property
- **`symmetric()`** - Declare symmetric property
- **`asymmetric()`** - Declare asymmetric property
- **`reflexive()`** - Declare reflexive property
- **`irreflexive()`** - Declare irreflexive property
- **`propertyChainAxiom(chain: List<RdfResource>)`** / **`propertyChainAxiom(vararg propertyIris: String)`** - Add property chain axiom

### OwlDataPropertyDsl Methods

- **`label(value: String, language: String? = null)`** - Add an rdfs:label
- **`comment(value: String, language: String? = null)`** - Add an rdfs:comment
- **`domain(domainClass: RdfResource)`** / **`domain(domainClassIri: String)`** - Declare domain
- **`range(datatype: RdfResource)`** / **`range(datatypeIri: String)`** - Declare range
- **`subPropertyOf(superProperty: RdfResource)`** / **`subPropertyOf(superPropertyIri: String)`** - Declare subproperty relationship
- **`equivalentProperty(otherProperty: RdfResource)`** / **`equivalentProperty(otherPropertyIri: String)`** - Declare equivalent property
- **`propertyDisjointWith(otherProperty: RdfResource)`** / **`propertyDisjointWith(otherPropertyIri: String)`** - Declare disjoint property
- **`functional()`** - Declare functional property

### OwlAnnotationPropertyDsl Methods

- **`label(value: String, language: String? = null)`** - Add an rdfs:label
- **`comment(value: String, language: String? = null)`** - Add an rdfs:comment
- **`subPropertyOf(superProperty: RdfResource)`** / **`subPropertyOf(superPropertyIri: String)`** - Declare subproperty relationship

### OwlIndividualDsl Methods

- **`is(classResource: RdfResource)`** / **`is(classIri: String)`** - Declare type of individual
- **`sameAs(otherIndividual: RdfResource)`** / **`sameAs(otherIndividualIri: String)`** - Declare same individual
- **`differentFrom(otherIndividual: RdfResource)`** / **`differentFrom(otherIndividualIri: String)`** - Declare different individual
- **`property(predicate: Iri, value: RdfTerm)`** / **`property(predicateIri: String, value: RdfTerm)`** - Add property assertion
- **`property(predicateIri: String, value: String)`** - Add property assertion with string value
- **`property(predicateIri: String, value: Int)`** - Add property assertion with int value

## See Also

- [RDFS DSL Guide](rdfs-dsl-guide.md) - For simpler schema definitions
- [SHACL DSL Guide](shacl-dsl-guide.md) - For data validation
- [RDF Fundamentals](../concepts/rdf-fundamentals.md) - Understanding RDF basics
- [Reasoning Guide](../features/reasoning.md) - Using OWL with reasoners

