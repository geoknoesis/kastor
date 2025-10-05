package com.example.dcatus.domain

import com.example.ontomapper.annotations.RdfClass
import com.example.ontomapper.annotations.RdfProperty

/**
 * Domain interface for DCAT Distribution.
 * Pure domain interface with no RDF dependencies.
 */
@RdfClass(iri = "http://www.w3.org/ns/dcat#Distribution")
interface Distribution {
    @get:RdfProperty(iri = "http://purl.org/dc/terms/title")
    val title: List<String>
    
    @get:RdfProperty(iri = "http://www.w3.org/ns/dcat#downloadURL")
    val downloadUrl: List<String>
    
    @get:RdfProperty(iri = "http://www.w3.org/ns/dcat#mediaType")
    val mediaType: List<String>
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/format")
    val format: List<String>
}
