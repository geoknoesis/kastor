# Vocabularies Quick Reference

## Import Statements

```kotlin
// Import specific vocabularies
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

// Import all vocabularies
import com.geoknoesis.kastor.rdf.vocab.*
```

## Common RDF Terms

```kotlin
// Core RDF
RDF.type          // rdf:type
RDF.subject       // rdf:subject
RDF.predicate     // rdf:predicate
RDF.object        // rdf:object
RDF.Statement     // rdf:Statement
RDF.List          // rdf:List
RDF.first         // rdf:first
RDF.rest          // rdf:rest
RDF.nil           // rdf:nil
```

## XSD Datatypes

```kotlin
// Basic types
XSD.string        // xsd:string
XSD.integer       // xsd:integer
XSD.decimal       // xsd:decimal
XSD.double        // xsd:double
XSD.float         // xsd:float
XSD.boolean       // xsd:boolean

// Date/time types
XSD.date          // xsd:date
XSD.time          // xsd:time
XSD.dateTime      // xsd:dateTime
XSD.dateTimeStamp // xsd:dateTimeStamp
XSD.gYear         // xsd:gYear
XSD.gYearMonth    // xsd:gYearMonth

// Binary types
XSD.base64Binary  // xsd:base64Binary
XSD.hexBinary     // xsd:hexBinary
```

## RDFS Terms

```kotlin
RDFS.Class                    // rdfs:Class
RDFS.Resource                 // rdfs:Resource
RDFS.Literal                  // rdfs:Literal
RDFS.subClassOf               // rdfs:subClassOf
RDFS.subPropertyOf            // rdfs:subPropertyOf
RDFS.domain                   // rdfs:domain
RDFS.range                    // rdfs:range
RDFS.label                    // rdfs:label
RDFS.comment                  // rdfs:comment
RDFS.seeAlso                  // rdfs:seeAlso
RDFS.isDefinedBy              // rdfs:isDefinedBy
```

## OWL Terms

```kotlin
// Classes
OWL.Class                     // owl:Class
OWL.ObjectProperty            // owl:ObjectProperty
OWL.DatatypeProperty          // owl:DatatypeProperty
OWL.AnnotationProperty        // owl:AnnotationProperty
OWL.Thing                     // owl:Thing
OWL.Nothing                   // owl:Nothing

// Properties
OWL.sameAs                    // owl:sameAs
OWL.differentFrom             // owl:differentFrom
OWL.equivalentClass           // owl:equivalentClass
OWL.equivalentProperty        // owl:equivalentProperty
OWL.inverseOf                 // owl:inverseOf
OWL.transitiveProperty        // owl:transitiveProperty
OWL.symmetricProperty         // owl:symmetricProperty
OWL.functionalProperty        // owl:functionalProperty
OWL.inverseFunctionalProperty // owl:inverseFunctionalProperty
```

## FOAF Terms

```kotlin
// Classes
FOAF.Person                   // foaf:Person
FOAF.Agent                    // foaf:Agent
FOAF.Organization             // foaf:Organization
FOAF.Group                    // foaf:Group
FOAF.Document                 // foaf:Document
FOAF.Image                    // foaf:Image

// Properties
FOAF.name                     // foaf:name
FOAF.firstName                // foaf:firstName
FOAF.familyName               // foaf:familyName
FOAF.givenName                // foaf:givenName
FOAF.surname                  // foaf:surname
FOAF.nick                     // foaf:nick
FOAF.age                      // foaf:age
FOAF.birthday                 // foaf:birthday
FOAF.gender                   // foaf:gender
FOAF.homepage                 // foaf:homepage
FOAF.knows                    // foaf:knows
FOAF.mbox                     // foaf:mbox
FOAF.phone                    // foaf:phone
```

## Dublin Core Terms

```kotlin
// Classes
DCTERMS.Agent                 // dcterms:Agent
DCTERMS.BibliographicResource // dcterms:BibliographicResource
DCTERMS.FileFormat            // dcterms:FileFormat
DCTERMS.Location              // dcterms:Location
DCTERMS.PeriodOfTime          // dcterms:PeriodOfTime

// Properties
DCTERMS.title                 // dcterms:title
DCTERMS.creator               // dcterms:creator
DCTERMS.contributor           // dcterms:contributor
DCTERMS.publisher             // dcterms:publisher
DCTERMS.date                  // dcterms:date
DCTERMS.created               // dcterms:created
DCTERMS.modified              // dcterms:modified
DCTERMS.description           // dcterms:description
DCTERMS.subject               // dcterms:subject
DCTERMS.language              // dcterms:language
DCTERMS.format                // dcterms:format
DCTERMS.identifier            // dcterms:identifier
DCTERMS.rights                // dcterms:rights
DCTERMS.license               // dcterms:license
```

