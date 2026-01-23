package com.geoknoesis.kastor.gen.processor.internal.utils

/**
 * Maps IRIs to vocabulary constants for code generation.
 */
internal object VocabularyMapper {
    
    // Mapping of namespace to vocabulary object name
    private val namespaceToVocab = mapOf(
        "http://www.w3.org/1999/02/22-rdf-syntax-ns#" to "RDF",
        "http://www.w3.org/2000/01/rdf-schema#" to "RDFS",
        "http://www.w3.org/2002/07/owl#" to "OWL",
        "http://www.w3.org/ns/shacl#" to "SHACL",
        "http://www.w3.org/2004/02/skos/core#" to "SKOS",
        "http://purl.org/dc/terms/" to "DCTERMS",
        "http://xmlns.com/foaf/0.1/" to "FOAF",
        "http://www.w3.org/ns/dcat#" to "DCAT",
        "http://www.w3.org/2001/XMLSchema#" to "XSD"
    )
    
    // Mapping of full IRI to vocabulary constant
    private val iriToConstant = mapOf(
        // RDF
        "http://www.w3.org/1999/02/22-rdf-syntax-ns#type" to "RDF.type",
        "http://www.w3.org/1999/02/22-rdf-syntax-ns#Property" to "RDF.Property",
        "http://www.w3.org/1999/02/22-rdf-syntax-ns#Statement" to "RDF.Statement",
        "http://www.w3.org/1999/02/22-rdf-syntax-ns#subject" to "RDF.subject",
        "http://www.w3.org/1999/02/22-rdf-syntax-ns#predicate" to "RDF.predicate",
        "http://www.w3.org/1999/02/22-rdf-syntax-ns#object" to "RDF.`object`",
        
        // RDFS
        "http://www.w3.org/2000/01/rdf-schema#Class" to "RDFS.Class",
        "http://www.w3.org/2000/01/rdf-schema#Resource" to "RDFS.Resource",
        "http://www.w3.org/2000/01/rdf-schema#subClassOf" to "RDFS.subClassOf",
        "http://www.w3.org/2000/01/rdf-schema#subPropertyOf" to "RDFS.subPropertyOf",
        "http://www.w3.org/2000/01/rdf-schema#label" to "RDFS.label",
        "http://www.w3.org/2000/01/rdf-schema#comment" to "RDFS.comment",
        "http://www.w3.org/2000/01/rdf-schema#domain" to "RDFS.domain",
        "http://www.w3.org/2000/01/rdf-schema#range" to "RDFS.range",
        
        // OWL
        "http://www.w3.org/2002/07/owl#Class" to "OWL.Class",
        "http://www.w3.org/2002/07/owl#Ontology" to "OWL.Ontology",
        "http://www.w3.org/2002/07/owl#ObjectProperty" to "OWL.ObjectProperty",
        "http://www.w3.org/2002/07/owl#DatatypeProperty" to "OWL.DatatypeProperty",
        
        // SKOS
        "http://www.w3.org/2004/02/skos/core#Concept" to "SKOS.Concept",
        "http://www.w3.org/2004/02/skos/core#ConceptScheme" to "SKOS.ConceptScheme",
        "http://www.w3.org/2004/02/skos/core#Collection" to "SKOS.Collection",
        "http://www.w3.org/2004/02/skos/core#OrderedCollection" to "SKOS.OrderedCollection",
        "http://www.w3.org/2004/02/skos/core#prefLabel" to "SKOS.prefLabel",
        "http://www.w3.org/2004/02/skos/core#altLabel" to "SKOS.altLabel",
        "http://www.w3.org/2004/02/skos/core#hiddenLabel" to "SKOS.hiddenLabel",
        "http://www.w3.org/2004/02/skos/core#definition" to "SKOS.definition",
        "http://www.w3.org/2004/02/skos/core#scopeNote" to "SKOS.scopeNote",
        "http://www.w3.org/2004/02/skos/core#example" to "SKOS.example",
        "http://www.w3.org/2004/02/skos/core#note" to "SKOS.note",
        "http://www.w3.org/2004/02/skos/core#broader" to "SKOS.broader",
        "http://www.w3.org/2004/02/skos/core#narrower" to "SKOS.narrower",
        "http://www.w3.org/2004/02/skos/core#related" to "SKOS.related",
        "http://www.w3.org/2004/02/skos/core#inScheme" to "SKOS.inScheme",
        "http://www.w3.org/2004/02/skos/core#hasTopConcept" to "SKOS.hasTopConcept",
        "http://www.w3.org/2004/02/skos/core#topConceptOf" to "SKOS.topConceptOf",
        "http://www.w3.org/2004/02/skos/core#member" to "SKOS.member",
        "http://www.w3.org/2004/02/skos/core#notation" to "SKOS.notation",
        
        // DCAT
        "http://www.w3.org/ns/dcat#Catalog" to "DCAT.Catalog",
        "http://www.w3.org/ns/dcat#Dataset" to "DCAT.Dataset",
        "http://www.w3.org/ns/dcat#Distribution" to "DCAT.Distribution",
        "http://www.w3.org/ns/dcat#DataService" to "DCAT.DataService",
        "http://www.w3.org/ns/dcat#dataset" to "DCAT.dataset",
        "http://www.w3.org/ns/dcat#distribution" to "DCAT.distribution",
        "http://www.w3.org/ns/dcat#service" to "DCAT.service",
        
        // DCTERMS
        "http://purl.org/dc/terms/title" to "DCTERMS.title",
        "http://purl.org/dc/terms/description" to "DCTERMS.description",
        "http://purl.org/dc/terms/identifier" to "DCTERMS.identifier",
        "http://purl.org/dc/terms/created" to "DCTERMS.created",
        "http://purl.org/dc/terms/modified" to "DCTERMS.modified",
        "http://purl.org/dc/terms/publisher" to "DCTERMS.publisher",
        "http://purl.org/dc/terms/creator" to "DCTERMS.creator",
        
        // FOAF
        "http://xmlns.com/foaf/0.1/Person" to "FOAF.Person",
        "http://xmlns.com/foaf/0.1/Organization" to "FOAF.Organization",
        "http://xmlns.com/foaf/0.1/name" to "FOAF.name",
        "http://xmlns.com/foaf/0.1/mbox" to "FOAF.mbox",
        "http://xmlns.com/foaf/0.1/homepage" to "FOAF.homepage",
        
        // XSD
        "http://www.w3.org/2001/XMLSchema#string" to "XSD.string",
        "http://www.w3.org/2001/XMLSchema#integer" to "XSD.integer",
        "http://www.w3.org/2001/XMLSchema#int" to "XSD.int",
        "http://www.w3.org/2001/XMLSchema#double" to "XSD.double",
        "http://www.w3.org/2001/XMLSchema#float" to "XSD.float",
        "http://www.w3.org/2001/XMLSchema#boolean" to "XSD.boolean",
        "http://www.w3.org/2001/XMLSchema#anyURI" to "XSD.anyURI",
        "http://www.w3.org/2001/XMLSchema#date" to "XSD.date",
        "http://www.w3.org/2001/XMLSchema#dateTime" to "XSD.dateTime"
    )
    
