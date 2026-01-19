# Vocabulary Terms Index

This page provides a comprehensive index of all available vocabulary terms organized by vocabulary.

## RDF Vocabulary (`rdf:`)

**Classes:**
- `RDF.Statement` - Statement class
- `RDF.List` - List class
- `RDF.Bag` - Bag class
- `RDF.Seq` - Sequence class
- `RDF.Alt` - Alternative class
- `RDF.Property` - Property class

**Properties:**
- `RDF.type` - Type relationship
- `RDF.subject` - Subject of a statement
- `RDF.predicate` - Predicate of a statement
- `RDF.object` - Object of a statement
- `RDF.first` - First element of a list
- `RDF.rest` - Rest of a list
- `RDF.nil` - Empty list
- `RDF.value` - Value of a property

**Datatypes:**
- `RDF.langString` - Language-tagged string

**Container Membership:**
- `RDF._1` to `RDF._5` - Container membership properties

## XSD Vocabulary (`xsd:`)

**Basic Types:**
- `XSD.string` - String datatype
- `XSD.integer` - Integer datatype
- `XSD.decimal` - Decimal datatype
- `XSD.double` - Double precision floating point
- `XSD.float` - Single precision floating point
- `XSD.boolean` - Boolean datatype

**Date/Time Types:**
- `XSD.date` - Date datatype
- `XSD.time` - Time datatype
- `XSD.dateTime` - Date and time datatype
- `XSD.dateTimeStamp` - Date and time with timezone
- `XSD.gYear` - Year datatype
- `XSD.gYearMonth` - Year and month datatype

**Binary Types:**
- `XSD.base64Binary` - Base64 encoded binary data
- `XSD.hexBinary` - Hexadecimal encoded binary data

**Numeric Types:**
- `XSD.long`, `XSD.int`, `XSD.short`, `XSD.byte`
- `XSD.unsignedLong`, `XSD.unsignedInt`, `XSD.unsignedShort`, `XSD.unsignedByte`
- `XSD.positiveInteger`, `XSD.nonNegativeInteger`
- `XSD.negativeInteger`, `XSD.nonPositiveInteger`

## RDFS Vocabulary (`rdfs:`)

**Classes:**
- `RDFS.Class` - Class definition
- `RDFS.Container` - Container class
- `RDFS.ContainerMembershipProperty` - Container membership property
- `RDFS.Datatype` - Datatype class
- `RDFS.Literal` - Literal class
- `RDFS.Resource` - Resource class

**Properties:**
- `RDFS.subClassOf` - Subclass relationship
- `RDFS.subPropertyOf` - Subproperty relationship
- `RDFS.domain` - Property domain
- `RDFS.range` - Property range
- `RDFS.label` - Human-readable label
- `RDFS.comment` - Human-readable comment
- `RDFS.seeAlso` - Related resource
- `RDFS.isDefinedBy` - Definition source
- `RDFS.member` - Container member

## OWL Vocabulary (`owl:`)

**Classes:**
- `OWL.Class` - OWL class
- `OWL.Thing` - Top class
- `OWL.Nothing` - Bottom class
- `OWL.ObjectProperty` - Object property
- `OWL.DatatypeProperty` - Datatype property
- `OWL.AnnotationProperty` - Annotation property
- `OWL.FunctionalProperty` - Functional property
- `OWL.InverseFunctionalProperty` - Inverse functional property
- `OWL.SymmetricProperty` - Symmetric property
- `OWL.AsymmetricProperty` - Asymmetric property
- `OWL.ReflexiveProperty` - Reflexive property
- `OWL.IrreflexiveProperty` - Irreflexive property
- `OWL.TransitiveProperty` - Transitive property

