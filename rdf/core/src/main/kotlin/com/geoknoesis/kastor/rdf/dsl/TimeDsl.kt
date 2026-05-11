package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.lit
import com.geoknoesis.kastor.rdf.vocab.TIME
import com.geoknoesis.kastor.rdf.vocab.XSD

/**
 * [OWL-Time](https://www.w3.org/TR/owl-time/) temporal relation helpers.
 */
fun TripleDsl.time(block: TimeTripleBuilder.() -> Unit) {
    TimeTripleBuilder(triples).apply(block)
}

fun GraphDsl.time(block: TimeTripleBuilder.() -> Unit) {
    TimeTripleBuilder(triples).apply(block)
}

class TimeTripleBuilder(private val out: MutableList<RdfTriple>) {

    infix fun RdfResource.before(other: RdfResource) {
        out.add(RdfTriple(this, TIME.before, other))
    }

    infix fun RdfResource.after(other: RdfResource) {
        out.add(RdfTriple(this, TIME.after, other))
    }

    infix fun RdfResource.during(other: RdfResource) {
        out.add(RdfTriple(this, TIME.during, other))
    }

    infix fun RdfResource.inside(other: RdfResource) {
        out.add(RdfTriple(this, TIME.inside, other))
    }

    infix fun RdfResource.hasBeginning(instant: RdfResource) {
        out.add(RdfTriple(this, TIME.hasBeginning, instant))
    }

    infix fun RdfResource.hasEnd(instant: RdfResource) {
        out.add(RdfTriple(this, TIME.hasEnd, instant))
    }

    infix fun RdfResource.hasTime(temporal: RdfResource) {
        out.add(RdfTriple(this, TIME.hasTime, temporal))
    }

    fun RdfResource.inXSDDateTimeStamp(iso: String) {
        out.add(RdfTriple(this, TIME.inXSDDateTimeStamp, lit(iso, XSD.dateTimeStamp)))
    }

    fun RdfResource.inXSDDateTime(iso: String) {
        out.add(RdfTriple(this, TIME.inXSDDateTime, lit(iso, XSD.dateTime)))
    }

    fun RdfResource.numericDuration(value: Double) {
        out.add(RdfTriple(this, TIME.numericDuration, lit(value.toString(), XSD.decimal)))
    }

    infix fun RdfResource.unitType(unit: RdfResource) {
        out.add(RdfTriple(this, TIME.unitType, unit))
    }
}
