package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * [OWL-Time](https://www.w3.org/TR/owl-time/) ontology (`time:`).
 *
 * @see <a href="http://www.w3.org/2006/time#">OWL-Time namespace</a>
 */
object TIME : Vocabulary {
    override val namespace: String = "http://www.w3.org/2006/time#"
    override val prefix: String = "time"

    val Instant: Iri by lazy { term("Instant") }
    val Interval: Iri by lazy { term("Interval") }
    val Duration: Iri by lazy { term("Duration") }
    val TemporalEntity: Iri by lazy { term("TemporalEntity") }
    val ProperInterval: Iri by lazy { term("ProperInterval") }
    val DateTimeInterval: Iri by lazy { term("DateTimeInterval") }

    val before: Iri by lazy { term("before") }
    val after: Iri by lazy { term("after") }
    val inside: Iri by lazy { term("inside") }
    val during: Iri by lazy { term("during") }
    val inXSDDateTimeStamp: Iri by lazy { term("inXSDDateTimeStamp") }
    val inXSDDateTime: Iri by lazy { term("inXSDDateTime") }
    val hasBeginning: Iri by lazy { term("hasBeginning") }
    val hasEnd: Iri by lazy { term("hasEnd") }
    val hasTime: Iri by lazy { term("hasTime") }
    val hasDuration: Iri by lazy { term("hasDuration") }
    val numericDuration: Iri by lazy { term("numericDuration") }
    val unitType: Iri by lazy { term("unitType") }
}