**Properties:**
- `OWL.sameAs` - Same individual
- `OWL.differentFrom` - Different individual
- `OWL.equivalentClass` - Equivalent class
- `OWL.equivalentProperty` - Equivalent property
- `OWL.inverseOf` - Inverse property
- `OWL.unionOf` - Union of classes
- `OWL.intersectionOf` - Intersection of classes
- `OWL.complementOf` - Complement of class
- `OWL.oneOf` - Enumeration
- `OWL.allValuesFrom` - Universal quantification
- `OWL.someValuesFrom` - Existential quantification
- `OWL.hasValue` - Has value restriction
- `OWL.minCardinality` - Minimum cardinality
- `OWL.maxCardinality` - Maximum cardinality
- `OWL.cardinality` - Exact cardinality

## SHACL Vocabulary (`sh:`)

**Classes:**
- `SHACL.Shape` - Base shape class
- `SHACL.NodeShape` - Node shape
- `SHACL.PropertyShape` - Property shape
- `SHACL.ValidationReport` - Validation report
- `SHACL.ValidationResult` - Validation result
- `SHACL.ConstraintComponent` - Constraint component
- `SHACL.Function` - Function
- `SHACL.Parameter` - Parameter

**Properties:**
- `SHACL.targetClass` - Target class
- `SHACL.targetNode` - Target node
- `SHACL.targetObjectsOf` - Target objects of property
- `SHACL.targetSubjectsOf` - Target subjects of property
- `SHACL.property` - Property constraint
- `SHACL.path` - Property path
- `SHACL.minCount` - Minimum count
- `SHACL.maxCount` - Maximum count
- `SHACL.datatype` - Datatype constraint
- `SHACL.pattern` - Pattern constraint
- `SHACL.message` - Constraint message
- `SHACL.severity` - Constraint severity
- `SHACL.hasValue` - Has value constraint
- `SHACL.in` - In constraint
- `SHACL.equals` - Equals constraint
- `SHACL.disjoint` - Disjoint constraint
- `SHACL.lessThan` - Less than constraint
- `SHACL.lessThanOrEquals` - Less than or equals constraint

## SKOS Vocabulary (`skos:`)

**Classes:**
- `SKOS.Concept` - Concept
- `SKOS.ConceptScheme` - Concept scheme
- `SKOS.Collection` - Collection
- `SKOS.OrderedCollection` - Ordered collection

**Properties:**
- `SKOS.prefLabel` - Preferred label
- `SKOS.altLabel` - Alternative label
- `SKOS.hiddenLabel` - Hidden label
- `SKOS.definition` - Definition
- `SKOS.scopeNote` - Scope note
- `SKOS.example` - Example
- `SKOS.note` - Note
- `SKOS.changeNote` - Change note
- `SKOS.editorialNote` - Editorial note
- `SKOS.historyNote` - History note
- `SKOS.broader` - Broader concept
- `SKOS.narrower` - Narrower concept
- `SKOS.related` - Related concept
- `SKOS.broaderTransitive` - Broader transitive
- `SKOS.narrowerTransitive` - Narrower transitive
- `SKOS.broaderMatch` - Broader match
- `SKOS.narrowerMatch` - Narrower match
- `SKOS.relatedMatch` - Related match
- `SKOS.exactMatch` - Exact match
- `SKOS.closeMatch` - Close match
- `SKOS.inScheme` - In concept scheme
- `SKOS.hasTopConcept` - Has top concept
- `SKOS.topConceptOf` - Top concept of
- `SKOS.member` - Collection member
- `SKOS.memberList` - Collection member list
- `SKOS.notation` - Notation

## DCTERMS Vocabulary (`dcterms:`)

