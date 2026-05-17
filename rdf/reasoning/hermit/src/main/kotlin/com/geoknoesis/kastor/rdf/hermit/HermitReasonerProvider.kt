package com.geoknoesis.kastor.rdf.hermit

import com.geoknoesis.kastor.rdf.reasoning.ReasonerCapabilities
import com.geoknoesis.kastor.rdf.reasoning.ReasonerConfig
import com.geoknoesis.kastor.rdf.reasoning.ReasonerType
import com.geoknoesis.kastor.rdf.reasoning.RdfReasoner
import com.geoknoesis.kastor.rdf.reasoning.RdfReasonerProvider
import com.geoknoesis.kastor.rdf.reasoning.PerformanceProfile

/**
 * Registers **HermiT** (OWL 2 DL) via **OWL API 4.x**, materializing inferred axioms as RDF triples.
 *
 * Only [ReasonerType.HERMIT] is supported here; use Jena/RDF4J providers for RDFS / OWL Micro.
 */
class HermitReasonerProvider : RdfReasonerProvider {

    override fun getType(): String = "hermit"

    override val name: String = "HermiT (OWL API)"

    override val version: String = "1.4.5.x"

    override fun createReasoner(config: ReasonerConfig): RdfReasoner {
        require(config.reasonerType == ReasonerType.HERMIT) {
            "HermitReasonerProvider only supports ReasonerType.HERMIT, got ${config.reasonerType}"
        }
        return HermitRdfReasoner(config)
    }

    override fun getCapabilities(): ReasonerCapabilities =
        ReasonerCapabilities(
            supportedTypes = setOf(ReasonerType.HERMIT),
            supportsIncrementalReasoning = false,
            supportsCustomRules = false,
            supportsExplanation = false,
            supportsConsistencyChecking = true,
            supportsClassification = true,
            typicalPerformance = PerformanceProfile.SLOW,
        )

    override fun getSupportedTypes(): List<ReasonerType> = listOf(ReasonerType.HERMIT)

    override fun isSupported(type: ReasonerType): Boolean = type == ReasonerType.HERMIT
}
