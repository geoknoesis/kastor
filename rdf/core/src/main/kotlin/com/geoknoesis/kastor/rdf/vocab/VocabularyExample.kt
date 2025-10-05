package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.*

/**
 * Example demonstrating the usage of the vocabulary package.
 * This file shows practical examples of how to work with RDF vocabularies.
 */
object VocabularyExample {
    
    /**
     * Example: Creating a person description using FOAF vocabulary.
     */
    fun createPersonDescription() {
        // Create resources
        val person = iri("http://example.com/person/123")
        val document = iri("http://example.com/document/456")
        
        // Create triples using vocabulary terms
        val triples = listOf(
            // Person is of type FOAF.Person
            RdfTriple(person, RDF.type, FOAF.Person),
            
            // Person properties
            RdfTriple(person, FOAF.name, "John Doe".toLiteral()),
            RdfTriple(person, FOAF.firstName, "John".toLiteral()),
            RdfTriple(person, FOAF.familyName, "Doe".toLiteral()),
            RdfTriple(person, FOAF.age, 30.toLiteral()),
            RdfTriple(person, FOAF.homepage, iri("http://johndoe.com")),
            
            // Person relationships
            RdfTriple(person, FOAF.knows, iri("http://example.com/person/456")),
            RdfTriple(person, FOAF.workplaceHomepage, iri("http://company.com")),
            
            // Document metadata using Dublin Core
            RdfTriple(document, RDF.type, DCTERMS.BibliographicResource),
            RdfTriple(document, DCTERMS.title, "Sample Document".toLiteral()),
            RdfTriple(document, DCTERMS.creator, person),
            RdfTriple(document, DCTERMS.date, "2024-01-15".toLiteral()),
            RdfTriple(document, DCTERMS.description, "A sample document for demonstration".toLiteral()),
            RdfTriple(document, DCTERMS.language, "en".toLiteral())
        )
        
        println("Created ${triples.size} triples for person description")
        triples.forEach { triple ->
            println("  ${triple.subject} ${triple.predicate} ${triple.obj}")
        }
    }
    
    /**
     * Example: Working with vocabulary metadata.
     */
    fun demonstrateVocabularyFeatures() {
        println("\n=== Vocabulary Features ===")
        
        // Check vocabulary information
        println("FOAF namespace: ${FOAF.namespace}")
        println("FOAF prefix: ${FOAF.prefix}")
        
        // Check if terms belong to vocabularies
        val personTerm = FOAF.Person
        println("Is FOAF.Person a FOAF term? ${FOAF.contains(personTerm)}")
        println("Is FOAF.Person a DCTERMS term? ${DCTERMS.contains(personTerm)}")
        
        // Get local names
        println("Local name of FOAF.name: ${FOAF.localname(FOAF.name)}")
        println("Local name of DCTERMS.title: ${DCTERMS.localname(DCTERMS.title)}")
        
        // Create terms dynamically
        val customFoafTerm = FOAF.term("customProperty")
        println("Custom FOAF term: $customFoafTerm")
    }
    
    /**
     * Example: Cross-vocabulary operations.
     */
    fun demonstrateCrossVocabularyFeatures() {
        println("\n=== Cross-Vocabulary Features ===")
        
        // Find vocabulary for a term
        val someTerm = FOAF.name
        val vocab = Vocabularies.findVocabularyForTerm(someTerm)
        println("Term $someTerm belongs to vocabulary: ${vocab?.prefix}")
        
        // Get local name from any vocabulary
        val localName = Vocabularies.getLocalName(someTerm)
        println("Local name of $someTerm: $localName")
        
        // Check if term is known
        val isKnown = Vocabularies.isKnownTerm(someTerm)
        println("Is $someTerm a known term? $isKnown")
        
        // Find vocabulary by prefix
        val foafVocab = Vocabularies.findByPrefix("foaf")
        println("Found FOAF vocabulary: ${foafVocab?.namespace}")
        
        // Get all terms from a vocabulary
        val foafTerms = Vocabularies.getTermsByPrefix("foaf")
        println("FOAF vocabulary has ${foafTerms?.size} terms")
    }
    
