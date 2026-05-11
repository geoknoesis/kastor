package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * Selected Relation Ontology (RO) object properties commonly used with BFO.
 * IRIs share the OBO PURL namespace with [BFO] and [OBO].
 *
 * @see <a href="http://purl.obolibrary.org/obo/ro.owl">ro.owl</a>
 */
object RO {
    /** `RO_0000052` — RO label "characteristic of" (IAO alternative term "inheres in"). */
    val inheresIn: Iri by lazy { OBO.term("RO_0000052") }

    /** `RO_0000053` — "has characteristic" / "bearer of". */
    val bearerOf: Iri by lazy { OBO.term("RO_0000053") }

    val participatesIn: Iri by lazy { OBO.term("RO_0000056") }
    val hasParticipant: Iri by lazy { OBO.term("RO_0000057") }

    /** Independent continuant located inside another (`RO_0001025`). */
    val locatedIn: Iri by lazy { OBO.term("RO_0001025") }
}
