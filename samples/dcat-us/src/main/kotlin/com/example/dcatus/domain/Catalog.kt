package com.example.dcatus.domain

import com.geoknoesis.kastor.gen.annotations.RdfClass
import com.geoknoesis.kastor.gen.annotations.RdfProperty

/**
 * Domain interface for DCAT Catalog.
 * Pure domain interface with no RDF dependencies.
 */
@RdfClass(iri = "http://www.w3.org/ns/dcat#Catalog")
interface Catalog {
    @get:RdfProperty(iri = "http://purl.org/dc/terms/title")
    val title: List<String>
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/description")
    val description: List<String>
    
    @get:RdfProperty(iri = "http://www.w3.org/ns/dcat#dataset")
    val datasets: List<Dataset>
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/publisher")
    val publisher: List<Agent>
}









