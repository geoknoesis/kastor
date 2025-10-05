package com.example.dcatus.domain

import com.example.ontomapper.annotations.RdfClass
import com.example.ontomapper.annotations.RdfProperty

/**
 * Domain interface for FOAF Agent.
 * Pure domain interface with no RDF dependencies.
 */
@RdfClass(iri = "http://xmlns.com/foaf/0.1/Agent")
interface Agent {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/homepage")
    val homepage: List<String>
}
