package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VocabularyTest {
    
    @Test
    fun `DCTERMS vocabulary has correct namespace and prefix`() {
        assertEquals("http://purl.org/dc/terms/", DCTERMS.namespace, "DCTERMS should have correct namespace")
        assertEquals("dcterms", DCTERMS.prefix, "DCTERMS should have correct prefix")
    }
    
    @Test
    fun `DCTERMS vocabulary terms are created correctly`() {
        // Test core classes
        assertEquals("http://purl.org/dc/terms/Agent", DCTERMS.Agent.value, "DCTERMS Agent should have correct IRI")
        assertEquals("http://purl.org/dc/terms/BibliographicResource", DCTERMS.BibliographicResource.value, "DCTERMS BibliographicResource should have correct IRI")
        assertEquals("http://purl.org/dc/terms/FileFormat", DCTERMS.FileFormat.value, "DCTERMS FileFormat should have correct IRI")
        
        // Test core properties
        assertEquals("http://purl.org/dc/terms/title", DCTERMS.title.value, "DCTERMS title should have correct IRI")
        assertEquals("http://purl.org/dc/terms/creator", DCTERMS.creator.value, "DCTERMS creator should have correct IRI")
        assertEquals("http://purl.org/dc/terms/description", DCTERMS.description.value, "DCTERMS description should have correct IRI")
        assertEquals("http://purl.org/dc/terms/date", DCTERMS.date.value, "DCTERMS date should have correct IRI")
        assertEquals("http://purl.org/dc/terms/type", DCTERMS.type.value, "DCTERMS type should have correct IRI")
    }
    
    @Test
    fun `FOAF vocabulary has correct namespace and prefix`() {
        assertEquals("http://xmlns.com/foaf/0.1/", FOAF.namespace, "FOAF should have correct namespace")
        assertEquals("foaf", FOAF.prefix, "FOAF should have correct prefix")
    }
    
    @Test
    fun `FOAF vocabulary terms are created correctly`() {
        // Test core classes
        assertEquals("http://xmlns.com/foaf/0.1/Person", FOAF.Person.value, "FOAF Person should have correct IRI")
        assertEquals("http://xmlns.com/foaf/0.1/Organization", FOAF.Organization.value, "FOAF Organization should have correct IRI")
        assertEquals("http://xmlns.com/foaf/0.1/Document", FOAF.Document.value, "FOAF Document should have correct IRI")
        
        // Test core properties
        assertEquals("http://xmlns.com/foaf/0.1/name", FOAF.name.value, "FOAF name should have correct IRI")
        assertEquals("http://xmlns.com/foaf/0.1/knows", FOAF.knows.value, "FOAF knows should have correct IRI")
        assertEquals("http://xmlns.com/foaf/0.1/mbox", FOAF.mbox.value, "FOAF mbox should have correct IRI")
        assertEquals("http://xmlns.com/foaf/0.1/homepage", FOAF.homepage.value, "FOAF homepage should have correct IRI")
        assertEquals("http://xmlns.com/foaf/0.1/age", FOAF.age.value, "FOAF age should have correct IRI")
    }
    
    @Test
    fun `RDF vocabulary has correct namespace and prefix`() {
        assertEquals("http://www.w3.org/1999/02/22-rdf-syntax-ns#", RDF.namespace, "RDF should have correct namespace")
        assertEquals("rdf", RDF.prefix, "RDF should have correct prefix")
    }
    
    @Test
    fun `RDF vocabulary terms are created correctly`() {
        // Test core terms
        assertEquals("http://www.w3.org/1999/02/22-rdf-syntax-ns#type", RDF.type.value, "RDF type should have correct IRI")
        assertEquals("http://www.w3.org/1999/02/22-rdf-syntax-ns#Property", RDF.Property.value, "RDF Property should have correct IRI")
        assertEquals("http://www.w3.org/1999/02/22-rdf-syntax-ns#Statement", RDF.Statement.value, "RDF Statement should have correct IRI")
        assertEquals("http://www.w3.org/1999/02/22-rdf-syntax-ns#subject", RDF.subject.value, "RDF subject should have correct IRI")
        assertEquals("http://www.w3.org/1999/02/22-rdf-syntax-ns#predicate", RDF.predicate.value, "RDF predicate should have correct IRI")
        assertEquals("http://www.w3.org/1999/02/22-rdf-syntax-ns#object", RDF.`object`.value, "RDF object should have correct IRI")
    }
    
    @Test
    fun `RDFS vocabulary has correct namespace and prefix`() {
        assertEquals("http://www.w3.org/2000/01/rdf-schema#", RDFS.namespace, "RDFS should have correct namespace")
        assertEquals("rdfs", RDFS.prefix, "RDFS should have correct prefix")
    }
    
    @Test
    fun `RDFS vocabulary terms are created correctly`() {
        // Test core terms
        assertEquals("http://www.w3.org/2000/01/rdf-schema#Class", RDFS.Class.value, "RDFS Class should have correct IRI")
        assertEquals("http://www.w3.org/2000/01/rdf-schema#Resource", RDFS.Resource.value, "RDFS Resource should have correct IRI")
        assertEquals("http://www.w3.org/2000/01/rdf-schema#label", RDFS.label.value, "RDFS label should have correct IRI")
        assertEquals("http://www.w3.org/2000/01/rdf-schema#comment", RDFS.comment.value, "RDFS comment should have correct IRI")
        assertEquals("http://www.w3.org/2000/01/rdf-schema#subClassOf", RDFS.subClassOf.value, "RDFS subClassOf should have correct IRI")
        assertEquals("http://www.w3.org/2000/01/rdf-schema#subPropertyOf", RDFS.subPropertyOf.value, "RDFS subPropertyOf should have correct IRI")
    }
    
    @Test
    fun `OWL vocabulary has correct namespace and prefix`() {
        assertEquals("http://www.w3.org/2002/07/owl#", OWL.namespace, "OWL should have correct namespace")
        assertEquals("owl", OWL.prefix, "OWL should have correct prefix")
    }
    
    @Test
    fun `OWL vocabulary terms are created correctly`() {
        // Test core classes
        assertEquals("http://www.w3.org/2002/07/owl#Class", OWL.Class.value, "OWL Class should have correct IRI")
        assertEquals("http://www.w3.org/2002/07/owl#ObjectProperty", OWL.ObjectProperty.value, "OWL ObjectProperty should have correct IRI")
        assertEquals("http://www.w3.org/2002/07/owl#DatatypeProperty", OWL.DatatypeProperty.value, "OWL DatatypeProperty should have correct IRI")
        assertEquals("http://www.w3.org/2002/07/owl#Ontology", OWL.Ontology.value, "OWL Ontology should have correct IRI")
        
        // Test core properties
        assertEquals("http://www.w3.org/2002/07/owl#sameAs", OWL.sameAs.value, "OWL sameAs should have correct IRI")
        assertEquals("http://www.w3.org/2002/07/owl#differentFrom", OWL.differentFrom.value, "OWL differentFrom should have correct IRI")
        assertEquals("http://www.w3.org/2002/07/owl#equivalentClass", OWL.equivalentClass.value, "OWL equivalentClass should have correct IRI")
        assertEquals("http://www.w3.org/2002/07/owl#equivalentProperty", OWL.equivalentProperty.value, "OWL equivalentProperty should have correct IRI")
    }
    
    @Test
    fun `XSD vocabulary has correct namespace and prefix`() {
        assertEquals("http://www.w3.org/2001/XMLSchema#", XSD.namespace, "XSD should have correct namespace")
        assertEquals("xsd", XSD.prefix, "XSD should have correct prefix")
    }
    
    @Test
    fun `XSD vocabulary terms are created correctly`() {
        // Test core datatypes
        assertEquals("http://www.w3.org/2001/XMLSchema#string", XSD.string.value, "XSD string should have correct IRI")
        assertEquals("http://www.w3.org/2001/XMLSchema#integer", XSD.integer.value, "XSD integer should have correct IRI")
        assertEquals("http://www.w3.org/2001/XMLSchema#long", XSD.long.value, "XSD long should have correct IRI")
        assertEquals("http://www.w3.org/2001/XMLSchema#double", XSD.double.value, "XSD double should have correct IRI")
        assertEquals("http://www.w3.org/2001/XMLSchema#boolean", XSD.boolean.value, "XSD boolean should have correct IRI")
        assertEquals("http://www.w3.org/2001/XMLSchema#date", XSD.date.value, "XSD date should have correct IRI")
        assertEquals("http://www.w3.org/2001/XMLSchema#dateTime", XSD.dateTime.value, "XSD dateTime should have correct IRI")
        assertEquals("http://www.w3.org/2001/XMLSchema#time", XSD.time.value, "XSD time should have correct IRI")
        assertEquals("http://www.w3.org/2001/XMLSchema#decimal", XSD.decimal.value, "XSD decimal should have correct IRI")
        assertEquals("http://www.w3.org/2001/XMLSchema#float", XSD.float.value, "XSD float should have correct IRI")
    }
    
    @Test
    fun `SKOS vocabulary has correct namespace and prefix`() {
        assertEquals("http://www.w3.org/2004/02/skos/core#", SKOS.namespace, "SKOS should have correct namespace")
        assertEquals("skos", SKOS.prefix, "SKOS should have correct prefix")
    }
    
    @Test
    fun `SKOS vocabulary terms are created correctly`() {
        // Test core terms
        assertEquals("http://www.w3.org/2004/02/skos/core#Concept", SKOS.Concept.value, "SKOS Concept should have correct IRI")
        assertEquals("http://www.w3.org/2004/02/skos/core#ConceptScheme", SKOS.ConceptScheme.value, "SKOS ConceptScheme should have correct IRI")
        assertEquals("http://www.w3.org/2004/02/skos/core#prefLabel", SKOS.prefLabel.value, "SKOS prefLabel should have correct IRI")
        assertEquals("http://www.w3.org/2004/02/skos/core#altLabel", SKOS.altLabel.value, "SKOS altLabel should have correct IRI")
        assertEquals("http://www.w3.org/2004/02/skos/core#broader", SKOS.broader.value, "SKOS broader should have correct IRI")
        assertEquals("http://www.w3.org/2004/02/skos/core#narrower", SKOS.narrower.value, "SKOS narrower should have correct IRI")
    }
    
    @Test
    fun `SHACL vocabulary has correct namespace and prefix`() {
        assertEquals("http://www.w3.org/ns/shacl#", SHACL.namespace, "SHACL should have correct namespace")
        assertEquals("sh", SHACL.prefix, "SHACL should have correct prefix")
    }
    
    @Test
    fun `SHACL vocabulary terms are created correctly`() {
        // Test core terms
        assertEquals("http://www.w3.org/ns/shacl#NodeShape", SHACL.NodeShape.value, "SHACL NodeShape should have correct IRI")
        assertEquals("http://www.w3.org/ns/shacl#PropertyShape", SHACL.PropertyShape.value, "SHACL PropertyShape should have correct IRI")
        assertEquals("http://www.w3.org/ns/shacl#targetClass", SHACL.targetClass.value, "SHACL targetClass should have correct IRI")
        assertEquals("http://www.w3.org/ns/shacl#property", SHACL.property.value, "SHACL property should have correct IRI")
        assertEquals("http://www.w3.org/ns/shacl#minCount", SHACL.minCount.value, "SHACL minCount should have correct IRI")
        assertEquals("http://www.w3.org/ns/shacl#maxCount", SHACL.maxCount.value, "SHACL maxCount should have correct IRI")
    }
    
    @Test
    fun `vocabulary terms are lazy initialized`() {
        // Test that vocabulary terms are lazy (this is more of a structural test)
        // The actual lazy initialization is tested by the fact that we can access the terms
        assertNotNull(DCTERMS.title, "DCTERMS title should be accessible")
        assertNotNull(FOAF.name, "FOAF name should be accessible")
        assertNotNull(RDF.type, "RDF type should be accessible")
        assertNotNull(RDFS.label, "RDFS label should be accessible")
        assertNotNull(OWL.Class, "OWL Class should be accessible")
        assertNotNull(XSD.string, "XSD string should be accessible")
    }
    
    @Test
    fun `vocabulary terms are IRIs`() {
        // Test that all vocabulary terms are IRI instances
        assertTrue(DCTERMS.title is Iri, "DCTERMS title should be an IRI")
        assertTrue(FOAF.name is Iri, "FOAF name should be an IRI")
        assertTrue(RDF.type is Iri, "RDF type should be an IRI")
        assertTrue(RDFS.label is Iri, "RDFS label should be an IRI")
        assertTrue(OWL.Class is Iri, "OWL Class should be an IRI")
        assertTrue(XSD.string is Iri, "XSD string should be an IRI")
        assertTrue(SKOS.Concept is Iri, "SKOS Concept should be an IRI")
        assertTrue(SHACL.NodeShape is Iri, "SHACL NodeShape should be an IRI")
    }
    
    @Test
    fun `vocabulary terms have correct values`() {
        // Test that vocabulary terms have the expected string values
        assertTrue(DCTERMS.title.value.endsWith("title"), "DCTERMS title should end with 'title'")
        assertTrue(FOAF.name.value.endsWith("name"), "FOAF name should end with 'name'")
        assertTrue(RDF.type.value.endsWith("type"), "RDF type should end with 'type'")
        assertTrue(RDFS.label.value.endsWith("label"), "RDFS label should end with 'label'")
        assertTrue(OWL.Class.value.endsWith("Class"), "OWL Class should end with 'Class'")
        assertTrue(XSD.string.value.endsWith("string"), "XSD string should end with 'string'")
        assertTrue(SKOS.Concept.value.endsWith("Concept"), "SKOS Concept should end with 'Concept'")
        assertTrue(SHACL.NodeShape.value.endsWith("NodeShape"), "SHACL NodeShape should end with 'NodeShape'")
    }
    
    @Test
    fun `vocabulary namespaces are valid URIs`() {
        // Test that all vocabulary namespaces are valid URIs
        val namespaces = listOf(
            DCTERMS.namespace,
            FOAF.namespace,
            RDF.namespace,
            RDFS.namespace,
            OWL.namespace,
            XSD.namespace,
            SKOS.namespace,
            SHACL.namespace
        )
        
        namespaces.forEach { namespace ->
            assertTrue(namespace.startsWith("http://"), "Namespace should start with http://: $namespace")
            assertTrue(namespace.endsWith("/") || namespace.endsWith("#"), "Namespace should end with / or #: $namespace")
        }
    }
    
    @Test
    fun `vocabulary prefixes are valid`() {
        // Test that all vocabulary prefixes are valid
        val prefixes = listOf(
            DCTERMS.prefix,
            FOAF.prefix,
            RDF.prefix,
            RDFS.prefix,
            OWL.prefix,
            XSD.prefix,
            SKOS.prefix,
            SHACL.prefix
        )
        
        prefixes.forEach { prefix ->
            assertTrue(prefix.isNotEmpty(), "Prefix should not be empty: $prefix")
            assertTrue(prefix.matches(Regex("[a-zA-Z][a-zA-Z0-9]*")), "Prefix should be valid identifier: $prefix")
        }
    }
    
    @Test
    fun `vocabulary terms can be used in triples`() {
        val repo = Rdf.memory()
        val graph = repo.defaultGraph
        
        val resource = Iri("http://example.org/resource")
        
        // Test using vocabulary terms in triples
        val triple1 = RdfTriple(resource, DCTERMS.title, Literal("My Resource"))
        val triple2 = RdfTriple(resource, FOAF.name, Literal("Resource Name"))
        val triple3 = RdfTriple(resource, RDF.type, FOAF.Document)
        val triple4 = RdfTriple(resource, RDFS.label, Literal("Resource Label"))
        
        // Add triples to graph
        graph.addTriple(triple1)
        graph.addTriple(triple2)
        graph.addTriple(triple3)
        graph.addTriple(triple4)
        
        // Verify triples were added correctly
        assertEquals(4, graph.size(), "Graph should contain 4 triples")
        assertTrue(graph.hasTriple(triple1), "Graph should contain DCTERMS title triple")
        assertTrue(graph.hasTriple(triple2), "Graph should contain FOAF name triple")
        assertTrue(graph.hasTriple(triple3), "Graph should contain RDF type triple")
        assertTrue(graph.hasTriple(triple4), "Graph should contain RDFS label triple")
        
        repo.close()
    }
}









