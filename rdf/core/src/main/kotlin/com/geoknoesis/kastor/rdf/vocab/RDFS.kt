package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * RDFS (RDF Schema) vocabulary.
 * A vocabulary for defining classes and properties in RDF.
 * 
 * @see <a href="https://www.w3.org/TR/rdf-schema/">RDF Schema 1.1</a>
 */
object RDFS : Vocabulary {
    override val namespace: String = "http://www.w3.org/2000/01/rdf-schema#"
    override val prefix: String = "rdfs"
    
    // Core classes
    val Class: Iri by lazy { term("Class") }
    val Container: Iri by lazy { term("Container") }
    val ContainerMembershipProperty: Iri by lazy { term("ContainerMembershipProperty") }
    val Datatype: Iri by lazy { term("Datatype") }
    val Literal: Iri by lazy { term("Literal") }
    val Resource: Iri by lazy { term("Resource") }
    
    // Core properties
    val comment: Iri by lazy { term("comment") }
    val domain: Iri by lazy { term("domain") }
    val isDefinedBy: Iri by lazy { term("isDefinedBy") }
    val label: Iri by lazy { term("label") }
    val member: Iri by lazy { term("member") }
    val range: Iri by lazy { term("range") }
    val seeAlso: Iri by lazy { term("seeAlso") }
    val subClassOf: Iri by lazy { term("subClassOf") }
    val subPropertyOf: Iri by lazy { term("subPropertyOf") }
}
