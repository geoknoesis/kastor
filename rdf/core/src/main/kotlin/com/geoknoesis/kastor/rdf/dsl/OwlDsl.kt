package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.vocab.RDFS

/**
 * DSL for creating OWL 2 ontologies.
 * Provides a type-safe, natural language syntax for defining OWL classes, properties, restrictions, and axioms.
 * 
 * Example:
 * ```kotlin
 * val ontology = owl {
 *     prefix("ex", "http://example.org/")
 *     
 *     ontology("ex:MyOntology") {
 *         versionInfo("1.0")
 *         imports("http://example.org/other-ontology")
 *     }
 *     
 *     `class`("ex:Person") {
 *         label("Person", "en")
 *         subClassOf("ex:Animal")
 *         equivalentClass {
 *             intersectionOf("ex:Animal", "ex:HasName")
 *         }
 *     }
 *     
 *     objectProperty("ex:hasParent") {
 *         domain("ex:Person")
 *         range("ex:Person")
 *         inverseOf("ex:hasChild")
 *         transitive()
 *     }
 *     
 *     dataProperty("ex:age") {
 *         domain("ex:Person")
 *         range(XSD.integer)
 *         functional()
 *     }
 *     
 *     individual("ex:alice") {
 *         `is`("ex:Person")
 *         has("ex:age") with 30
 *         sameAs("ex:aliceSmith")
 *     }
 * }
 * ```
 */
class OwlDsl {
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
     * Define an OWL Ontology.
     */
    fun ontology(ontologyIri: String, configure: OwlOntologyDsl.() -> Unit = {}) {
        val ontologyResource = resolveIri(ontologyIri)
        graphDsl.triple(ontologyResource, RDF.type, OWL.Ontology)
        val dsl = OwlOntologyDsl(ontologyResource, graphDsl, ::resolveIri)
        dsl.configure()
    }
    
    /**
     * Define an OWL Class.
     */
    fun `class`(classIri: String, configure: OwlClassDsl.() -> Unit = {}) {
        val classResource = resolveIri(classIri)
        graphDsl.triple(classResource, RDF.type, OWL.Class)
        val dsl = OwlClassDsl(classResource, graphDsl, ::resolveIri, ::nextBnode)
        dsl.configure()
    }
    
    /**
     * Define an OWL Object Property.
     */
    fun objectProperty(propertyIri: String, configure: OwlObjectPropertyDsl.() -> Unit = {}) {
        val propertyResource = resolveIri(propertyIri)
        graphDsl.triple(propertyResource, RDF.type, OWL.ObjectProperty)
        val dsl = OwlObjectPropertyDsl(propertyResource, graphDsl, ::resolveIri, ::nextBnode)
        dsl.configure()
    }
    
    /**
     * Define an OWL Datatype Property.
     */
    fun dataProperty(propertyIri: String, configure: OwlDataPropertyDsl.() -> Unit = {}) {
        val propertyResource = resolveIri(propertyIri)
        graphDsl.triple(propertyResource, RDF.type, OWL.DatatypeProperty)
        val dsl = OwlDataPropertyDsl(propertyResource, graphDsl, ::resolveIri, ::nextBnode)
        dsl.configure()
    }
    
    /**
     * Define an OWL Annotation Property.
     */
    fun annotationProperty(propertyIri: String, configure: OwlAnnotationPropertyDsl.() -> Unit = {}) {
        val propertyResource = resolveIri(propertyIri)
        graphDsl.triple(propertyResource, RDF.type, OWL.AnnotationProperty)
        val dsl = OwlAnnotationPropertyDsl(propertyResource, graphDsl, ::resolveIri)
        dsl.configure()
    }
    
