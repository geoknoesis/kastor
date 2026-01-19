package com.geoknoesis.kastor.gen.annotations

/**
 * Annotation to mark an interface as representing an RDF class.
 * 
 * @property iri The IRI of the RDF class
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RdfClass(val iri: String)













