package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.lang
import com.geoknoesis.kastor.rdf.lit
import com.geoknoesis.kastor.rdf.string
import com.geoknoesis.kastor.rdf.vocab.PROV
import com.geoknoesis.kastor.rdf.vocab.XSD

/**
 * Human-readable [PROV-O](https://www.w3.org/TR/prov-o/) triple helpers inside [TripleDsl] or [GraphDsl] blocks.
 *
 * Example:
 * ```kotlin
 * repo.add {
 *   prov {
 *     dataset wasGeneratedBy extraction
 *     extraction used rawFile
 *     extraction wasAssociatedWith softwareAgent
 *     dataset wasAttributedTo person
 *     extraction.startedAtTime("2024-01-15T10:00:00")
 *   }
 * }
 * ```
 */
fun TripleDsl.prov(block: ProvTripleBuilder.() -> Unit) {
    ProvTripleBuilder(triples).apply(block)
}

fun GraphDsl.prov(block: ProvTripleBuilder.() -> Unit) {
    ProvTripleBuilder(triples).apply(block)
}

/**
 * Collects PROV assertions using [PROV] predicate IRIs.
 */
class ProvTripleBuilder(private val out: MutableList<RdfTriple>) {

    // --- Generation / use / invalidation ---

    /** Entity [PROV.wasGeneratedBy] activity. */
    infix fun RdfResource.wasGeneratedBy(activity: RdfResource) {
        out.add(RdfTriple(this, PROV.wasGeneratedBy, activity))
    }

    /** Activity [PROV.used] entity. */
    infix fun RdfResource.used(entity: RdfResource) {
        out.add(RdfTriple(this, PROV.used, entity))
    }

    /** Activity [PROV.generated] entity. */
    infix fun RdfResource.generated(entity: RdfResource) {
        out.add(RdfTriple(this, PROV.generated, entity))
    }

    /** Activity [PROV.invalidated] entity. */
    infix fun RdfResource.invalidated(entity: RdfResource) {
        out.add(RdfTriple(this, PROV.invalidated, entity))
    }

    /** Entity [PROV.wasInvalidatedBy] activity. */
    infix fun RdfResource.wasInvalidatedBy(activity: RdfResource) {
        out.add(RdfTriple(this, PROV.wasInvalidatedBy, activity))
    }

    // --- Agents / delegation / attribution ---

    /** Activity [PROV.wasAssociatedWith] agent (or activity). */
    infix fun RdfResource.wasAssociatedWith(agent: RdfResource) {
        out.add(RdfTriple(this, PROV.wasAssociatedWith, agent))
    }

    /** Entity [PROV.wasAttributedTo] agent. */
    infix fun RdfResource.wasAttributedTo(agent: RdfResource) {
        out.add(RdfTriple(this, PROV.wasAttributedTo, agent))
    }

    /** Delegate [PROV.actedOnBehalfOf] responsible agent. */
    infix fun RdfResource.actedOnBehalfOf(responsible: RdfResource) {
        out.add(RdfTriple(this, PROV.actedOnBehalfOf, responsible))
    }

    // --- Derivation ---

    infix fun RdfResource.wasDerivedFrom(prior: RdfResource) {
        out.add(RdfTriple(this, PROV.wasDerivedFrom, prior))
    }

    infix fun RdfResource.wasRevisionOf(prior: RdfResource) {
        out.add(RdfTriple(this, PROV.wasRevisionOf, prior))
    }

    infix fun RdfResource.wasQuotedFrom(prior: RdfResource) {
        out.add(RdfTriple(this, PROV.wasQuotedFrom, prior))
    }

    infix fun RdfResource.hadPrimarySource(source: RdfResource) {
        out.add(RdfTriple(this, PROV.hadPrimarySource, source))
    }

    // --- Structure ---

    infix fun RdfResource.specializationOf(general: RdfResource) {
        out.add(RdfTriple(this, PROV.specializationOf, general))
    }

    infix fun RdfResource.alternateOf(other: RdfResource) {
        out.add(RdfTriple(this, PROV.alternateOf, other))
    }

    /** Collection [PROV.hadMember] entity. */
    infix fun RdfResource.hadMember(member: RdfResource) {
        out.add(RdfTriple(this, PROV.hadMember, member))
    }

    // --- Activity ordering / control ---

    infix fun RdfResource.wasInformedBy(prior: RdfResource) {
        out.add(RdfTriple(this, PROV.wasInformedBy, prior))
    }

    infix fun RdfResource.wasStartedBy(trigger: RdfResource) {
        out.add(RdfTriple(this, PROV.wasStartedBy, trigger))
    }

    infix fun RdfResource.wasEndedBy(trigger: RdfResource) {
        out.add(RdfTriple(this, PROV.wasEndedBy, trigger))
    }

    infix fun RdfResource.atLocation(loc: RdfResource) {
        out.add(RdfTriple(this, PROV.atLocation, loc))
    }

    /** Activity [PROV.hadPlan] plan entity. */
    infix fun RdfResource.hadPlan(plan: RdfResource) {
        out.add(RdfTriple(this, PROV.hadPlan, plan))
    }

    // --- Influence ---

    infix fun RdfResource.influenced(influencee: RdfResource) {
        out.add(RdfTriple(this, PROV.influenced, influencee))
    }

    infix fun RdfResource.wasInfluencedBy(influencer: RdfResource) {
        out.add(RdfTriple(this, PROV.wasInfluencedBy, influencer))
    }

    // --- Time and labels (`xsd:dateTime` for times; use dot form for labels) ---

    fun RdfResource.startedAtTime(isoDateTime: String) {
        out.add(RdfTriple(this, PROV.startedAtTime, lit(isoDateTime, XSD.dateTime)))
    }

    fun RdfResource.endedAtTime(isoDateTime: String) {
        out.add(RdfTriple(this, PROV.endedAtTime, lit(isoDateTime, XSD.dateTime)))
    }

    fun RdfResource.generatedAtTime(isoDateTime: String) {
        out.add(RdfTriple(this, PROV.generatedAtTime, lit(isoDateTime, XSD.dateTime)))
    }

    fun RdfResource.invalidatedAtTime(isoDateTime: String) {
        out.add(RdfTriple(this, PROV.invalidatedAtTime, lit(isoDateTime, XSD.dateTime)))
    }

    /** [PROV.label] as plain or language-tagged literal. */
    fun RdfResource.provLabel(text: String, language: String? = null) {
        out.add(RdfTriple(this, PROV.label, literalFor(text, language)))
    }

    /** [PROV.value] for qualified PROV structures. */
    fun RdfResource.provValue(term: RdfTerm) {
        out.add(RdfTriple(this, PROV.value, term))
    }

    private fun literalFor(text: String, language: String?): RdfTerm =
        if (language != null) lang(text, language) else string(text)
}
