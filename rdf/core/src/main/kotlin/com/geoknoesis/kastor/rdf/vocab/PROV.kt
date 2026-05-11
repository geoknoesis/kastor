package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * W3C [PROV-O](https://www.w3.org/TR/prov-o/) vocabulary (PROV namespace).
 *
 * Use with `rdf:type` for [Entity], [Activity], [Agent], and the `prov:` QName prefix in triple/graph DSL blocks.
 *
 * @see <a href="http://www.w3.org/ns/prov#">PROV namespace</a>
 */
object PROV : Vocabulary {
    override val namespace: String = "http://www.w3.org/ns/prov#"
    override val prefix: String = "prov"

    // --- Core classes ---
    val Entity: Iri by lazy { term("Entity") }
    val Activity: Iri by lazy { term("Activity") }
    val Agent: Iri by lazy { term("Agent") }
    val Location: Iri by lazy { term("Location") }
    val Role: Iri by lazy { term("Role") }
    val Bundle: Iri by lazy { term("Bundle") }
    val Collection: Iri by lazy { term("Collection") }
    val Plan: Iri by lazy { term("Plan") }
    val Organization: Iri by lazy { term("Organization") }
    val Person: Iri by lazy { term("Person") }
    val SoftwareAgent: Iri by lazy { term("SoftwareAgent") }

    // --- Starting / ending / times (datatype properties; also used by [ProvTripleBuilder]) ---
    val startedAtTime: Iri by lazy { term("startedAtTime") }
    val endedAtTime: Iri by lazy { term("endedAtTime") }
    val generatedAtTime: Iri by lazy { term("generatedAtTime") }
    val invalidatedAtTime: Iri by lazy { term("invalidatedAtTime") }

    /** Human-readable name in provenance records (`prov:label`). */
    val label: Iri by lazy { term("label") }

    /** Literal value on qualified structures (`prov:value`). */
    val value: Iri by lazy { term("value") }

    // --- Core starting-point relations ---
    val wasGeneratedBy: Iri by lazy { term("wasGeneratedBy") }
    val used: Iri by lazy { term("used") }
    val generated: Iri by lazy { term("generated") }
    val wasInvalidatedBy: Iri by lazy { term("wasInvalidatedBy") }
    val invalidated: Iri by lazy { term("invalidated") }

    val wasAssociatedWith: Iri by lazy { term("wasAssociatedWith") }
    val wasAttributedTo: Iri by lazy { term("wasAttributedTo") }
    val actedOnBehalfOf: Iri by lazy { term("actedOnBehalfOf") }

    val wasDerivedFrom: Iri by lazy { term("wasDerivedFrom") }
    val wasRevisionOf: Iri by lazy { term("wasRevisionOf") }
    val wasQuotedFrom: Iri by lazy { term("wasQuotedFrom") }
    val hadPrimarySource: Iri by lazy { term("hadPrimarySource") }

    val specializationOf: Iri by lazy { term("specializationOf") }
    val alternateOf: Iri by lazy { term("alternateOf") }
    val hadMember: Iri by lazy { term("hadMember") }

    val wasInformedBy: Iri by lazy { term("wasInformedBy") }
    val wasStartedBy: Iri by lazy { term("wasStartedBy") }
    val wasEndedBy: Iri by lazy { term("wasEndedBy") }

    val atLocation: Iri by lazy { term("atLocation") }
    val hadPlan: Iri by lazy { term("hadPlan") }

    val influenced: Iri by lazy { term("influenced") }
    val wasInfluencedBy: Iri by lazy { term("wasInfluencedBy") }
}