## SKOS Terms

```kotlin
// Classes
SKOS.Concept                  // skos:Concept
SKOS.ConceptScheme            // skos:ConceptScheme
SKOS.Collection               // skos:Collection
SKOS.OrderedCollection        // skos:OrderedCollection

// Properties
SKOS.prefLabel                // skos:prefLabel
SKOS.altLabel                 // skos:altLabel
SKOS.hiddenLabel              // skos:hiddenLabel
SKOS.definition               // skos:definition
SKOS.scopeNote                // skos:scopeNote
SKOS.example                  // skos:example
SKOS.broader                  // skos:broader
SKOS.narrower                 // skos:narrower
SKOS.related                  // skos:related
SKOS.inScheme                 // skos:inScheme
SKOS.hasTopConcept            // skos:hasTopConcept
```

## SHACL Terms

```kotlin
// Classes
SHACL.NodeShape               // sh:NodeShape
SHACL.PropertyShape           // sh:PropertyShape
SHACL.Shape                   // sh:Shape
SHACL.ValidationReport        // sh:ValidationReport
SHACL.ValidationResult        // sh:ValidationResult

// Properties
SHACL.targetClass             // sh:targetClass
SHACL.targetNode              // sh:targetNode
SHACL.property                // sh:property
SHACL.path                    // sh:path
SHACL.minCount                // sh:minCount
SHACL.maxCount                // sh:maxCount
SHACL.datatype                // sh:datatype
SHACL.pattern                 // sh:pattern
SHACL.message                 // sh:message
SHACL.severity                // sh:severity
SHACL.hasValue                // sh:hasValue
SHACL.in                      // sh:in
SHACL.equals                  // sh:equals
SHACL.disjoint                // sh:disjoint
```

## Common Usage Patterns

### Creating Triples

```kotlin
// Basic triple creation
val person = iri("http://example.com/person/123")
val triple = person has RDF.type with FOAF.Person

// Multiple properties
val triples = listOf(
    person has FOAF.name with "John Doe",
    person has FOAF.age with 30,
    person has DCTERMS.created with "2024-01-15"
)
```

### Type Definitions

```kotlin
// Class definition
val personClass = iri("http://example.com/Person")
val triples = listOf(
    personClass has RDF.type with RDFS.Class,
    personClass has RDFS.label with "Person",
    personClass has RDFS.comment with "A human being"
)

// Property definition
val nameProperty = iri("http://example.com/name")
val propertyTriples = listOf(
    nameProperty has RDF.type with RDF.Property,
    nameProperty has RDFS.domain with personClass,
    nameProperty has RDFS.range with XSD.string
)
```

### Validation Constraints

```kotlin
val personShape = iri("http://example.com/PersonShape")
val namePropertyShape = iri("http://example.com/NameProperty")

val constraints = listOf(
    personShape has RDF.type with SHACL.NodeShape,
    personShape has SHACL.targetClass with FOAF.Person,
    namePropertyShape has RDF.type with SHACL.PropertyShape,
    namePropertyShape has SHACL.path with FOAF.name,
    namePropertyShape has SHACL.minCount with 1.toLiteral(),
    namePropertyShape has SHACL.maxCount with 1.toLiteral()
)
```

## Utility Functions

```kotlin
import com.geoknoesis.kastor.rdf.vocab.Vocabularies.*

// Find vocabulary for a term
val vocab = findVocabularyForTerm(someIri)
println(vocab?.prefix) // e.g., "foaf"

// Get local name
val localName = getLocalName(someIri)

// Check if term is known
val isKnown = isKnownTerm(someIri)

// Find by prefix
val foafVocab = findByPrefix("foaf")

// Get all terms
val foafTerms = getTermsByPrefix("foaf")
```

## Performance Tips

```kotlin
// Good: Access terms only when needed
if (needsName) {
    val nameProperty = FOAF.name  // IRI created here
    // Use nameProperty
}

// Good: Cache frequently used terms
object CommonTerms {
    val type = RDF.type
    val label = RDFS.label
    val comment = RDFS.comment
}

// Avoid: Accessing all terms unnecessarily
// This doesn't create objects, but keeps them in scope
```

## Custom Vocabulary

```kotlin
object MyVocab : Vocabulary {
    override val namespace: String = "http://example.com/vocab#"
    override val prefix: String = "ex"
    
    val MyClass: Iri by lazy { term("MyClass") }
    val myProperty: Iri by lazy { term("myProperty") }
}
```




