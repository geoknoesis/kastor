package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * RDF (Resource Description Framework) vocabulary constants.
 *
 * This object exposes the IRIs from `http://www.w3.org/1999/02/22-rdf-syntax-ns#`
 * that the RDF 1.2 specification defines. Members added in RDF 1.2 are
 * documented inline.
 */
object RDF : Vocabulary {
    override val namespace: String = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    override val prefix: String = "rdf"

    // Core classes and properties
    val langString: Iri by lazy { term("langString") }

    /**
     * `rdf:dirLangString` (RDF 1.2). Datatype for language-tagged strings that
     * carry an explicit base direction (`ltr` / `rtl`).
     */
    val dirLangString: Iri by lazy { term("dirLangString") }

    val type: Iri by lazy { term("type") }

    /**
     * `rdf:Statement` (RDF 1.0/1.1 reification). Deprecated in RDF 1.2; prefer
     * a triple term referenced via [reifies].
     */
    @Deprecated(
        message = "Legacy RDF 1.1 reification class. Prefer rdf:reifies + a triple term in RDF 1.2.",
        replaceWith = ReplaceWith("RDF.reifies"),
    )
    val Statement: Iri by lazy { term("Statement") }

    /**
     * `rdf:subject` (RDF 1.0/1.1 reification). Deprecated in RDF 1.2; the new
     * idiomatic pattern is `_:r rdf:reifies <<( s p o )>>`, which makes the
     * subject directly accessible inside the triple term.
     */
    @Deprecated(
        message = "Legacy RDF 1.1 reification property. Prefer rdf:reifies + a triple term in RDF 1.2.",
        replaceWith = ReplaceWith("RDF.reifies"),
    )
    val subject: Iri by lazy { term("subject") }

    /**
     * `rdf:predicate` (RDF 1.0/1.1 reification). Deprecated in RDF 1.2.
     */
    @Deprecated(
        message = "Legacy RDF 1.1 reification property. Prefer rdf:reifies + a triple term in RDF 1.2.",
        replaceWith = ReplaceWith("RDF.reifies"),
    )
    val predicate: Iri by lazy { term("predicate") }

    /**
     * `rdf:object` (RDF 1.0/1.1 reification). Deprecated in RDF 1.2.
     */
    @Deprecated(
        message = "Legacy RDF 1.1 reification property. Prefer rdf:reifies + a triple term in RDF 1.2.",
        replaceWith = ReplaceWith("RDF.reifies"),
    )
    @Suppress("BuiltinNameOverride")
    val `object`: Iri by lazy { term("object") }

    /**
     * `rdf:reifies` (RDF 1.2). Property linking a *reifier* (an IRI or blank
     * node) to the triple term it names. The reifier is the subject onto which
     * metadata about the triple is attached.
     *
     * ```turtle
     * _:r rdf:reifies <<( :alice :age 30 )>> ;
     *     ex:certainty "0.9"^^xsd:decimal .
     * ```
     */
    val reifies: Iri by lazy { term("reifies") }

    /**
     * `rdf:reifier` (RDF 1.2). The class of resources that act as reifiers in
     * the [reifies] pattern.
     */
    val reifier: Iri by lazy { term("reifier") }

    /**
     * `rdf:TripleTerm` (RDF 1.2). The class of all triple terms.
     */
    val TripleTerm: Iri by lazy { term("TripleTerm") }

    /**
     * `rdf:HTML` (RDF 1.1+). Datatype for fragments of HTML5.
     */
    val HTML: Iri by lazy { term("HTML") }

    /**
     * `rdf:JSON` (RDF 1.2). Datatype for JSON literals.
     */
    val JSON: Iri by lazy { term("JSON") }

    /**
     * `rdf:CompoundLiteral` (RDF 1.2 working draft). Class for compound
     * literals used in some directional language string constructions.
     */
    val CompoundLiteral: Iri by lazy { term("CompoundLiteral") }

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
