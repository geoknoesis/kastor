package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS

/**
 * Lightweight validation-session index over a data graph (architecture §9.3).
 */
internal class DataGraphIndex(graph: RdfGraph) {

    private val triples: List<RdfTriple> = graph.getTriples()

    private val bySubject: Map<RdfResource, List<RdfTriple>> = triples.groupBy { it.subject }

    fun distinctResourceSubjects(): Set<RdfResource> = bySubject.keys.toSet()

    private val byPredicateObject: Map<Pair<Iri, RdfTerm>, MutableSet<RdfResource>> = run {
        val m = mutableMapOf<Pair<Iri, RdfTerm>, MutableSet<RdfResource>>()
        for (t in triples) {
            m.getOrPut(t.predicate to t.obj) { mutableSetOf() }.add(t.subject)
        }
        m
    }

    private val typesByInstance: Map<RdfResource, Set<Iri>> = run {
        val m = mutableMapOf<RdfResource, MutableSet<Iri>>()
        for (t in triples) {
            if (t.predicate == RDF.type && t.obj is Iri) {
                m.getOrPut(t.subject) { mutableSetOf() }.add(t.obj as Iri)
            }
        }
        m.mapValues { it.value.toSet() }
    }

    /** Direct `rdfs:subClassOf` edges: subclass → superclasses. */
    private val directSuperClasses: Map<Iri, Set<Iri>> = run {
        val m = mutableMapOf<Iri, MutableSet<Iri>>()
        for (t in triples) {
            if (t.predicate == RDFS.subClassOf && t.subject is Iri && t.obj is Iri) {
                m.getOrPut(t.subject as Iri) { mutableSetOf() }.add(t.obj as Iri)
            }
        }
        m.mapValues { it.value.toSet() }
    }

    fun objects(subject: RdfResource, predicate: Iri): List<RdfTerm> =
        bySubject[subject]?.filter { it.predicate == predicate }?.map { it.obj } ?: emptyList()

    fun predicatesFor(subject: RdfResource): Set<Iri> =
        bySubject[subject]?.map { it.predicate }?.toSet() ?: emptySet()

    fun subjectsWith(predicate: Iri, obj: RdfTerm): Set<RdfResource> =
        byPredicateObject[predicate to obj] ?: emptySet()

    /** Reifiers naming [claim] via `rdf:reifies` (RDF 1.2 triple terms). */
    fun reifiersForClaim(claim: RdfTriple): List<RdfResource> =
        triples.mapNotNull { t ->
            if (t.predicate != RDF.reifies) return@mapNotNull null
            val tt = t.obj as? TripleTerm ?: return@mapNotNull null
            if (tt.triple == claim) t.subject else null
        }

    fun typesOf(resource: RdfResource): Set<Iri> = typesByInstance[resource] ?: emptySet()

    fun allInstancesOf(clazz: Iri): Sequence<RdfResource> =
        triples.asSequence()
            .filter { it.predicate == RDF.type && it.obj == clazz }
            .map { it.subject }

    /**
     * Types reachable from [clazz] by walking `rdfs:subClassOf` outward (includes [clazz]).
     * Used so `sh:targetClass` matches instances of subclasses.
     */
    fun superclassCone(clazz: Iri): Set<Iri> {
        val seen = mutableSetOf<Iri>()
        val dq = ArrayDeque<Iri>()
        dq.add(clazz)
        while (dq.isNotEmpty()) {
            val c = dq.removeFirst()
            if (!seen.add(c)) continue
            directSuperClasses[c]?.forEach { dq.add(it) }
        }
        return seen
    }

    /** Instances whose asserted `rdf:type` is [targetClass] or a subclass of it. */
    fun instancesMatchingTargetClass(targetClass: Iri): Sequence<RdfResource> =
        triples.asSequence()
            .filter { it.predicate == RDF.type && it.obj is Iri }
            .filter { (_, _, obj) -> targetClass in superclassCone(obj as Iri) }
            .map { it.subject }
            .filterIsInstance<RdfResource>()
            .distinct()

    fun subjectsWithPredicate(predicate: Iri): Sequence<RdfResource> =
        triples.asSequence().filter { it.predicate == predicate }.map { it.subject }.distinct()

    fun objectsWithPredicate(predicate: Iri): Sequence<RdfTerm> =
        triples.asSequence().filter { it.predicate == predicate }.map { it.obj }.distinct()

    /**
     * Expands a well-formed RDF list head in the **data** graph. Returns `null` if [head] is not a valid list cell chain.
     * [RDF.nil] denotes the empty list.
     */
    fun expandDataList(head: RdfTerm): List<RdfTerm>? {
        if (head == RDF.nil) return emptyList()
        if (head !is RdfResource) return null
        val out = mutableListOf<RdfTerm>()
        var cur: RdfTerm? = head
        val visited = mutableSetOf<RdfResource>()
        while (cur != null && cur != RDF.nil) {
            val cell = cur as? RdfResource ?: return null
            if (!visited.add(cell)) return null
            val firsts = objects(cell, RDF.first)
            if (firsts.size != 1) return null
            out.add(firsts[0])
            val rests = objects(cell, RDF.rest)
            if (rests.size != 1) return null
            cur = rests[0]
        }
        return out
    }
}