**Classes:**
- `DCTERMS.Agent` - Agent
- `DCTERMS.AgentClass` - Agent class
- `DCTERMS.BibliographicResource` - Bibliographic resource
- `DCTERMS.FileFormat` - File format
- `DCTERMS.Frequency` - Frequency
- `DCTERMS.Jurisdiction` - Jurisdiction
- `DCTERMS.LicenseDocument` - License document
- `DCTERMS.LinguisticSystem` - Linguistic system
- `DCTERMS.Location` - Location
- `DCTERMS.LocationPeriodOrJurisdiction` - Location, period, or jurisdiction
- `DCTERMS.MediaType` - Media type
- `DCTERMS.MediaTypeOrExtent` - Media type or extent
- `DCTERMS.MethodOfAccrual` - Method of accrual
- `DCTERMS.MethodOfInstruction` - Method of instruction
- `DCTERMS.PeriodOfTime` - Period of time
- `DCTERMS.PhysicalMedium` - Physical medium
- `DCTERMS.PhysicalResource` - Physical resource
- `DCTERMS.Policy` - Policy
- `DCTERMS.ProvenanceStatement` - Provenance statement
- `DCTERMS.RightsStatement` - Rights statement
- `DCTERMS.SizeOrDuration` - Size or duration
- `DCTERMS.Standard` - Standard

**Properties:**
- `DCTERMS.title` - Title
- `DCTERMS.creator` - Creator
- `DCTERMS.contributor` - Contributor
- `DCTERMS.publisher` - Publisher
- `DCTERMS.date` - Date
- `DCTERMS.created` - Created
- `DCTERMS.modified` - Modified
- `DCTERMS.issued` - Issued
- `DCTERMS.dateAccepted` - Date accepted
- `DCTERMS.dateCopyrighted` - Date copyrighted
- `DCTERMS.dateSubmitted` - Date submitted
- `DCTERMS.description` - Description
- `DCTERMS.abstract` - Abstract
- `DCTERMS.subject` - Subject
- `DCTERMS.coverage` - Coverage
- `DCTERMS.spatial` - Spatial coverage
- `DCTERMS.temporal` - Temporal coverage
- `DCTERMS.language` - Language
- `DCTERMS.format` - Format
- `DCTERMS.extent` - Extent
- `DCTERMS.identifier` - Identifier
- `DCTERMS.source` - Source
- `DCTERMS.relation` - Relation
- `DCTERMS.isPartOf` - Is part of
- `DCTERMS.hasPart` - Has part
- `DCTERMS.isVersionOf` - Is version of
- `DCTERMS.hasVersion` - Has version
- `DCTERMS.replaces` - Replaces
- `DCTERMS.isReplacedBy` - Is replaced by
- `DCTERMS.requires` - Requires
- `DCTERMS.isRequiredBy` - Is required by
- `DCTERMS.conformsTo` - Conforms to
- `DCTERMS.audience` - Audience
- `DCTERMS.educationLevel` - Education level
- `DCTERMS.mediator` - Mediator
- `DCTERMS.accessRights` - Access rights
- `DCTERMS.rights` - Rights
- `DCTERMS.rightsHolder` - Rights holder
- `DCTERMS.license` - License
- `DCTERMS.bibliographicCitation` - Bibliographic citation
- `DCTERMS.instructionalMethod` - Instructional method
- `DCTERMS.accrualMethod` - Accrual method
- `DCTERMS.accrualPeriodicity` - Accrual periodicity
- `DCTERMS.accrualPolicy` - Accrual policy
- `DCTERMS.available` - Available
- `DCTERMS.valid` - Valid

## FOAF Vocabulary (`foaf:`)

**Classes:**
- `FOAF.Person` - Person
- `FOAF.Agent` - Agent
- `FOAF.Organization` - Organization
- `FOAF.Group` - Group
- `FOAF.Document` - Document
- `FOAF.Image` - Image
- `FOAF.Project` - Project