    /**
     * Define an OWL Named Individual.
     */
    fun individual(individualIri: String, configure: OwlIndividualDsl.() -> Unit = {}) {
        val individualResource = resolveIri(individualIri)
        graphDsl.triple(individualResource, RDF.type, OWL.NamedIndividual)
        val dsl = OwlIndividualDsl(individualResource, graphDsl, ::resolveIri)
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
 * DSL for configuring an OWL Ontology.
 */
class OwlOntologyDsl(
    private val ontologyResource: RdfResource,
    private val graphDsl: GraphDsl,
    private val resolveIri: (String) -> Iri
) {
    /**
     * Add owl:versionInfo.
     */
    fun versionInfo(value: String) {
        graphDsl.triple(ontologyResource, OWL.versionInfo, string(value))
    }
    
    /**
     * Add owl:versionIRI.
     */
    fun versionIRI(iri: Iri) {
        graphDsl.triple(ontologyResource, OWL.versionIRI, iri)
    }
    
    /**
     * Add owl:versionIRI (by IRI string).
     */
    fun versionIRI(iriString: String) {
        versionIRI(resolveIri(iriString))
    }
    
    /**
     * Add owl:imports.
     */
    fun imports(iri: Iri) {
        graphDsl.triple(ontologyResource, OWL.imports, iri)
    }
    
    /**
     * Add owl:imports (by IRI string).
     */
    fun imports(iriString: String) {
        imports(resolveIri(iriString))
    }
    
    /**
     * Add multiple owl:imports.
     */
    fun imports(iris: List<Iri>) {
        iris.forEach { imports(it) }
    }
    
    /**
     * Add multiple owl:imports (vararg version using strings).
     */
    fun imports(vararg iriStrings: String) {
        iriStrings.forEach { imports(it) }
    }
    
    
    /**
     * Add owl:priorVersion.
     */
    fun priorVersion(iri: Iri) {
        graphDsl.triple(ontologyResource, OWL.priorVersion, iri)
    }
    
    /**
     * Add owl:priorVersion (by IRI string).
     */
    fun priorVersion(iriString: String) {
        priorVersion(resolveIri(iriString))
    }
    
    /**
     * Add owl:backwardCompatibleWith.
     */
    fun backwardCompatibleWith(iri: Iri) {
        graphDsl.triple(ontologyResource, OWL.backwardCompatibleWith, iri)
    }
    
    /**
     * Add owl:backwardCompatibleWith (by IRI string).
     */
    fun backwardCompatibleWith(iriString: String) {
        backwardCompatibleWith(resolveIri(iriString))
    }
    
    /**
     * Add owl:incompatibleWith.
     */
    fun incompatibleWith(iri: Iri) {
        graphDsl.triple(ontologyResource, OWL.incompatibleWith, iri)
    }
    
    /**
     * Add owl:incompatibleWith (by IRI string).
     */
    fun incompatibleWith(iriString: String) {
        incompatibleWith(resolveIri(iriString))
    }
}

/**
 * DSL for configuring an OWL Class.
 */
class OwlClassDsl(
    private val classResource: RdfResource,
    private val graphDsl: GraphDsl,
    private val resolveIri: (String) -> Iri,
    private val nextBnode: (String) -> BlankNode
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
     * Declare that this class is equivalent to another class.
     */
    fun equivalentClass(otherClass: RdfResource) {
        graphDsl.triple(classResource, OWL.equivalentClass, otherClass)
    }
    
    /**
     * Declare that this class is equivalent to another class (by IRI string).
     */
    fun equivalentClass(otherClassIri: String) {
        equivalentClass(resolveIri(otherClassIri))
    }
    
    /**
     * Declare that this class is equivalent to a class expression.
     */
    fun equivalentClass(block: OwlClassExpressionBuilder.() -> RdfResource) {
        val builder = OwlClassExpressionBuilder(graphDsl, resolveIri, nextBnode)
        val expression = builder.block()
        graphDsl.triple(classResource, OWL.equivalentClass, expression)
    }
    
    /**
     * Declare that this class is disjoint with another class.
     */
    fun disjointWith(otherClass: RdfResource) {
        graphDsl.triple(classResource, OWL.disjointWith, otherClass)
    }
    
    /**
     * Declare that this class is disjoint with another class (by IRI string).
     */
    fun disjointWith(otherClassIri: String) {
        disjointWith(resolveIri(otherClassIri))
    }
    
    /**
     * Declare that this class is the complement of another class.
     */
    fun complementOf(otherClass: RdfResource) {
        graphDsl.triple(classResource, OWL.complementOf, otherClass)
    }
    
    /**
     * Declare that this class is the complement of another class (by IRI string).
     */
    fun complementOf(otherClassIri: String) {
        complementOf(resolveIri(otherClassIri))
    }
}

/**
 * DSL for configuring an OWL Object Property.
 */
class OwlObjectPropertyDsl(
    private val propertyResource: RdfResource,
    private val graphDsl: GraphDsl,
    private val resolveIri: (String) -> Iri,
    private val nextBnode: (String) -> BlankNode
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
     * Declare that this property is equivalent to another property.
     */
    fun equivalentProperty(otherProperty: RdfResource) {
        graphDsl.triple(propertyResource, OWL.equivalentProperty, otherProperty)
    }
    
    /**
     * Declare that this property is equivalent to another property (by IRI string).
     */
    fun equivalentProperty(otherPropertyIri: String) {
        equivalentProperty(resolveIri(otherPropertyIri))
    }
    
    /**
     * Declare that this property is the inverse of another property.
     */
    fun inverseOf(otherProperty: RdfResource) {
        graphDsl.triple(propertyResource, OWL.inverseOf, otherProperty)
    }
    
    /**
     * Declare that this property is the inverse of another property (by IRI string).
     */
    fun inverseOf(otherPropertyIri: String) {
        inverseOf(resolveIri(otherPropertyIri))
    }
    
    /**
     * Declare that this property is disjoint with another property.
     */
    fun propertyDisjointWith(otherProperty: RdfResource) {
        graphDsl.triple(propertyResource, OWL.propertyDisjointWith, otherProperty)
    }
    
    /**
     * Declare that this property is disjoint with another property (by IRI string).
     */
    fun propertyDisjointWith(otherPropertyIri: String) {
        propertyDisjointWith(resolveIri(otherPropertyIri))
    }
    
    /**
     * Declare that this property is functional.
     */
    fun functional() {
        graphDsl.triple(propertyResource, RDF.type, OWL.FunctionalProperty)
    }
    
    /**
     * Declare that this property is inverse functional.
     */
    fun inverseFunctional() {
        graphDsl.triple(propertyResource, RDF.type, OWL.InverseFunctionalProperty)
    }
    
    /**
     * Declare that this property is transitive.
     */
    fun transitive() {
        graphDsl.triple(propertyResource, RDF.type, OWL.TransitiveProperty)
    }
    
    /**
     * Declare that this property is symmetric.
     */
    fun symmetric() {
        graphDsl.triple(propertyResource, RDF.type, OWL.SymmetricProperty)
    }
    
    /**
     * Declare that this property is asymmetric.
     */
    fun asymmetric() {
        graphDsl.triple(propertyResource, RDF.type, OWL.AsymmetricProperty)
    }
    
    /**
     * Declare that this property is reflexive.
     */
    fun reflexive() {
        graphDsl.triple(propertyResource, RDF.type, OWL.ReflexiveProperty)
    }
    
    /**
     * Declare that this property is irreflexive.
     */
    fun irreflexive() {
        graphDsl.triple(propertyResource, RDF.type, OWL.IrreflexiveProperty)
    }
    
    /**
     * Add a property chain axiom.
     */
    fun propertyChainAxiom(chain: List<RdfResource>) {
        val listNode = createRdfList(chain)
        graphDsl.triple(propertyResource, OWL.propertyChainAxiom, listNode)
    }
    
    /**
     * Add a property chain axiom (by IRI strings).
     */
    fun propertyChainAxiom(vararg propertyIris: String) {
        propertyChainAxiom(propertyIris.map { resolveIri(it) })
    }
    
    private fun createRdfList(values: List<RdfResource>): RdfTerm {
        if (values.isEmpty()) return RDF.nil
        
        val listHead = nextBnode("list")
        var currentNode = listHead
        
        values.forEachIndexed { index, element ->
            graphDsl.triple(currentNode, RDF.first, element)
            if (index < values.size - 1) {
                val nextNode = nextBnode("list")
                graphDsl.triple(currentNode, RDF.rest, nextNode)
                currentNode = nextNode
            } else {
                graphDsl.triple(currentNode, RDF.rest, RDF.nil)
            }
        }
        
        return listHead
    }
}

/**
 * DSL for configuring an OWL Datatype Property.
 */
class OwlDataPropertyDsl(
    private val propertyResource: RdfResource,
    private val graphDsl: GraphDsl,
    private val resolveIri: (String) -> Iri,
    private val nextBnode: (String) -> BlankNode
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
     * Declare the range of this property.
     */
    fun range(datatype: RdfResource) {
        graphDsl.triple(propertyResource, RDFS.range, datatype)
    }
    
    /**
     * Declare the range of this property (by IRI string).
     */
    fun range(datatypeIri: String) {
        range(resolveIri(datatypeIri))
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
     * Declare that this property is equivalent to another property.
     */
    fun equivalentProperty(otherProperty: RdfResource) {
        graphDsl.triple(propertyResource, OWL.equivalentProperty, otherProperty)
    }
    
    /**
     * Declare that this property is equivalent to another property (by IRI string).
     */
    fun equivalentProperty(otherPropertyIri: String) {
        equivalentProperty(resolveIri(otherPropertyIri))
    }
    
    /**
     * Declare that this property is disjoint with another property.
     */
    fun propertyDisjointWith(otherProperty: RdfResource) {
        graphDsl.triple(propertyResource, OWL.propertyDisjointWith, otherProperty)
    }
    
    /**
     * Declare that this property is disjoint with another property (by IRI string).
     */
    fun propertyDisjointWith(otherPropertyIri: String) {
        propertyDisjointWith(resolveIri(otherPropertyIri))
    }
    
    /**
     * Declare that this property is functional.
     */
    fun functional() {
        graphDsl.triple(propertyResource, RDF.type, OWL.FunctionalProperty)
    }
}

/**
 * DSL for configuring an OWL Annotation Property.
 */
class OwlAnnotationPropertyDsl(
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
}

/**
 * DSL for configuring an OWL Individual.
 */
class OwlIndividualDsl(
    private val individualResource: RdfResource,
    private val graphDsl: GraphDsl,
    private val resolveIri: (String) -> Iri
) {
    /**
     * Declare the type of this individual.
     */
    fun `is`(classResource: RdfResource) {
        graphDsl.triple(individualResource, RDF.type, classResource)
    }
    
    /**
     * Declare the type of this individual (by IRI string).
     */
    fun `is`(classIri: String) {
        `is`(resolveIri(classIri))
    }
    
    /**
     * Declare that this individual is the same as another individual.
     */
    fun sameAs(otherIndividual: RdfResource) {
        graphDsl.triple(individualResource, OWL.sameAs, otherIndividual)
    }
    
    /**
     * Declare that this individual is the same as another individual (by IRI string).
     */
    fun sameAs(otherIndividualIri: String) {
        sameAs(resolveIri(otherIndividualIri))
    }
    
    /**
     * Declare that this individual is different from another individual.
     */
    fun differentFrom(otherIndividual: RdfResource) {
        graphDsl.triple(individualResource, OWL.differentFrom, otherIndividual)
    }
    
    /**
     * Declare that this individual is different from another individual (by IRI string).
     */
    fun differentFrom(otherIndividualIri: String) {
        differentFrom(resolveIri(otherIndividualIri))
    }
    
    /**
     * Add a property assertion using the natural language syntax.
     * Usage: individual("ex:alice") { has(FOAF.name) with "Alice" }
     * Note: The 'with' method must be called on the returned SubjectPredicateChain
     * in the context of the GraphDsl, so use: with(graphDsl) { individual("ex:alice") { has(FOAF.name) with "Alice" } }
     * Or use the direct triple method instead.
     */
    infix fun has(predicate: Iri): SubjectPredicateChain {
        return SubjectPredicateChain(individualResource, predicate)
    }
    
    /**
     * Add a property assertion using the natural language syntax (by IRI string).
     * Usage: individual("ex:alice") { has("ex:name") with "Alice" }
     * Note: The 'with' method must be called on the returned SubjectPredicateChain
     * in the context of the GraphDsl, so use: with(graphDsl) { individual("ex:alice") { has("ex:name") with "Alice" } }
     * Or use the direct triple method instead.
     */
    fun has(predicateIri: String): SubjectPredicateChain {
        return SubjectPredicateChain(individualResource, resolveIri(predicateIri))
    }
    
    /**
     * Add a property assertion directly.
     * Usage: individual("ex:alice") { property(FOAF.name, "Alice") }
     */
    fun property(predicate: Iri, value: RdfTerm) {
        graphDsl.triple(individualResource, predicate, value)
    }
    
    /**
     * Add a property assertion directly (by IRI string).
     * Usage: individual("ex:alice") { property("ex:name", "Alice") }
     */
    fun property(predicateIri: String, value: RdfTerm) {
        property(resolveIri(predicateIri), value)
    }
    
    /**
     * Add a property assertion directly with string value.
     */
    fun property(predicateIri: String, value: String) {
        property(resolveIri(predicateIri), string(value))
    }
    
    /**
     * Add a property assertion directly with int value.
     */
    fun property(predicateIri: String, value: Int) {
        property(resolveIri(predicateIri), value.toLiteral())
    }
}

/**
 * Builder for OWL class expressions (union, intersection, complement, restrictions, etc.).
 */
class OwlClassExpressionBuilder(
    private val graphDsl: GraphDsl,
    private val resolveIri: (String) -> Iri,
    private val nextBnode: (String) -> BlankNode
) {
    /**
     * Create a union of classes.
     */
    fun unionOf(vararg classes: RdfResource): RdfResource {
        val unionNode = nextBnode("union")
        graphDsl.triple(unionNode, RDF.type, OWL.Class)
        val listNode = createRdfList(classes.toList())
        graphDsl.triple(unionNode, OWL.unionOf, listNode)
        return unionNode
    }
    
    /**
     * Create a union of classes (by IRI strings).
     */
    fun unionOf(vararg classIris: String): RdfResource {
        return unionOf(*classIris.map { resolveIri(it) }.toTypedArray())
    }
    
    /**
     * Create an intersection of classes.
     */
    fun intersectionOf(vararg classes: RdfResource): RdfResource {
        val intersectionNode = nextBnode("intersection")
        graphDsl.triple(intersectionNode, RDF.type, OWL.Class)
        val listNode = createRdfList(classes.toList())
        graphDsl.triple(intersectionNode, OWL.intersectionOf, listNode)
        return intersectionNode
    }
    
    /**
     * Create an intersection of classes (by IRI strings).
     */
    fun intersectionOf(vararg classIris: String): RdfResource {
        return intersectionOf(*classIris.map { resolveIri(it) }.toTypedArray())
    }
    
    /**
     * Create a complement of a class.
     */
    fun complementOf(classResource: RdfResource): RdfResource {
        val complementNode = nextBnode("complement")
        graphDsl.triple(complementNode, RDF.type, OWL.Class)
        graphDsl.triple(complementNode, OWL.complementOf, classResource)
        return complementNode
    }
    
    /**
     * Create a complement of a class (by IRI string).
     */
    fun complementOf(classIri: String): RdfResource {
        return complementOf(resolveIri(classIri))
    }
    
    /**
     * Create a oneOf (enumeration) class.
     */
    fun oneOf(vararg individuals: RdfResource): RdfResource {
        val oneOfNode = nextBnode("oneOf")
        graphDsl.triple(oneOfNode, RDF.type, OWL.Class)
        val listNode = createRdfList(individuals.toList())
        graphDsl.triple(oneOfNode, OWL.oneOf, listNode)
        return oneOfNode
    }
    
    /**
     * Create a oneOf (enumeration) class (by IRI strings).
     */
    fun oneOf(vararg individualIris: String): RdfResource {
        return oneOf(*individualIris.map { resolveIri(it) }.toTypedArray())
    }
    
    /**
     * Create a restriction (allValuesFrom, someValuesFrom, hasValue, cardinality, etc.).
     */
    fun restriction(property: RdfResource, configure: OwlRestrictionBuilder.() -> Unit): RdfResource {
        val restrictionNode = nextBnode("restriction")
        graphDsl.triple(restrictionNode, RDF.type, OWL.Restriction)
        graphDsl.triple(restrictionNode, OWL.onProperty, property)
        val builder = OwlRestrictionBuilder(restrictionNode, graphDsl, resolveIri, nextBnode)
        builder.configure()
        return restrictionNode
    }
    
    /**
     * Create a restriction (by property IRI string).
     */
    fun restriction(propertyIri: String, configure: OwlRestrictionBuilder.() -> Unit): RdfResource {
        return restriction(resolveIri(propertyIri), configure)
    }
    
    private fun createRdfList(values: List<RdfResource>): RdfTerm {
        if (values.isEmpty()) return RDF.nil
        
        val listHead = nextBnode("list")
        var currentNode = listHead
        
        values.forEachIndexed { index, element ->
            graphDsl.triple(currentNode, RDF.first, element)
            if (index < values.size - 1) {
                val nextNode = nextBnode("list")
                graphDsl.triple(currentNode, RDF.rest, nextNode)
                currentNode = nextNode
            } else {
                graphDsl.triple(currentNode, RDF.rest, RDF.nil)
            }
        }
        
        return listHead
    }
}

/**
 * Builder for OWL restrictions.
 */
class OwlRestrictionBuilder(
    private val restrictionNode: RdfResource,
    private val graphDsl: GraphDsl,
    private val resolveIri: (String) -> Iri,
    private val nextBnode: (String) -> BlankNode
) {
    /**
     * Add allValuesFrom constraint.
     */
    fun allValuesFrom(classResource: RdfResource) {
        graphDsl.triple(restrictionNode, OWL.allValuesFrom, classResource)
    }
    
    /**
     * Add allValuesFrom constraint (by IRI string).
     */
    fun allValuesFrom(classIri: String) {
        allValuesFrom(resolveIri(classIri))
    }
    
    /**
     * Add someValuesFrom constraint.
     */
    fun someValuesFrom(classResource: RdfResource) {
        graphDsl.triple(restrictionNode, OWL.someValuesFrom, classResource)
    }
    
    /**
     * Add someValuesFrom constraint (by IRI string).
     */
    fun someValuesFrom(classIri: String) {
        someValuesFrom(resolveIri(classIri))
    }
    
    /**
     * Add hasValue constraint.
     */
    fun hasValue(value: RdfTerm) {
        graphDsl.triple(restrictionNode, OWL.hasValue, value)
    }
    
    /**
     * Add cardinality constraint.
     */
    fun cardinality(n: Int) {
        graphDsl.triple(restrictionNode, OWL.cardinality, n.toLiteral())
    }
    
    /**
     * Add minCardinality constraint.
     */
    fun minCardinality(n: Int) {
        graphDsl.triple(restrictionNode, OWL.minCardinality, n.toLiteral())
    }
    
    /**
     * Add maxCardinality constraint.
     */
    fun maxCardinality(n: Int) {
        graphDsl.triple(restrictionNode, OWL.maxCardinality, n.toLiteral())
    }
    
    /**
     * Add qualifiedCardinality constraint.
     */
    fun qualifiedCardinality(n: Int, classResource: RdfResource) {
        graphDsl.triple(restrictionNode, OWL.qualifiedCardinality, n.toLiteral())
        graphDsl.triple(restrictionNode, OWL.onClass, classResource)
    }
    
    /**
     * Add qualifiedCardinality constraint (by IRI string).
     */
    fun qualifiedCardinality(n: Int, classIri: String) {
        qualifiedCardinality(n, resolveIri(classIri))
    }
    
    /**
     * Add minQualifiedCardinality constraint.
     */
    fun minQualifiedCardinality(n: Int, classResource: RdfResource) {
        graphDsl.triple(restrictionNode, OWL.minQualifiedCardinality, n.toLiteral())
        graphDsl.triple(restrictionNode, OWL.onClass, classResource)
    }
    
    /**
     * Add minQualifiedCardinality constraint (by IRI string).
     */
    fun minQualifiedCardinality(n: Int, classIri: String) {
        minQualifiedCardinality(n, resolveIri(classIri))
    }
    
    /**
     * Add maxQualifiedCardinality constraint.
     */
    fun maxQualifiedCardinality(n: Int, classResource: RdfResource) {
        graphDsl.triple(restrictionNode, OWL.maxQualifiedCardinality, n.toLiteral())
        graphDsl.triple(restrictionNode, OWL.onClass, classResource)
    }
    
    /**
     * Add maxQualifiedCardinality constraint (by IRI string).
     */
    fun maxQualifiedCardinality(n: Int, classIri: String) {
        maxQualifiedCardinality(n, resolveIri(classIri))
    }
    
    /**
     * Add hasSelf constraint.
     */
    fun hasSelf(value: Boolean) {
        graphDsl.triple(restrictionNode, OWL.hasSelf, boolean(value))
    }
}

/**
 * Top-level function to create an OWL ontology using the DSL.
 */
fun owl(block: OwlDsl.() -> Unit): MutableRdfGraph {
    val dsl = OwlDsl()
    dsl.apply(block)
    return dsl.build()
}

