package com.geoknoesis.kastor.ontoquality.catalog

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.jena.JenaBridge

/**
 * Which graph a detector evaluates when a reasoner is on: every targeted shape of a catalogue carries
 * `oqsh:evaluatedOn`.
 *
 * - `oqsh:AssertedGraph` — the detector looks at what the author **wrote**: hierarchy shape and cycles, missing or
 *   malformed declarations and annotations, naming, redundancy, serialisation hygiene. A reasoner's closure would
 *   make such a detector fire on every class (reflexive `C rdfs:subClassOf C`, axiomatic triples on the built-in
 *   vocabulary) or go silent (entailed typing hides a missing declaration), so it always runs on the asserted graph.
 * - `oqsh:EntailedGraph` — the detector looks at what the ontology **means**: class membership, disjointness and
 *   value-type conditions that are supposed to see inferred types and relations. It runs on the materialised graph.
 *
 * A shape without the annotation (a custom catalogue) evaluates the entailed graph, as every shape did before the
 * annotation existed. Without a reasoner the two graphs are the same and the annotation has no effect.
 *
 * The annotation is read on **targeted** shapes only (explicit targets, or an implicit class target). A shape that
 * is only referenced (`sh:node`, `sh:property` …) is evaluated wherever the shape referencing it is.
 */
internal class EvaluationScopes private constructor(
    /** Targeted shapes marked `oqsh:AssertedGraph`. */
    val asserted: Set<RdfResource>,
    /** Targeted shapes that evaluate the entailed graph (marked so, or not marked). */
    val entailed: Set<RdfResource>,
    /** Active targeted shapes without the annotation, for the bundled-catalogue test. */
    val unclassified: List<String>,
) {
    /** [shapes] with every shape of the other scope deactivated; null when no shape of [scope] is left. */
    fun shapesFor(shapes: RdfGraph, scope: Scope): RdfGraph? {
        val (kept, dropped) = if (scope == Scope.ASSERTED) asserted to entailed else entailed to asserted
        if (kept.isEmpty()) return null
        if (dropped.isEmpty()) return shapes
        val out = JenaBridge.createEmptyModel()
        out.addTriples(shapes.getTriples())
        out.addTriples(dropped.map { RdfTriple(it, DEACTIVATED, Literal(true)) })
        return out
    }

    enum class Scope { ASSERTED, ENTAILED }

    companion object {
        private const val SH = "http://www.w3.org/ns/shacl#"
        private const val OQSH = "http://example.org/owl-quality-shacl#"
        private val TYPE = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
        private val DEACTIVATED = Iri("${SH}deactivated")
        val EVALUATED_ON = Iri("${OQSH}evaluatedOn")
        val ASSERTED_GRAPH = Iri("${OQSH}AssertedGraph")
        val ENTAILED_GRAPH = Iri("${OQSH}EntailedGraph")
        private val TARGETS =
            setOf("targetClass", "targetNode", "targetSubjectsOf", "targetObjectsOf", "targetWhere", "target").map { Iri(SH + it) }.toSet()
        private val SHAPE_TYPES = setOf(Iri("${SH}NodeShape"), Iri("${SH}PropertyShape"))
        private val CLASS_TYPES =
            setOf(Iri("http://www.w3.org/2000/01/rdf-schema#Class"), Iri("http://www.w3.org/2002/07/owl#Class"))

        fun of(shapes: RdfGraph): EvaluationScopes {
            val targeted = LinkedHashSet<RdfResource>()
            val shapeTyped = HashSet<RdfResource>()
            val classTyped = HashSet<RdfResource>()
            val scope = HashMap<RdfResource, Iri>()
            val deactivated = HashSet<RdfResource>()
            for (t in shapes.getTriples()) {
                when {
                    t.predicate in TARGETS -> targeted.add(t.subject)
                    t.predicate == TYPE && t.obj in SHAPE_TYPES -> shapeTyped.add(t.subject)
                    t.predicate == TYPE && t.obj in CLASS_TYPES -> classTyped.add(t.subject)
                    t.predicate == EVALUATED_ON -> (t.obj as? Iri)?.let { scope[t.subject] = it }
                    t.predicate == DEACTIVATED && (t.obj as? Literal)?.lexical.let { it == "true" || it == "1" } -> deactivated.add(t.subject)
                }
            }
            // Implicit class targets: a shape that is also a class.
            targeted.addAll(shapeTyped.filter { it in classTyped })
            val asserted = LinkedHashSet<RdfResource>()
            val entailed = LinkedHashSet<RdfResource>()
            val unclassified = ArrayList<String>()
            for (shape in targeted) {
                if (shape in deactivated) continue
                when (scope[shape]) {
                    ASSERTED_GRAPH -> asserted.add(shape)
                    ENTAILED_GRAPH -> entailed.add(shape)
                    else -> {
                        entailed.add(shape)
                        unclassified.add(if (shape is BlankNode) "(anonymous shape)" else shape.toString())
                    }
                }
            }
            return EvaluationScopes(asserted, entailed, unclassified)
        }
    }
}
