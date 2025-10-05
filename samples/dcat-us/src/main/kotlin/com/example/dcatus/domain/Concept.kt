package com.example.dcatus.domain

import com.example.ontomapper.annotations.RdfClass
import com.example.ontomapper.annotations.RdfProperty

/**
 * Domain interface for SKOS Concept.
 * Pure domain interface with no RDF dependencies.
 */
@RdfClass(iri = "http://www.w3.org/2004/02/skos/core#Concept")
interface Concept {
    @get:RdfProperty(iri = "http://www.w3.org/2004/02/skos/core#prefLabel")
    val prefLabel: List<String>
    
    @get:RdfProperty(iri = "http://www.w3.org/2004/02/skos/core#definition")
    val definition: List<String>
}
