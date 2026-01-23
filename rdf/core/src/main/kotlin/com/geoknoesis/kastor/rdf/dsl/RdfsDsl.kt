package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS

/**
 * DSL for creating RDF Schema (RDFS) ontologies.
 * Provides a type-safe, natural language syntax for defining RDFS classes, properties, and relationships.
 * 
 * Example:
 * ```kotlin
 * val ontology = rdfs {
 *     prefix("ex", "http://example.org/")
 *     
 *     `class`("ex:Person") {
 *         label("Person", "en")
 *         comment("A human being", "en")
 *         subClassOf(RDFS.Resource)
 *     }
 *     
 *     property("ex:name") {
 *         label("Name", "en")
 *         domain("ex:Person")
 *         range(RDFS.Literal)
 *     }
 *     
 *     property("ex:parent") {
 *         domain("ex:Person")
 *         range("ex:Person")
 *         subPropertyOf("ex:relatedTo")
 *     }
 * }
 * ```
 */
class RdfsDsl {
    private val graphDsl = GraphDsl()
    private var bnodeCounter = 0
    
    private fun nextBnode(prefix: String = "b"): BlankNode {
        return bnode("${prefix}${++bnodeCounter}")
    }
    
    /**
     * Configure prefix mappings for QName resolution.
     */
    fun prefixes(configure: MutableMap<String, String>.() -> Unit) {
        graphDsl.prefixes(configure)
    }
    
    /**
     * Add a single prefix mapping.
     */
    fun prefix(name: String, namespace: String) {
        graphDsl.prefix(name, namespace)
    }
    
    private fun resolveIri(iriOrQName: String): Iri {
        return graphDsl.qname(iriOrQName)
    }
    
    /**
     * Define an RDFS Class.
     */
    fun `class`(classIri: String, configure: RdfsClassDsl.() -> Unit = {}) {
        val classResource = resolveIri(classIri)
        graphDsl.triple(classResource, RDF.type, RDFS.Class)
        val dsl = RdfsClassDsl(classResource, graphDsl, ::resolveIri)
        dsl.configure()
    }
    
    /**
     * Define an RDFS Property.
     */
    fun property(propertyIri: String, configure: RdfsPropertyDsl.() -> Unit = {}) {
        val propertyResource = resolveIri(propertyIri)
        graphDsl.triple(propertyResource, RDF.type, RDF.Property)
        val dsl = RdfsPropertyDsl(propertyResource, graphDsl, ::resolveIri)
        dsl.configure()
    }
    
    /**
     * Define an RDFS Datatype.
     */
    fun datatype(datatypeIri: String, configure: RdfsDatatypeDsl.() -> Unit = {}) {
        val datatypeResource = resolveIri(datatypeIri)
        graphDsl.triple(datatypeResource, RDF.type, RDFS.Datatype)
        val dsl = RdfsDatatypeDsl(datatypeResource, graphDsl, ::resolveIri)
        dsl.configure()
    }
    
    /**
     * Add a direct triple to the graph (for advanced use cases).
     */
    fun triple(subject: RdfResource, predicate: Iri, obj: RdfTerm) {
        graphDsl.triple(subject, predicate, obj)
    }
    
    /**
     * Build the final RdfGraph from the collected triples.
     */
    fun build(): MutableRdfGraph {
        return graphDsl.build()
    }
}

/**
 * DSL for configuring an RDFS Class.
 */
class RdfsClassDsl(
    private val classResource: RdfResource,
    private val graphDsl: GraphDsl,
    private val resolveIri: (String) -> Iri
) {
    /**
     * Add an rdfs:label to the class.
     */
    fun label(value: String, language: String? = null) {
        val literal = if (language != null) {
            lang(value, language)
        } else {
            string(value)
        }
        graphDsl.triple(classResource, RDFS.label, literal)
    }
    
    /**
     * Add an rdfs:comment to the class.
     */
    fun comment(value: String, language: String? = null) {
        val literal = if (language != null) {
            lang(value, language)
        } else {
            string(value)
        }
        graphDsl.triple(classResource, RDFS.comment, literal)
    }
    
    /**
     * Declare that this class is a subclass of another class.
     */
    fun subClassOf(superClass: RdfResource) {
        graphDsl.triple(classResource, RDFS.subClassOf, superClass)
    }
    
    /**
     * Declare that this class is a subclass of another class (by IRI string).
     */
    fun subClassOf(superClassIri: String) {
        subClassOf(resolveIri(superClassIri))
    }
    
    /**
     * Add an rdfs:seeAlso link.
     */
    fun seeAlso(iri: Iri) {
        graphDsl.triple(classResource, RDFS.seeAlso, iri)
    }
    
    /**
     * Add an rdfs:seeAlso link (by IRI string).
     */
    fun seeAlso(iriString: String) {
        seeAlso(resolveIri(iriString))
    }
    
    /**
     * Add an rdfs:isDefinedBy link.
     */
    fun isDefinedBy(iri: Iri) {
        graphDsl.triple(classResource, RDFS.isDefinedBy, iri)
    }
    
    /**
     * Add an rdfs:isDefinedBy link (by IRI string).
     */
    fun isDefinedBy(iriString: String) {
        isDefinedBy(resolveIri(iriString))
    }
}

