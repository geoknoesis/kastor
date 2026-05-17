package com.geoknoesis.kastor.ontoquality.embed

import com.geoknoesis.kastor.rdf.Iri

object EnrichmentVocabulary {
    const val NS = "http://example.org/owl-quality-shacl#"

    val semanticallyCloseTo: Iri = Iri("${NS}semanticallyCloseTo")
    val Enrichment: Iri = Iri("${NS}Enrichment")
    val model: Iri = Iri("${NS}model")
    val modelHash: Iri = Iri("${NS}modelHash")
    val threshold: Iri = Iri("${NS}threshold")
    val tokenizer: Iri = Iri("${NS}tokenizer")
    val timestamp: Iri = Iri("${NS}timestamp")
    val entitiesProcessed: Iri = Iri("${NS}entitiesProcessed")
    val pairsAboveThreshold: Iri = Iri("${NS}pairsAboveThreshold")
    val labelDefinitionDriftScore: Iri = Iri("${NS}labelDefinitionDriftScore")
}
