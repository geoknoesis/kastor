package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * OWL (Web Ontology Language) vocabulary.
 * A vocabulary for defining ontologies and complex relationships.
 * 
 * @see <a href="https://www.w3.org/TR/owl2-overview/">OWL 2 Web Ontology Language</a>
 */
object OWL : Vocabulary {
    override val namespace: String = "http://www.w3.org/2002/07/owl#"
    override val prefix: String = "owl"
    
    // Core classes
    val AllDifferent: Iri by lazy { term("AllDifferent") }
    val AllDisjointClasses: Iri by lazy { term("AllDisjointClasses") }
    val AllDisjointProperties: Iri by lazy { term("AllDisjointProperties") }
    val Annotation: Iri by lazy { term("Annotation") }
    val AnnotationProperty: Iri by lazy { term("AnnotationProperty") }
    val AsymmetricProperty: Iri by lazy { term("AsymmetricProperty") }
    val Axiom: Iri by lazy { term("Axiom") }
    val Class: Iri by lazy { term("Class") }
    val DataRange: Iri by lazy { term("DataRange") }
    val DatatypeProperty: Iri by lazy { term("DatatypeProperty") }
    val DeprecatedClass: Iri by lazy { term("DeprecatedClass") }
    val DeprecatedProperty: Iri by lazy { term("DeprecatedProperty") }
    val FunctionalProperty: Iri by lazy { term("FunctionalProperty") }
    val InverseFunctionalProperty: Iri by lazy { term("InverseFunctionalProperty") }
    val IrreflexiveProperty: Iri by lazy { term("IrreflexiveProperty") }
    val NamedIndividual: Iri by lazy { term("NamedIndividual") }
    val NegativePropertyAssertion: Iri by lazy { term("NegativePropertyAssertion") }
    val ObjectProperty: Iri by lazy { term("ObjectProperty") }
    val Ontology: Iri by lazy { term("Ontology") }
    val OntologyProperty: Iri by lazy { term("OntologyProperty") }
    val ReflexiveProperty: Iri by lazy { term("ReflexiveProperty") }
    val Restriction: Iri by lazy { term("Restriction") }
    val SymmetricProperty: Iri by lazy { term("SymmetricProperty") }
    val Thing: Iri by lazy { term("Thing") }
    val TransitiveProperty: Iri by lazy { term("TransitiveProperty") }
    
    // Core properties
    val allValuesFrom: Iri by lazy { term("allValuesFrom") }
    val annotatedProperty: Iri by lazy { term("annotatedProperty") }
    val annotatedSource: Iri by lazy { term("annotatedSource") }
    val annotatedTarget: Iri by lazy { term("annotatedTarget") }
    val assertionProperty: Iri by lazy { term("assertionProperty") }
    val backwardCompatibleWith: Iri by lazy { term("backwardCompatibleWith") }
    val bottomDataProperty: Iri by lazy { term("bottomDataProperty") }
    val bottomObjectProperty: Iri by lazy { term("bottomObjectProperty") }
    val cardinality: Iri by lazy { term("cardinality") }
    val complementOf: Iri by lazy { term("complementOf") }
    val datatypeComplementOf: Iri by lazy { term("datatypeComplementOf") }
    val differentFrom: Iri by lazy { term("differentFrom") }
    val disjointUnionOf: Iri by lazy { term("disjointUnionOf") }
    val disjointWith: Iri by lazy { term("disjointWith") }
    val distinctMembers: Iri by lazy { term("distinctMembers") }
    val equivalentClass: Iri by lazy { term("equivalentClass") }
    val equivalentProperty: Iri by lazy { term("equivalentProperty") }
    val hasKey: Iri by lazy { term("hasKey") }
    val hasSelf: Iri by lazy { term("hasSelf") }
    val hasValue: Iri by lazy { term("hasValue") }
    val imports: Iri by lazy { term("imports") }
    val incompatibleWith: Iri by lazy { term("incompatibleWith") }
    val intersectionOf: Iri by lazy { term("intersectionOf") }
    val inverseOf: Iri by lazy { term("inverseOf") }
    val maxCardinality: Iri by lazy { term("maxCardinality") }
    val maxQualifiedCardinality: Iri by lazy { term("maxQualifiedCardinality") }
    val members: Iri by lazy { term("members") }
    val minCardinality: Iri by lazy { term("minCardinality") }
    val minQualifiedCardinality: Iri by lazy { term("minQualifiedCardinality") }
    val onClass: Iri by lazy { term("onClass") }
    val onDataRange: Iri by lazy { term("onDataRange") }
    val onDatatype: Iri by lazy { term("onDatatype") }
    val onProperties: Iri by lazy { term("onProperties") }
    val onProperty: Iri by lazy { term("onProperty") }
    val oneOf: Iri by lazy { term("oneOf") }
    val priorVersion: Iri by lazy { term("priorVersion") }
    val propertyChainAxiom: Iri by lazy { term("propertyChainAxiom") }
    val propertyDisjointWith: Iri by lazy { term("propertyDisjointWith") }
    val qualifiedCardinality: Iri by lazy { term("qualifiedCardinality") }
    val sameAs: Iri by lazy { term("sameAs") }
    val someValuesFrom: Iri by lazy { term("someValuesFrom") }
    val sourceIndividual: Iri by lazy { term("sourceIndividual") }
    val targetIndividual: Iri by lazy { term("targetIndividual") }
    val targetValue: Iri by lazy { term("targetValue") }
    val topDataProperty: Iri by lazy { term("topDataProperty") }
    val topObjectProperty: Iri by lazy { term("topObjectProperty") }
    val unionOf: Iri by lazy { term("unionOf") }
    val versionInfo: Iri by lazy { term("versionInfo") }
    val versionIRI: Iri by lazy { term("versionIRI") }
}