/**
 * DSL for configuring an RDFS Property.
 */
class RdfsPropertyDsl(
    private val propertyResource: RdfResource,
    private val graphDsl: GraphDsl,
    private val resolveIri: (String) -> Iri
) {
    /**
     * Add an rdfs:label to the property.
     */
    fun label(value: String, language: String? = null) {
        val literal = if (language != null) {
            lang(value, language)
        } else {
            string(value)
        }
        graphDsl.triple(propertyResource, RDFS.label, literal)
    }
    
    /**
     * Add an rdfs:comment to the property.
     */
    fun comment(value: String, language: String? = null) {
        val literal = if (language != null) {
            lang(value, language)
        } else {
            string(value)
        }
        graphDsl.triple(propertyResource, RDFS.comment, literal)
    }
    
    /**
     * Declare the domain of this property.
     */
    fun domain(domainClass: RdfResource) {
        graphDsl.triple(propertyResource, RDFS.domain, domainClass)
    }
    
    /**
     * Declare the domain of this property (by IRI string).
     */
    fun domain(domainClassIri: String) {
        domain(resolveIri(domainClassIri))
    }
    
    /**
     * Declare multiple domains for this property.
     */
    fun domains(vararg domainClasses: RdfResource) {
        domainClasses.forEach { domain(it) }
    }
    
    /**
     * Declare multiple domains for this property (by IRI strings).
     */
    fun domains(vararg domainClassIris: String) {
        domainClassIris.forEach { domain(it) }
    }
    
    /**
     * Declare the range of this property.
     */
    fun range(rangeClass: RdfResource) {
        graphDsl.triple(propertyResource, RDFS.range, rangeClass)
    }
    
    /**
     * Declare the range of this property (by IRI string).
     */
    fun range(rangeClassIri: String) {
        range(resolveIri(rangeClassIri))
    }
    
    /**
     * Declare multiple ranges for this property.
     */
    fun ranges(vararg rangeClasses: RdfResource) {
        rangeClasses.forEach { range(it) }
    }
    
    /**
     * Declare multiple ranges for this property (by IRI strings).
     */
    fun ranges(vararg rangeClassIris: String) {
        rangeClassIris.forEach { range(it) }
    }
    
    /**
     * Declare that this property is a subproperty of another property.
     */
    fun subPropertyOf(superProperty: RdfResource) {
        graphDsl.triple(propertyResource, RDFS.subPropertyOf, superProperty)
    }
    
    /**
     * Declare that this property is a subproperty of another property (by IRI string).
     */
    fun subPropertyOf(superPropertyIri: String) {
        subPropertyOf(resolveIri(superPropertyIri))
    }
    
    /**
     * Add an rdfs:seeAlso link.
     */
    fun seeAlso(iri: Iri) {
        graphDsl.triple(propertyResource, RDFS.seeAlso, iri)
    }
    
    /**
     * Add an rdfs:seeAlso link (by IRI string).
     */
    fun seeAlso(iriString: String) {
        seeAlso(resolveIri(iriString))
    }
    
    /**
     * Add an rdfs:isDefinedBy link.
     */
    fun isDefinedBy(iri: Iri) {
        graphDsl.triple(propertyResource, RDFS.isDefinedBy, iri)
    }
    
    /**
     * Add an rdfs:isDefinedBy link (by IRI string).
     */
    fun isDefinedBy(iriString: String) {
        isDefinedBy(resolveIri(iriString))
    }
}

/**
 * DSL for configuring an RDFS Datatype.
 */
class RdfsDatatypeDsl(
    private val datatypeResource: RdfResource,
    private val graphDsl: GraphDsl,
    private val resolveIri: (String) -> Iri
) {
    /**
     * Add an rdfs:label to the datatype.
     */
    fun label(value: String, language: String? = null) {
        val literal = if (language != null) {
            lang(value, language)
        } else {
            string(value)
        }
        graphDsl.triple(datatypeResource, RDFS.label, literal)
    }
    
    /**
     * Add an rdfs:comment to the datatype.
     */
    fun comment(value: String, language: String? = null) {
        val literal = if (language != null) {
            lang(value, language)
        } else {
            string(value)
        }
        graphDsl.triple(datatypeResource, RDFS.comment, literal)
    }
    
    /**
     * Add an rdfs:seeAlso link.
     */
    fun seeAlso(iri: Iri) {
        graphDsl.triple(datatypeResource, RDFS.seeAlso, iri)
    }
    
    /**
     * Add an rdfs:seeAlso link (by IRI string).
     */
    fun seeAlso(iriString: String) {
        seeAlso(resolveIri(iriString))
    }
    
    /**
     * Add an rdfs:isDefinedBy link.
     */
    fun isDefinedBy(iri: Iri) {
        graphDsl.triple(datatypeResource, RDFS.isDefinedBy, iri)
    }
    
    /**
     * Add an rdfs:isDefinedBy link (by IRI string).
     */
    fun isDefinedBy(iriString: String) {
        isDefinedBy(resolveIri(iriString))
    }
}

/**
 * Top-level function to create an RDFS ontology using the DSL.
 */
fun rdfs(block: RdfsDsl.() -> Unit): MutableRdfGraph {
    val dsl = RdfsDsl()
    dsl.apply(block)
    return dsl.build()
}

