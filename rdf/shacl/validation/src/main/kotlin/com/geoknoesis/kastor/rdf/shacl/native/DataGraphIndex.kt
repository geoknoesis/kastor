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
internal class DataGraphIndex(graph: RdfGraph, private val budget: ValidationBudget = ValidationBudget.NONE) {

    private val triples: List<RdfTriple> = budget.snapshot(graph, "data snapshot")

    private val bySubject: Map<RdfResource, List<RdfTriple>> = run {
        val result = mutableMapOf<RdfResource, MutableList<RdfTriple>>()
        triples.forEach { triple ->
            budget.check("data indexing")
            result.getOrPut(triple.subject) { ArrayList(1) }.add(triple)
        }
        result
    }

    fun distinctResourceSubjects(): Set<RdfResource> = bySubject.keys

    private val byPredicateObject: Map<Pair<Iri, RdfTerm>, MutableSet<RdfResource>> = run {
        val m = mutableMapOf<Pair<Iri, RdfTerm>, MutableSet<RdfResource>>()
        for (t in triples) {
            budget.check("data indexing")
            m.getOrPut(t.predicate to t.obj) { mutableSetOf() }.add(t.subject)
        }
        m
    }

    private val typesByInstance: Map<RdfResource, Set<Iri>> by lazy(LazyThreadSafetyMode.NONE) {
        val m = mutableMapOf<RdfResource, MutableSet<Iri>>()
        for (t in triples) {
            budget.check("data indexing")
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
            budget.check("data indexing")
            if (t.predicate == RDFS.subClassOf && t.subject is Iri && t.obj is Iri) {
                m.getOrPut(t.subject as Iri) { mutableSetOf() }.add(t.obj as Iri)
            }
        }
        m.mapValues { it.value.toSet() }
    }

    private val directSubClasses: Map<Iri, Set<Iri>> = run {
        val result = mutableMapOf<Iri, MutableSet<Iri>>()
        directSuperClasses.forEach { (child, parents) -> parents.forEach { parent ->
            budget.check("class indexing")
            result.getOrPut(parent) { mutableSetOf() }.add(child)
        } }
        result
    }

    // Session-local and bounded: large class hierarchies cannot retain every transitive closure.
    private val superclassCache = object : LinkedHashMap<Iri, Set<Iri>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Iri, Set<Iri>>): Boolean = size > 128
    }

    fun objects(subject: RdfResource, predicate: Iri): List<RdfTerm> =
        bySubject[subject]?.filter { budget.check(); it.predicate == predicate }?.map { it.obj } ?: emptyList()

    fun predicatesFor(subject: RdfResource): Set<Iri> =
        bySubject[subject]?.map { it.predicate }?.toSet() ?: emptySet()

    fun subjectsWith(predicate: Iri, obj: RdfTerm): Set<RdfResource> =
        byPredicateObject[predicate to obj] ?: emptySet()

    /** Reifiers naming [claim] via `rdf:reifies` (RDF 1.2 triple terms). */
    fun reifiersForClaim(claim: RdfTriple): List<RdfResource> =
        subjectsWith(RDF.reifies, TripleTerm(claim)).toList()

    fun typesOf(resource: RdfResource): Set<Iri> = typesByInstance[resource] ?: emptySet()

    fun allInstancesOf(clazz: Iri): Sequence<RdfResource> =
        subjectsWith(RDF.type, clazz).asSequence().onEach { budget.check() }

    /**
     * Types reachable from [clazz] by walking `rdfs:subClassOf` outward (includes [clazz]).
     * Used so `sh:targetClass` matches instances of subclasses.
     */
    fun superclassCone(clazz: Iri): Set<Iri> {
        budget.check("class traversal")
        superclassCache[clazz]?.let { return it }
        val seen = mutableSetOf<Iri>()
        val dq = ArrayDeque<Iri>()
        dq.add(clazz)
        while (dq.isNotEmpty()) {
            budget.check("class traversal")
            val c = dq.removeFirst()
            if (!seen.add(c)) continue
            directSuperClasses[c]?.forEach { dq.add(it) }
        }
        superclassCache[clazz] = seen
        return seen
    }

    /** Instances whose asserted `rdf:type` is [targetClass] or a subclass of it. */
    fun instancesMatchingTargetClass(targetClass: Iri): Sequence<RdfResource> =
        sequence {
            val pending = ArrayDeque<Iri>()
            val visited = mutableSetOf<Iri>()
            pending.add(targetClass)
            while (pending.isNotEmpty()) {
                budget.check("target class traversal")
                val clazz = pending.removeFirst()
                if (!visited.add(clazz)) continue
                yieldAll(allInstancesOf(clazz))
                directSubClasses[clazz]?.forEach { pending.add(it) }
            }
        }.distinct()

    fun subjectsWithPredicate(predicate: Iri): Sequence<RdfResource> =
        triples.asSequence().onEach { budget.check() }.filter { it.predicate == predicate }.map { it.subject }.distinct()

    fun objectsWithPredicate(predicate: Iri): Sequence<RdfTerm> =
        triples.asSequence().onEach { budget.check() }.filter { it.predicate == predicate }.map { it.obj }.distinct()

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
            budget.check("data collection")
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
