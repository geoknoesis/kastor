package com.geoknoesis.kastor.ontoquality.embed

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.SKOS

object LabelExtractor {
    private val labelPredicates =
        listOf(
            RDFS.label,
            SKOS.prefLabel,
            SKOS.altLabel,
        )

    private val definitionPredicates =
        listOf(
            SKOS.definition,
            RDFS.comment,
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

    /** Default: label-bearing predicates only (see task spec). */
    fun extractLabelTexts(
        graph: RdfGraph,
        separator: String = " ; ",
    ): Map<RdfResource, String> {
        val values =
            collectLiteralObjects(graph, labelPredicates.toSet())
                .filterKeys { includeResource(it) }
                .mapValues { (_, literals) -> literals.joinToString(separator) }
        return values.filter { it.value.isNotBlank() }
    }

    /**
     * Label + definition strings for `owl:Class` resources only (for label–definition drift).
     */
    fun extractLabelAndDefinitionTexts(
        graph: RdfGraph,
        separator: String = " ; ",
    ): List<Pair<Iri, Pair<String, String>>> {
        val classes = owlClassIris(graph)
        if (classes.isEmpty()) return emptyList()

        val labelValues =
            collectLiteralObjects(graph, labelPredicates.toSet())
                .filterKeys { it is Iri && classes.contains(it) }
                .mapValues { (_, literals) -> literals.joinToString(separator) }

        val defValues =
            collectLiteralObjects(graph, definitionPredicates.toSet())
                .filterKeys { it is Iri && classes.contains(it) }
                .mapValues { (_, literals) -> literals.joinToString(separator) }

        val out = ArrayList<Pair<Iri, Pair<String, String>>>()
        for (iri in classes) {
            val label = labelValues[iri] ?: continue
            val def = defValues[iri] ?: continue
            if (label.isBlank() || def.isBlank()) continue
            out.add(iri to (label to def))
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

    private fun collectLiteralObjects(
        graph: RdfGraph,
        predicates: Set<Iri>,
    ): Map<RdfResource, List<String>> {
        val acc = mutableMapOf<RdfResource, LinkedHashSet<String>>()
        for (t in graph.getTriplesSequence()) {
            if (t.predicate !in predicates) continue
            val s = t.subject
            if (s is BlankNode) continue
            if (!includeResource(s)) continue
            val lex = literalLexical(t.obj) ?: continue
            acc.getOrPut(s) { LinkedHashSet() }.add(lex)
        }
        return acc.mapValues { it.value.toList() }
    }

    private fun literalLexical(term: RdfTerm): String? =
        when (term) {
            is Literal -> term.lexical
            else -> null
        }

    private fun includeResource(res: RdfResource): Boolean {
        if (res is BlankNode) return false
        if (res !is Iri) return false
        val s = res.value
        return excludedPrefixes.none { s.startsWith(it) }
    }
}
