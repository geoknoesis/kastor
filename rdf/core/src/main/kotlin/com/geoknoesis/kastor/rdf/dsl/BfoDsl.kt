package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.vocab.BFO
import com.geoknoesis.kastor.rdf.vocab.RO

/**
 * Human-readable BFO / RO triple helpers inside [TripleDsl] or [GraphDsl] blocks.
 *
 * Example:
 * ```kotlin
 * repo.add {
 *   bfo {
 *     cell partOf tissue
 *     tissue partOf organ
 *     quality inheresIn cell
 *     process hasParticipant cell
 *   }
 * }
 * ```
 */
fun TripleDsl.bfo(block: BfoTripleBuilder.() -> Unit) {
    BfoTripleBuilder(triples).apply(block)
}

fun GraphDsl.bfo(block: BfoTripleBuilder.() -> Unit) {
    BfoTripleBuilder(triples).apply(block)
}

/**
 * Collects instance-level assertions using readable BFO/RO-oriented names.
 * Predicates match OBO: [BFO.partOf], [BFO.hasPart], [RO.locatedIn], etc.
 */
class BfoTripleBuilder(private val out: MutableList<RdfTriple>) {

    /** `part` [BFO.partOf] `whole` (continuant or occurrent parthood). */
    infix fun RdfResource.partOf(whole: RdfResource) {
        out.add(RdfTriple(this, BFO.partOf, whole))
    }

    /** `whole` [BFO.hasPart] `part`. */
    infix fun RdfResource.hasPart(part: RdfResource) {
        out.add(RdfTriple(this, BFO.hasPart, part))
    }

    /** `inner` [RO.locatedIn] `outer` (independent continuant location). */
    infix fun RdfResource.locatedIn(outer: RdfResource) {
        out.add(RdfTriple(this, RO.locatedIn, outer))
    }

    /** `continuant` [RO.participatesIn] `process`. */
    infix fun RdfResource.participatesIn(process: RdfResource) {
        out.add(RdfTriple(this, RO.participatesIn, process))
    }

    /** `process` [RO.hasParticipant] `continuant`. */
    infix fun RdfResource.hasParticipant(continuant: RdfResource) {
        out.add(RdfTriple(this, RO.hasParticipant, continuant))
    }

    /** Dependent [RO.inheresIn] bearer (RO "characteristic of"). */
    infix fun RdfResource.inheresIn(bearer: RdfResource) {
        out.add(RdfTriple(this, RO.inheresIn, bearer))
    }

    /** Bearer [RO.bearerOf] dependent (inverse of [inheresIn]). */
    infix fun RdfResource.bearerOf(dependent: RdfResource) {
        out.add(RdfTriple(this, RO.bearerOf, dependent))
    }
}
