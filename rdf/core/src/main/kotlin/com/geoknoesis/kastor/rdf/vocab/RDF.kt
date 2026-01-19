package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * RDF (Resource Description Framework) vocabulary constants.
 * Provides commonly used RDF vocabulary IRIs.
 */
object RDF : Vocabulary {
    override val namespace: String = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    override val prefix: String = "rdf"
    
    // Core classes and properties
    val langString: Iri by lazy { term("langString") }
    val type: Iri by lazy { term("type") }
    val subject: Iri by lazy { term("subject") }
    val predicate: Iri by lazy { term("predicate") }
    val `object`: Iri by lazy { term("object") }
    val Statement: Iri by lazy { term("Statement") }
    val List: Iri by lazy { term("List") }
    val first: Iri by lazy { term("first") }
    val rest: Iri by lazy { term("rest") }
    val nil: Iri by lazy { term("nil") }
    val value: Iri by lazy { term("value") }
    val Bag: Iri by lazy { term("Bag") }
    val Seq: Iri by lazy { term("Seq") }
    val Alt: Iri by lazy { term("Alt") }
    val Property: Iri by lazy { term("Property") }
    
    // Container membership properties
    val _1: Iri by lazy { term("_1") }
    val _2: Iri by lazy { term("_2") }
    val _3: Iri by lazy { term("_3") }
    val _4: Iri by lazy { term("_4") }
    val _5: Iri by lazy { term("_5") }
}