    /**
     * Example: Creating ontology definitions using OWL and RDFS.
     */
    fun createOntologyDefinition() {
        println("\n=== Ontology Definition ===")
        
        val personClass = iri("http://example.com/ontology/Person")
        val nameProperty = iri("http://example.com/ontology/name")
        val ageProperty = iri("http://example.com/ontology/age")
        
        val ontologyTriples = listOf(
            // Class definitions
            RdfTriple(personClass, RDF.type, RDFS.Class),
            RdfTriple(personClass, RDFS.label, "Person".toLiteral()),
            RdfTriple(personClass, RDFS.comment, "A human being".toLiteral()),
            
            // Property definitions
            RdfTriple(nameProperty, RDF.type, RDF.Property),
            RdfTriple(nameProperty, RDFS.label, "name".toLiteral()),
            RdfTriple(nameProperty, RDFS.domain, personClass),
            RdfTriple(nameProperty, RDFS.range, XSD.string),
            
            RdfTriple(ageProperty, RDF.type, RDF.Property),
            RdfTriple(ageProperty, RDFS.label, "age".toLiteral()),
            RdfTriple(ageProperty, RDFS.domain, personClass),
            RdfTriple(ageProperty, RDFS.range, XSD.integer),
            
            // OWL restrictions
            RdfTriple(personClass, RDF.type, OWL.Class),
            RdfTriple(ageProperty, RDF.type, OWL.DatatypeProperty),
            RdfTriple(nameProperty, RDF.type, OWL.DatatypeProperty)
        )
        
        println("Created ${ontologyTriples.size} ontology definition triples")
    }
    
    /**
     * Example: Working with SKOS for knowledge organization.
     */
    fun createKnowledgeOrganization() {
        println("\n=== Knowledge Organization (SKOS) ===")
        
        val conceptScheme = iri("http://example.com/scheme/geography")
        val countryConcept = iri("http://example.com/concept/country")
        val cityConcept = iri("http://example.com/concept/city")
        
        val skosTriples = listOf(
            // Concept scheme
            RdfTriple(conceptScheme, RDF.type, SKOS.ConceptScheme),
            RdfTriple(conceptScheme, SKOS.prefLabel, "Geographic Concepts".toLiteral()),
            
            // Concepts
            RdfTriple(countryConcept, RDF.type, SKOS.Concept),
            RdfTriple(countryConcept, SKOS.prefLabel, "Country".toLiteral()),
            RdfTriple(countryConcept, SKOS.definition, "A nation or sovereign state".toLiteral()),
            RdfTriple(countryConcept, SKOS.inScheme, conceptScheme),
            
            RdfTriple(cityConcept, RDF.type, SKOS.Concept),
            RdfTriple(cityConcept, SKOS.prefLabel, "City".toLiteral()),
            RdfTriple(cityConcept, SKOS.definition, "A large human settlement".toLiteral()),
            RdfTriple(cityConcept, SKOS.inScheme, conceptScheme),
            
            // Hierarchical relationships
            RdfTriple(cityConcept, SKOS.broader, countryConcept),
            RdfTriple(countryConcept, SKOS.narrower, cityConcept)
        )
        
        println("Created ${skosTriples.size} SKOS knowledge organization triples")
    }
    
    /**
     * Example: Data validation constraints using SHACL.
     */
    fun createDataValidationConstraints() {
        println("\n=== Data Validation (SHACL) ===")
        
        val personShape = iri("http://example.com/shapes/PersonShape")
        val namePropertyShape = iri("http://example.com/shapes/PersonNameProperty")
        
        val shaclTriples = listOf(
            // Node shape
            RdfTriple(personShape, RDF.type, SHACL.NodeShape),
            RdfTriple(personShape, SHACL.targetClass, FOAF.Person),
            
            // Property shape for name
            RdfTriple(namePropertyShape, RDF.type, SHACL.PropertyShape),
            RdfTriple(namePropertyShape, SHACL.property, FOAF.name),
            RdfTriple(namePropertyShape, SHACL.minCount, 1.toLiteral()),
            RdfTriple(namePropertyShape, SHACL.maxCount, 1.toLiteral()),
            RdfTriple(namePropertyShape, SHACL.pattern, "^[A-Z][a-z]+ [A-Z][a-z]+$".toLiteral()),
            RdfTriple(namePropertyShape, SHACL.message, "Person must have exactly one name in 'First Last' format".toLiteral()),
            
            // Link property shape to node shape
            RdfTriple(personShape, SHACL.property, namePropertyShape)
        )
        
        println("Created ${shaclTriples.size} SHACL validation constraint triples")
    }
    
    /**
     * Run all examples.
     */
    fun runAllExamples() {
        println("Running Vocabulary Package Examples")
        println("==================================")
        
        createPersonDescription()
        demonstrateVocabularyFeatures()
        demonstrateCrossVocabularyFeatures()
        createOntologyDefinition()
        createKnowledgeOrganization()
        createDataValidationConstraints()
        
        println("\nAll examples completed successfully!")
    }
}
