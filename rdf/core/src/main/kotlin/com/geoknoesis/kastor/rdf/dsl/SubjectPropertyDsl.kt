package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.OWL

/**
 * DSL for Turtle-style compact syntax with multiple properties on same subject.
 * Provides function-like syntax for predicates with semicolon separators.
 */
class SubjectPropertyDsl(private val subject: RdfResource) {
    val triples = mutableListOf<RdfTriple>()
    
    /**
     * Add a property using function-like syntax.
     * Usage: name("Alice")
     */
    fun name(value: Any) = addProperty(FOAF.name, value)
    fun age(value: Any) = addProperty(FOAF.age, value)
    fun knows(value: Any) = addProperty(FOAF.knows, value)
    fun mbox(value: Any) = addProperty(FOAF.mbox, value)
    fun homepage(value: Any) = addProperty(FOAF.homepage, value)
    fun firstName(value: Any) = addProperty(FOAF.firstName, value)
    fun nick(value: Any) = addProperty(FOAF.nick, value)
    fun title(value: Any) = addProperty(FOAF.title, value)
    fun weblog(value: Any) = addProperty(FOAF.weblog, value)
    fun gender(value: Any) = addProperty(FOAF.gender, value)
    fun birthday(value: Any) = addProperty(FOAF.birthday, value)
    fun docTitle(value: Any) = addProperty(DCTERMS.title, value)
    fun creator(value: Any) = addProperty(DCTERMS.creator, value)
    fun dateCreated(value: Any) = addProperty(DCTERMS.date, value)
    fun description(value: Any) = addProperty(DCTERMS.description, value)
    fun type(value: Any) = addProperty(RDF.type, value)
    fun a(value: Any) = addProperty(RDF.type, value)  // Turtle-style alias for rdf:type
    fun label(value: Any) = addProperty(RDFS.label, value)
    fun comment(value: Any) = addProperty(RDFS.comment, value)
    fun subClassOf(value: Any) = addProperty(RDFS.subClassOf, value)
    fun domain(value: Any) = addProperty(RDFS.domain, value)
    fun range(value: Any) = addProperty(RDFS.range, value)
    fun sameAs(value: Any) = addProperty(OWL.sameAs, value)
    fun equivalentClass(value: Any) = addProperty(OWL.equivalentClass, value)
    fun imports(value: Any) = addProperty(OWL.imports, value)
    
    /**
     * Add multiple values for the same property.
     * Usage: knows(friend1, friend2, friend3)
     */
    fun name(vararg values: Any) = addMultipleProperties(FOAF.name, values)
    fun age(vararg values: Any) = addMultipleProperties(FOAF.age, values)
    fun knows(vararg values: Any) = addMultipleProperties(FOAF.knows, values)
    fun mbox(vararg values: Any) = addMultipleProperties(FOAF.mbox, values)
    fun homepage(vararg values: Any) = addMultipleProperties(FOAF.homepage, values)
    fun firstName(vararg values: Any) = addMultipleProperties(FOAF.firstName, values)
    fun nick(vararg values: Any) = addMultipleProperties(FOAF.nick, values)
    fun title(vararg values: Any) = addMultipleProperties(FOAF.title, values)
    fun weblog(vararg values: Any) = addMultipleProperties(FOAF.weblog, values)
    fun gender(vararg values: Any) = addMultipleProperties(FOAF.gender, values)
    fun birthday(vararg values: Any) = addMultipleProperties(FOAF.birthday, values)
    fun docTitle(vararg values: Any) = addMultipleProperties(DCTERMS.title, values)
    fun creator(vararg values: Any) = addMultipleProperties(DCTERMS.creator, values)
    fun dateCreated(vararg values: Any) = addMultipleProperties(DCTERMS.date, values)
    fun description(vararg values: Any) = addMultipleProperties(DCTERMS.description, values)
    fun type(vararg values: Any) = addMultipleProperties(RDF.type, values)
    fun a(vararg values: Any) = addMultipleProperties(RDF.type, values)  // Turtle-style alias for rdf:type
    fun label(vararg values: Any) = addMultipleProperties(RDFS.label, values)
    fun comment(vararg values: Any) = addMultipleProperties(RDFS.comment, values)
    fun subClassOf(vararg values: Any) = addMultipleProperties(RDFS.subClassOf, values)
    fun domain(vararg values: Any) = addMultipleProperties(RDFS.domain, values)
    fun range(vararg values: Any) = addMultipleProperties(RDFS.range, values)
    fun sameAs(vararg values: Any) = addMultipleProperties(OWL.sameAs, values)
    fun equivalentClass(vararg values: Any) = addMultipleProperties(OWL.equivalentClass, values)
    fun imports(vararg values: Any) = addMultipleProperties(OWL.imports, values)
    
    /**
     * Generic property function for custom predicates.
     * Usage: property(FOAF.name, "Alice") or property(Iri("http://example.org/custom"), "value")
     */
    fun property(predicate: Iri, value: Any) = addProperty(predicate, value)
    
    /**
     * Add multiple values for a custom property.
     * Usage: property(FOAF.knows, friend1, friend2, friend3)
     */
    fun property(predicate: Iri, vararg values: Any) = addMultipleProperties(predicate, values)
    
    /**
     * Add a property with automatic type conversion.
     */
    private fun addProperty(predicate: Iri, value: Any) {
        val obj = when (value) {
            is String -> Literal(value, XSD.string)
            is Int -> Literal(value.toString(), XSD.integer)
            is Double -> Literal(value.toString(), XSD.double)
            is Boolean -> Literal(value.toString(), XSD.boolean)
            is RdfTerm -> value
            else -> Literal(value.toString(), XSD.string)
        }
        triples.add(RdfTriple(subject, predicate, obj))
    }
    
    /**
     * Add multiple values for the same property.
     */
    private fun addMultipleProperties(predicate: Iri, values: Array<out Any>) {
        values.forEach { value -> addProperty(predicate, value) }
    }
}









