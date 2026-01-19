package com.example.ontomapper.annotations

/**
 * Annotation to mark a property as representing an RDF property.
 * 
 * @property iri The IRI of the RDF property
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class RdfProperty(val iri: String)













