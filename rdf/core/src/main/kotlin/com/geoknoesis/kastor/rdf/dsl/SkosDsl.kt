package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.lang
import com.geoknoesis.kastor.rdf.string
import com.geoknoesis.kastor.rdf.vocab.SKOS

/**
 * Human-readable SKOS triple helpers inside [TripleDsl] or [GraphDsl] blocks.
 *
 * Example:
 * ```kotlin
 * repo.add {
 *   skos {
 *     city.broader(country)
 *     country.narrower(city)
 *     city.prefLabel("City", "en")
 *     city.inScheme(scheme)
 *   }
 * }
 * ```
 */
fun TripleDsl.skos(block: SkosTripleBuilder.() -> Unit) {
    SkosTripleBuilder(triples).apply(block)
}

fun GraphDsl.skos(block: SkosTripleBuilder.() -> Unit) {
    SkosTripleBuilder(triples).apply(block)
}

/**
 * Collects SKOS assertions using vocabulary-aligned names ([SKOS] IRIs).
 */
class SkosTripleBuilder(private val out: MutableList<RdfTriple>) {

    // --- Semantic relations (concept ↔ concept) ---

    /** Narrower concept [SKOS.broader] wider concept. */
    infix fun RdfResource.broader(wider: RdfResource) {
        out.add(RdfTriple(this, SKOS.broader, wider))
    }

    /** Wider concept [SKOS.narrower] narrower concept. */
    infix fun RdfResource.narrower(narrower: RdfResource) {
        out.add(RdfTriple(this, SKOS.narrower, narrower))
    }

    /** [SKOS.related] associative concept link. */
    infix fun RdfResource.related(other: RdfResource) {
        out.add(RdfTriple(this, SKOS.related, other))
    }

    infix fun RdfResource.broaderTransitive(wider: RdfResource) {
        out.add(RdfTriple(this, SKOS.broaderTransitive, wider))
    }

    infix fun RdfResource.narrowerTransitive(narrower: RdfResource) {
        out.add(RdfTriple(this, SKOS.narrowerTransitive, narrower))
    }

    // --- Mapping properties ---

    infix fun RdfResource.broaderMatch(wider: RdfResource) {
        out.add(RdfTriple(this, SKOS.broaderMatch, wider))
    }

    infix fun RdfResource.narrowerMatch(narrower: RdfResource) {
        out.add(RdfTriple(this, SKOS.narrowerMatch, narrower))
    }

    infix fun RdfResource.relatedMatch(other: RdfResource) {
        out.add(RdfTriple(this, SKOS.relatedMatch, other))
    }

    infix fun RdfResource.exactMatch(other: RdfResource) {
        out.add(RdfTriple(this, SKOS.exactMatch, other))
    }

    infix fun RdfResource.closeMatch(other: RdfResource) {
        out.add(RdfTriple(this, SKOS.closeMatch, other))
    }

    // --- Schemes & collections ---

    /** Concept [SKOS.inScheme] scheme. */
    infix fun RdfResource.inScheme(scheme: RdfResource) {
        out.add(RdfTriple(this, SKOS.inScheme, scheme))
    }

    /** [SKOS.hasTopConcept] from scheme to top concept. */
    infix fun RdfResource.hasTopConcept(concept: RdfResource) {
        out.add(RdfTriple(this, SKOS.hasTopConcept, concept))
    }

    /** Concept [SKOS.topConceptOf] scheme. */
    infix fun RdfResource.topConceptOf(scheme: RdfResource) {
        out.add(RdfTriple(this, SKOS.topConceptOf, scheme))
    }

    /** Ordered/unordered [SKOS.member] from collection to member resource. */
    infix fun RdfResource.member(element: RdfResource) {
        out.add(RdfTriple(this, SKOS.member, element))
    }

    // --- Lexical / documentation (literals) ---

    fun RdfResource.prefLabel(text: String, lang: String? = null) {
        out.add(RdfTriple(this, SKOS.prefLabel, literalFor(text, lang)))
    }

    fun RdfResource.altLabel(text: String, lang: String? = null) {
        out.add(RdfTriple(this, SKOS.altLabel, literalFor(text, lang)))
    }

    fun RdfResource.hiddenLabel(text: String, lang: String? = null) {
        out.add(RdfTriple(this, SKOS.hiddenLabel, literalFor(text, lang)))
    }

    fun RdfResource.notation(text: String, lang: String? = null) {
        out.add(RdfTriple(this, SKOS.notation, literalFor(text, lang)))
    }

    fun RdfResource.definition(text: String, lang: String? = null) {
        out.add(RdfTriple(this, SKOS.definition, literalFor(text, lang)))
    }

    fun RdfResource.scopeNote(text: String, lang: String? = null) {
        out.add(RdfTriple(this, SKOS.scopeNote, literalFor(text, lang)))
    }

    fun RdfResource.example(text: String, lang: String? = null) {
        out.add(RdfTriple(this, SKOS.example, literalFor(text, lang)))
    }

    fun RdfResource.note(text: String, lang: String? = null) {
        out.add(RdfTriple(this, SKOS.note, literalFor(text, lang)))
    }

    fun RdfResource.changeNote(text: String, lang: String? = null) {
        out.add(RdfTriple(this, SKOS.changeNote, literalFor(text, lang)))
    }

    fun RdfResource.editorialNote(text: String, lang: String? = null) {
        out.add(RdfTriple(this, SKOS.editorialNote, literalFor(text, lang)))
    }

    fun RdfResource.historyNote(text: String, lang: String? = null) {
        out.add(RdfTriple(this, SKOS.historyNote, literalFor(text, lang)))
    }

    /** Plain literal or language-tagged literal when [lang] is non-null. */
    private fun literalFor(text: String, lang: String?): RdfTerm =
        if (lang != null) lang(text, lang) else string(text)
}
