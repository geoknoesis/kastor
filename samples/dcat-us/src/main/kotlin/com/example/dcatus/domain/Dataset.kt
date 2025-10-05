package com.example.dcatus.domain

import com.example.ontomapper.annotations.RdfClass
import com.example.ontomapper.annotations.RdfProperty

/**
 * Domain interface for DCAT Dataset.
 * Pure domain interface with no RDF dependencies.
 */
@RdfClass(iri = "http://www.w3.org/ns/dcat#Dataset")
interface Dataset {
    @get:RdfProperty(iri = "http://purl.org/dc/terms/title")
    val title: List<String>
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/description")
    val description: List<String>
    
    @get:RdfProperty(iri = "http://www.w3.org/ns/dcat#distribution")
    val distributions: List<Distribution>
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/keyword")
    val keywords: List<String>
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/theme")
    val themes: List<Concept>
}