    /**
     * Gets the vocabulary constant for an IRI, or null if not found.
     */
    fun getVocabularyConstant(iri: String): String? {
        return iriToConstant[iri]
    }
    
    /**
     * Gets the vocabulary object name for a namespace.
     */
    fun getVocabularyObject(namespace: String): String? {
        return namespaceToVocab[namespace]
    }
    
    /**
     * Gets all vocabulary imports needed for a set of IRIs.
     */
    fun getRequiredImports(iris: List<String>): Set<String> {
        val imports = mutableSetOf<String>()
        
        iris.forEach { iri ->
            val constant = getVocabularyConstant(iri)
            if (constant != null) {
                val vocabName = constant.substringBefore(".")
                imports.add("com.geoknoesis.kastor.rdf.vocab.$vocabName")
            }
        }
        
        return imports
    }
    
    /**
     * Extracts namespace from IRI.
     */
    fun extractNamespace(iri: String): String? {
        val hashIndex = iri.lastIndexOf('#')
        val slashIndex = iri.lastIndexOf('/')
        val separatorIndex = maxOf(hashIndex, slashIndex)
        return if (separatorIndex >= 0) {
            iri.substring(0, separatorIndex + 1)
        } else {
            null
        }
    }
    
    /**
     * Extracts local name from IRI.
     */
    fun extractLocalName(iri: String): String {
        return iri.substringAfterLast('#').substringAfterLast('/')
    }
}


