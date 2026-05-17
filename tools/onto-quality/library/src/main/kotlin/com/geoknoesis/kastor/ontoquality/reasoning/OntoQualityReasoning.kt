package com.geoknoesis.kastor.ontoquality.reasoning

import com.geoknoesis.kastor.ontoquality.QualityChecker
import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.hermit.HermitReasonerProvider
import com.geoknoesis.kastor.rdf.jena.JenaBridge
import com.geoknoesis.kastor.rdf.jena.reasoning.JenaReasonerProvider
import com.geoknoesis.kastor.rdf.reasoning.ReasonerConfig
import com.geoknoesis.kastor.rdf.reasoning.ReasonerType
import com.geoknoesis.kastor.rdf.reasoning.ReasoningResult
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity

/** Shape IRI for [BundledCatalogs.OOPS_PITFALL_REGISTRY] metadata and synthetic inconsistency rows. */
internal const val KASTOR_DOC_K07_SHAPE_URI: String =
    "http://example.org/owl-quality-shacl#KastorDoc_K07"

/**
 * High-level reasoning mode for onto-quality **before** SHACL validation (v0.4).
 *
 * **Jena** profiles align with the Jena-based SHACL stack; **HermiT** uses OWL 2 DL (OWL API, pinned with HermiT) in `:rdf:reasoning-hermit`.
 */
enum class OntoQualityReasoningProfile {
    /** Validate the asserted graph only (default). */
    NONE,

    /** RDFS rules via Jena (subClass → instance typing, subProperty, domain, range, etc.). */
    RDFS,

    /**
     * OWL “Micro” / lightweight OWL (Jena `ReasonerType.OWL_EL` binding).
     * Not full OWL 2 DL; suitable for simple ontologies.
     */
    OWL_MICRO,

    /** OWL 2 DL materialization via HermiT (slower; requires `:rdf:reasoning-hermit` on the classpath). */
    HERMIT,
}

internal fun OntoQualityReasoningProfile.toReasonerConfigOrNull(): ReasonerConfig? =
    when (this) {
        OntoQualityReasoningProfile.NONE -> null
        OntoQualityReasoningProfile.RDFS -> ReasonerConfig.rdfs()
        OntoQualityReasoningProfile.OWL_MICRO ->
            ReasonerConfig(
                reasonerType = ReasonerType.OWL_EL,
            )
        OntoQualityReasoningProfile.HERMIT -> ReasonerConfig.hermit()
    }

/**
 * v0.4: expand a graph with optional RDFS / OWL-micro (Jena) or OWL DL (HermiT) before [QualityChecker.check].
 */
object OntoQualityReasoning {
    private val jena = JenaReasonerProvider()
    private val hermit = HermitReasonerProvider()

    fun supports(profile: OntoQualityReasoningProfile): Boolean {
        val c = profile.toReasonerConfigOrNull() ?: return true
        return when (c.reasonerType) {
            ReasonerType.HERMIT -> hermit.isSupported(ReasonerType.HERMIT)
            else -> jena.isSupported(c.reasonerType)
        }
    }

    fun expand(ontology: RdfGraph, profile: OntoQualityReasoningProfile): RdfGraph =
        materializeWithReasoning(ontology, profile.toReasonerConfigOrNull()).graph

    /**
     * Lower-level hook for custom [ReasonerConfig] (timeouts, rule sets) when you depend on `:rdf:reasoning`.
     * For [ReasonerType.HERMIT], `:rdf:reasoning-hermit` must be on the classpath.
     */
    fun expand(ontology: RdfGraph, config: ReasonerConfig): RdfGraph =
        materializeWithReasoning(ontology, config).graph

    /**
     * Single [com.geoknoesis.kastor.rdf.reasoning.RdfReasoner.reason] call plus asserted+inferred merge.
     * When [config] is null, returns the input graph and no [ReasoningResult].
     */
    fun materializeWithReasoning(
        ontology: RdfGraph,
        config: ReasonerConfig?,
    ): MaterializeWithReasoningResult {
        if (config == null) {
            return MaterializeWithReasoningResult(graph = ontology, reasoningResult = null)
        }
        val reasoner =
            when (config.reasonerType) {
                ReasonerType.HERMIT -> {
                    require(hermit.isSupported(ReasonerType.HERMIT)) { "HermiT provider not available" }
                    hermit.createReasoner(config)
                }
                else -> {
                    require(jena.isSupported(config.reasonerType)) {
                        "onto-quality Jena reasoning unsupported profile ${config.reasonerType}. " +
                            "Supported: ${jena.getSupportedTypes()}"
                    }
                    jena.createReasoner(config)
                }
            }
        val result = reasoner.reason(ontology)
        val out = JenaBridge.createEmptyModel()
        out.addTriples(ontology.getTriples())
        out.addTriples(result.inferredTriples)
        return MaterializeWithReasoningResult(graph = out, reasoningResult = result)
    }

    internal fun inconsistencyViolationsForReport(result: ReasoningResult): List<ValidationViolation> {
        val cc = result.consistencyCheck
        if (cc.isConsistent) return emptyList()
        if (cc.inconsistencies.isNotEmpty()) {
            return cc.inconsistencies.map { inc ->
                ValidationViolation(
                    severity = ViolationSeverity.ERROR,
                    constraint =
                        ShaclConstraint(
                            constraintType = ConstraintType.CUSTOM_CONSTRAINT,
                            message = "OWL reasoning preflight (consistency check)",
                            severity = ViolationSeverity.ERROR,
                        ),
                    focusNode = inc.affectedResources.firstOrNull() ?: Iri("urn:kastor:ontology"),
                    message = inc.description,
                    shapeUri = KASTOR_DOC_K07_SHAPE_URI,
                    violationCode = "K07",
                )
            }
        }
        return listOf(
            ValidationViolation(
                severity = ViolationSeverity.ERROR,
                constraint =
                    ShaclConstraint(
                        constraintType = ConstraintType.CUSTOM_CONSTRAINT,
                        message = "OWL reasoning preflight (consistency check)",
                        severity = ViolationSeverity.ERROR,
                    ),
                focusNode = Iri("urn:kastor:ontology"),
                message = "Reasoner reports an inconsistent ontology (no detailed explanation).",
                shapeUri = KASTOR_DOC_K07_SHAPE_URI,
                violationCode = "K07",
            ),
        )
    }
}

data class MaterializeWithReasoningResult(
    val graph: RdfGraph,
    val reasoningResult: ReasoningResult?,
)
