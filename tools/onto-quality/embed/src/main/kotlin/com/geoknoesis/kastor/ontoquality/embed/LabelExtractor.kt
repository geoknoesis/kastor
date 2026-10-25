package com.geoknoesis.kastor.ontoquality.embed

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.SKOS

/**
 * Builds the text that is embedded for each entity.
 *
 * One entity is embedded in **one language**: the ontology's dominant label language (the tag carried by most
 * label literals; ties go to the smallest tag) when the entity has a label in it, else its untagged labels, else
 * its labels in the smallest language tag. Within that language the preferred labels (`rdfs:label`,
 * `skos:prefLabel`) are used, and `skos:altLabel` only when the entity has no preferred label in it. The literals
 * are de-duplicated and sorted, so the text does not depend on the serialisation order of the graph. Without this a
 * multilingual SKOS scheme embedded a polyglot string, and similarity followed shared translations, not meaning.
 *
 * Definitions (`skos:definition`, `rdfs:comment`) for label–definition drift follow the same language rule.
 */
object LabelExtractor {
    /** Label predicates by preference: preferred labels first, alternative labels only as a fallback. */
    private val labelPredicates =
        mapOf(
            RDFS.label to 0,
            SKOS.prefLabel to 0,
            SKOS.altLabel to 1,
        )

    private val definitionPredicates =
        mapOf(
            SKOS.definition to 0,
            RDFS.comment to 0,
        )

    private val excludedPrefixes =
        listOf(
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "http://www.w3.org/2000/01/rdf-schema#",
            "http://www.w3.org/2002/07/owl#",
            "http://www.w3.org/2001/XMLSchema#",
            "http://www.w3.org/ns/shacl#",
            "http://www.w3.org/2004/02/skos/core#",
            "http://purl.org/dc/terms/",
        )

    /** One literal of an entity: its predicate's preference rank, its lower-case language tag ("" if none), its text. */
    private data class Text(val rank: Int, val language: String, val lexical: String)

    /** One text per labelled entity, in one language (see the class documentation). */
    fun extractLabelTexts(
        graph: RdfGraph,
        separator: String = " ; ",
    ): Map<RdfResource, String> {
        val labels = collectTexts(graph, labelPredicates)
        val language = dominantLanguage(labels.values)
        return labels.mapValues { (_, texts) -> select(texts, language, separator) }.filterValues { it.isNotBlank() }
    }

    /**
     * Label + definition strings for `owl:Class` resources only (for label–definition drift). The label is the
     * one [extractLabelTexts] builds; the definition is taken in the label's language when the class has one there.
     */
    fun extractLabelAndDefinitionTexts(
        graph: RdfGraph,
        separator: String = " ; ",
    ): List<Pair<Iri, Pair<String, String>>> {
        val classes = owlClassIris(graph)
        if (classes.isEmpty()) return emptyList()
        val allLabels = collectTexts(graph, labelPredicates)
        val language = dominantLanguage(allLabels.values)
        val labels = allLabels.filterKeys { it in classes }
        val definitions = collectTexts(graph, definitionPredicates).filterKeys { it in classes }

        val out = ArrayList<Pair<Iri, Pair<String, String>>>()
        for (iri in classes.sortedBy { it.value }) {
            val labelTexts = labels[iri] ?: continue
            val definitionTexts = definitions[iri] ?: continue
            val labelLanguage = chooseLanguage(labelTexts, language)
            val label = select(labelTexts, language, separator)
            val definition = select(definitionTexts, labelLanguage, separator)
            if (label.isBlank() || definition.isBlank()) continue
            out.add(iri to (label to definition))
        }
        return out
    }

    private fun owlClassIris(graph: RdfGraph): Set<Iri> {
        val out = HashSet<Iri>()
        for (t in graph.getTriplesSequence()) {
            if (t.predicate != RDF.type) continue
            if (t.obj != OWL.Class) continue
            val subj = t.subject
            if (subj is Iri && includeResource(subj)) out.add(subj)
        }
        return out
    }

    private fun collectTexts(graph: RdfGraph, predicates: Map<Iri, Int>): Map<Iri, List<Text>> {
        val acc = HashMap<Iri, HashSet<Text>>()
        for (t in graph.getTriplesSequence()) {
            val rank = predicates[t.predicate] ?: continue
            val s = t.subject as? Iri ?: continue
            if (!includeResource(s)) continue
            val literal = t.obj as? Literal ?: continue
            val language = (literal as? LangString)?.normalizedLang.orEmpty()
            acc.getOrPut(s) { HashSet() }.add(Text(rank, language, literal.lexical))
        }
        return acc.mapValues { it.value.toList() }
    }

    /** The language tag most label literals carry (ties: the smallest tag); "" when no label is language-tagged. */
    private fun dominantLanguage(texts: Collection<List<Text>>): String =
        texts.asSequence().flatten().filter { it.language.isNotEmpty() }
            .groupingBy { it.language }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key.orEmpty()

    private fun chooseLanguage(texts: List<Text>, preferred: String): String =
        when {
            texts.any { it.language == preferred } -> preferred
            texts.any { it.language.isEmpty() } -> ""
            else -> texts.minOf { it.language }
        }

    /** The best-ranked literals of [texts] in the chosen language, de-duplicated, sorted and joined. */
    private fun select(texts: List<Text>, preferred: String, separator: String): String {
        val language = chooseLanguage(texts, preferred)
        val inLanguage = texts.filter { it.language == language }
        val best = inLanguage.minOf { it.rank }
        return inLanguage.filter { it.rank == best }.map { it.lexical }.distinct().sorted().joinToString(separator)
    }

    private fun includeResource(res: RdfResource): Boolean {
        if (res !is Iri) return false
        val s = res.value
        return excludedPrefixes.none { s.startsWith(it) }
    }
}
