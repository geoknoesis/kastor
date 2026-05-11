package com.geoknoesis.kastor.rdf.vocab

/**
 * OBO Foundry PURL namespace shared by BFO, RO, CHEBI, Uberon, and other OBO ontologies.
 *
 * Use [BFO] and [RO] for curated term constants, or [term] for ad hoc `CHEBI_…` / `GO_…` IDs.
 *
 * @see <a href="http://purl.obolibrary.org/obo/bfo.owl">BFO</a>
 * @see <a href="http://purl.obolibrary.org/obo/ro.owl">Relation Ontology</a>
 */
object OBO : Vocabulary {
    override val namespace: String = "http://purl.obolibrary.org/obo/"
    override val prefix: String = "obo"
}