**Properties:**
- `FOAF.name` - Name
- `FOAF.firstName` - First name
- `FOAF.familyName` - Family name
- `FOAF.givenName` - Given name
- `FOAF.surname` - Surname
- `FOAF.nick` - Nickname
- `FOAF.title` - Title
- `FOAF.age` - Age
- `FOAF.birthday` - Birthday
- `FOAF.gender` - Gender
- `FOAF.homepage` - Homepage
- `FOAF.weblog` - Weblog
- `FOAF.openid` - OpenID
- `FOAF.jabberID` - Jabber ID
- `FOAF.mbox` - Email
- `FOAF.mbox_sha1sum` - Email SHA1 hash
- `FOAF.knows` - Knows
- `FOAF.based_near` - Based near
- `FOAF.currentProject` - Current project
- `FOAF.pastProject` - Past project
- `FOAF.topic` - Topic
- `FOAF.topic_interest` - Topic interest
- `FOAF.primaryTopic` - Primary topic
- `FOAF.made` - Made
- `FOAF.maker` - Maker
- `FOAF.depiction` - Depiction
- `FOAF.depicts` - Depicts
- `FOAF.thumbnail` - Thumbnail
- `FOAF.img` - Image
- `FOAF.logo` - Logo
- `FOAF.member` - Member
- `FOAF.membershipClass` - Membership class
- `FOAF.focus` - Focus
- `FOAF.fundedBy` - Funded by
- `FOAF.theme` - Theme
- `FOAF.schoolHomepage` - School homepage
- `FOAF.workInfoHomepage` - Work info homepage
- `FOAF.workplaceHomepage` - Workplace homepage
- `FOAF.accountName` - Account name
- `FOAF.accountServiceHomepage` - Account service homepage
- `FOAF.holdsAccount` - Holds account
- `FOAF.phone` - Phone
- `FOAF.aimChatID` - AIM chat ID
- `FOAF.skypeID` - Skype ID
- `FOAF.icqChatID` - ICQ chat ID
- `FOAF.yahooChatID` - Yahoo chat ID
- `FOAF.msnChatID` - MSN chat ID
- `FOAF.status` - Status
- `FOAF.publications` - Publications
- `FOAF.geekcode` - Geek code
- `FOAF.dnaChecksum` - DNA checksum
- `FOAF.plan` - Plan
- `FOAF.sha1` - SHA1 hash
- `FOAF.interest` - Interest
- `FOAF.tipjar` - Tip jar
- `FOAF.myersBriggs` - Myers-Briggs type

## Usage Examples

### Finding Terms by Vocabulary

```kotlin
import com.geoknoesis.kastor.rdf.vocab.Vocabularies.*

// Get all FOAF terms
val foafTerms = getTermsByPrefix("foaf")
println("FOAF has ${foafTerms?.size} terms")

// Get all OWL terms
val owlTerms = getTermsByPrefix("owl")
println("OWL has ${owlTerms?.size} terms")
```

### Discovering Term Properties

```kotlin
// Check which vocabulary a term belongs to
val term = iri("http://xmlns.com/foaf/0.1/name")
val vocab = findVocabularyForTerm(term)
println("Term belongs to: ${vocab?.prefix}")

// Get the local name
val localName = getLocalName(term)
println("Local name: $localName")
```

### Custom Vocabulary Creation

```kotlin
object MyVocab : Vocabulary {
    override val namespace: String = "http://example.com/vocab#"
    override val prefix: String = "ex"
    
    val MyClass: Iri by lazy { term("MyClass") }
    val myProperty: Iri by lazy { term("myProperty") }
}

// Add to Vocabularies.all if needed
// Vocabularies.all += MyVocab
```

## Performance Notes

- All terms use lazy initialization (`by lazy`)
- Terms are only created when first accessed
- Unused vocabulary terms consume no memory
- Large vocabularies (like OWL) don't impact startup performance
- Accessing terms multiple times has no performance penalty after first access

## Related Documentation

- [RDF Vocabularies](vocabularies.md) - Comprehensive documentation
- [Vocabularies Quick Reference](vocabularies-quickref.md) - Quick reference card
- [RDF Terms](rdfterms.md) - Core RDF term model
- [Core API](../api/core-api.md) - Main RDF API interfaces




