package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * SKOS (Simple Knowledge Organization System) vocabulary.
 * A vocabulary for representing knowledge organization systems.
 * 
 * @see <a href="https://www.w3.org/TR/skos-reference/">SKOS Reference</a>
 */
object SKOS : Vocabulary {
    override val namespace: String = "http://www.w3.org/2004/02/skos/core#"
    override val prefix: String = "skos"
    
    // Core classes
    val Concept: Iri by lazy { term("Concept") }
    val ConceptScheme: Iri by lazy { term("ConceptScheme") }
    val Collection: Iri by lazy { term("Collection") }
    val OrderedCollection: Iri by lazy { term("OrderedCollection") }
    
    // Core properties
    val prefLabel: Iri by lazy { term("prefLabel") }
    val altLabel: Iri by lazy { term("altLabel") }
    val hiddenLabel: Iri by lazy { term("hiddenLabel") }
    val definition: Iri by lazy { term("definition") }
    val scopeNote: Iri by lazy { term("scopeNote") }
    val example: Iri by lazy { term("example") }
    val note: Iri by lazy { term("note") }
    val changeNote: Iri by lazy { term("changeNote") }
    val editorialNote: Iri by lazy { term("editorialNote") }
    val historyNote: Iri by lazy { term("historyNote") }
    val semanticRelation: Iri by lazy { term("semanticRelation") }
    val broader: Iri by lazy { term("broader") }
    val narrower: Iri by lazy { term("narrower") }
    val broaderTransitive: Iri by lazy { term("broaderTransitive") }
    val narrowerTransitive: Iri by lazy { term("narrowerTransitive") }
    val related: Iri by lazy { term("related") }
    val broaderMatch: Iri by lazy { term("broaderMatch") }
    val narrowerMatch: Iri by lazy { term("narrowerMatch") }
    val relatedMatch: Iri by lazy { term("relatedMatch") }
    val exactMatch: Iri by lazy { term("exactMatch") }
    val closeMatch: Iri by lazy { term("closeMatch") }
    val mappingRelation: Iri by lazy { term("mappingRelation") }
    val inScheme: Iri by lazy { term("inScheme") }
    val hasTopConcept: Iri by lazy { term("hasTopConcept") }
    val topConceptOf: Iri by lazy { term("topConceptOf") }
    val member: Iri by lazy { term("member") }
    val memberList: Iri by lazy { term("memberList") }
    val notation: Iri by lazy { term("notation") }
}









