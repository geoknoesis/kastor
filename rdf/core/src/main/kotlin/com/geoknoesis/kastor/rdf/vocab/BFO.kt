package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * Basic Formal Ontology (BFO 2.0) class and core relation IRIs under the OBO PURL scheme.
 *
 * Kotlin names follow the BFO specification labels (e.g. [materialEntity]); IRIs use official
 * `BFO_…` local names. Use with the triple / graph DSL: `instance is BFO.materialEntity`,
 * `part has BFO.partOf with whole`, etc.
 *
 * @see <a href="https://github.com/BFO-ontology/BFO">BFO repository</a>
 * @see <a href="http://purl.obolibrary.org/obo/bfo.owl">bfo.owl</a>
 */
object BFO {
    // --- Root ---
    val entity: Iri by lazy { OBO.term("BFO_0000001") }

    // --- Continuant branch ---
    val continuant: Iri by lazy { OBO.term("BFO_0000002") }
    val independentContinuant: Iri by lazy { OBO.term("BFO_0000004") }
    val spatialRegion: Iri by lazy { OBO.term("BFO_0000006") }
    val specificallyDependentContinuant: Iri by lazy { OBO.term("BFO_0000020") }
    val genericallyDependentContinuant: Iri by lazy { OBO.term("BFO_0000031") }
    val immaterialEntity: Iri by lazy { OBO.term("BFO_0000141") }
    val continuantFiatBoundary: Iri by lazy { OBO.term("BFO_0000140") }
    val oneDimensionalContinuantFiatBoundary: Iri by lazy { OBO.term("BFO_0000142") }
    val twoDimensionalContinuantFiatBoundary: Iri by lazy { OBO.term("BFO_0000146") }
    val zeroDimensionalContinuantFiatBoundary: Iri by lazy { OBO.term("BFO_0000147") }

    val quality: Iri by lazy { OBO.term("BFO_0000019") }
    val relationalQuality: Iri by lazy { OBO.term("BFO_0000145") }
    val realizableEntity: Iri by lazy { OBO.term("BFO_0000017") }
    val disposition: Iri by lazy { OBO.term("BFO_0000016") }
    val function: Iri by lazy { OBO.term("BFO_0000034") }
    val role: Iri by lazy { OBO.term("BFO_0000023") }

    val materialEntity: Iri by lazy { OBO.term("BFO_0000040") }
    val fiatObjectPart: Iri by lazy { OBO.term("BFO_0000024") }
    val objectAggregate: Iri by lazy { OBO.term("BFO_0000027") }
    /** BFO "object": maximal causally unified material entity (`BFO_0000030`). */
    val bfoObject: Iri by lazy { OBO.term("BFO_0000030") }
    val site: Iri by lazy { OBO.term("BFO_0000029") }

    val zeroDimensionalSpatialRegion: Iri by lazy { OBO.term("BFO_0000018") }
    val oneDimensionalSpatialRegion: Iri by lazy { OBO.term("BFO_0000026") }
    val twoDimensionalSpatialRegion: Iri by lazy { OBO.term("BFO_0000009") }
    val threeDimensionalSpatialRegion: Iri by lazy { OBO.term("BFO_0000028") }

    // --- Occurrent branch ---
    val occurrent: Iri by lazy { OBO.term("BFO_0000003") }
    val temporalRegion: Iri by lazy { OBO.term("BFO_0000008") }
    val spatiotemporalRegion: Iri by lazy { OBO.term("BFO_0000011") }
    val process: Iri by lazy { OBO.term("BFO_0000015") }
    val processBoundary: Iri by lazy { OBO.term("BFO_0000035") }
    val processProfile: Iri by lazy { OBO.term("BFO_0000144") }
    val history: Iri by lazy { OBO.term("BFO_0000182") }
    val oneDimensionalTemporalRegion: Iri by lazy { OBO.term("BFO_0000038") }
    val zeroDimensionalTemporalRegion: Iri by lazy { OBO.term("BFO_0000148") }

    // --- Core relations (declared in RO / OBO; same PURL namespace) ---
    /** Continuant or occurrent parthood (`BFO_0000050`). */
    val partOf: Iri by lazy { OBO.term("BFO_0000050") }

    /** Inverse of [partOf] (`BFO_0000051`). */
    val hasPart: Iri by lazy { OBO.term("BFO_0000051") }
}
