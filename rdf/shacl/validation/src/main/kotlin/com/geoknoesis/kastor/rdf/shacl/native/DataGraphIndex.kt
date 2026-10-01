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
 *
 * Built in a single pass: subject → predicate → objects (O(1) `objects(s, p)`), predicate → triples
 * (target subjects/objects of), (predicate, object) → subjects (inverse paths, class instances), plus
 * `rdf:type` and `rdfs:subClassOf` edges. Deadline checks are amortized via [ValidationBudget.tick].
 */
internal class DataGraphIndex(
    graph: RdfGraph,
    val budget: ValidationBudget = ValidationBudget.NONE,
    /** Cap on node sets produced while evaluating one property path ([com.geoknoesis.kastor.rdf.shacl.ValidationConfig.maxPathValueNodes]). */
    val maxPathValueNodes: Int = Int.MAX_VALUE,
) {

    /** Source graph (used for SHACL-SPARQL sessions, which query the data graph only). */
    val graph: RdfGraph = graph

    private val bySubject = LinkedHashMap<RdfResource, LinkedHashMap<Iri, MutableList<RdfTerm>>>()
    private val byPredicate = HashMap<Iri, MutableList<RdfTriple>>()
    private val byPredicateObject = HashMap<Pair<Iri, RdfTerm>, LinkedHashSet<RdfResource>>()
    private val typesByInstance = HashMap<RdfResource, LinkedHashSet<Iri>>()
    /** Direct `rdfs:subClassOf` edges: subclass → superclasses. */
    private val directSuperClasses = HashMap<Iri, LinkedHashSet<Iri>>()
    private val directSubClasses = HashMap<Iri, LinkedHashSet<Iri>>()

    init {
        for (t in budget.snapshot(graph, "data snapshot")) {
            budget.tick("data indexing")
            bySubject.getOrPut(t.subject) { LinkedHashMap() }.getOrPut(t.predicate) { ArrayList(1) }.add(t.obj)
            byPredicate.getOrPut(t.predicate) { ArrayList() }.add(t)
            byPredicateObject.getOrPut(t.predicate to t.obj) { LinkedHashSet() }.add(t.subject)
            if (t.predicate == RDF.type && t.obj is Iri) {
                typesByInstance.getOrPut(t.subject) { LinkedHashSet() }.add(t.obj as Iri)
            }
            if (t.predicate == RDFS.subClassOf && t.subject is Iri && t.obj is Iri) {
                directSuperClasses.getOrPut(t.subject as Iri) { LinkedHashSet() }.add(t.obj as Iri)
                directSubClasses.getOrPut(t.obj as Iri) { LinkedHashSet() }.add(t.subject as Iri)
            }
        }
        budget.check("data indexing")
    }

    fun distinctResourceSubjects(): Set<RdfResource> = bySubject.keys

    private val allNodesView: Set<RdfTerm> by lazy {
        val nodes = LinkedHashSet<RdfTerm>()
        for ((subject, byPredicateObjects) in bySubject) {
            budget.tick("node enumeration")
            nodes.add(subject)
            for (objects in byPredicateObjects.values) nodes.addAll(objects)
        }
        nodes
    }

    /** Every node of the data graph: subjects and objects (including literals), in first-occurrence order. */
    fun allNodes(): Set<RdfTerm> = allNodesView

    /** Whether [term] occurs in the data graph as a subject or an object. */
    fun containsNode(term: RdfTerm): Boolean = (term is RdfResource && bySubject.containsKey(term)) || term in allNodesView

    // Session-local and bounded: large class hierarchies cannot retain every transitive closure.
    private val superclassCache = object : LinkedHashMap<Iri, Set<Iri>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Iri, Set<Iri>>): Boolean = size > 128
    }

    fun objects(subject: RdfResource, predicate: Iri): List<RdfTerm> =
        bySubject[subject]?.get(predicate) ?: emptyList()

    fun predicatesFor(subject: RdfResource): Set<Iri> =
        bySubject[subject]?.keys ?: emptySet()

    fun subjectsWith(predicate: Iri, obj: RdfTerm): Set<RdfResource> =
        byPredicateObject[predicate to obj] ?: emptySet()

    /** Reifiers naming [claim] via `rdf:reifies` (RDF 1.2 triple terms). */
    fun reifiersForClaim(claim: RdfTriple): List<RdfResource> =
        subjectsWith(RDF.reifies, TripleTerm(claim)).toList()

    fun typesOf(resource: RdfResource): Set<Iri> = typesByInstance[resource] ?: emptySet()

    fun allInstancesOf(clazz: Iri): Sequence<RdfResource> =
        subjectsWith(RDF.type, clazz).asSequence().onEach { budget.tick() }

    /**
     * Types reachable from [clazz] by walking `rdfs:subClassOf` outward (includes [clazz]).
     * Used so `sh:targetClass` matches instances of subclasses.
     */
    fun superclassCone(clazz: Iri): Set<Iri> {
        budget.tick("class traversal")
        superclassCache[clazz]?.let { return it }
        val seen = LinkedHashSet<Iri>()
        val dq = ArrayDeque<Iri>()
        dq.add(clazz)
        while (dq.isNotEmpty()) {
            budget.tick("class traversal")
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
                budget.tick("target class traversal")
                val clazz = pending.removeFirst()
                if (!visited.add(clazz)) continue
                yieldAll(allInstancesOf(clazz))
                directSubClasses[clazz]?.forEach { pending.add(it) }
            }
        }.distinct()

    fun subjectsWithPredicate(predicate: Iri): Sequence<RdfResource> =
        (byPredicate[predicate] ?: emptyList<RdfTriple>()).asSequence().onEach { budget.tick() }.map { it.subject }.distinct()

    fun objectsWithPredicate(predicate: Iri): Sequence<RdfTerm> =
        (byPredicate[predicate] ?: emptyList<RdfTriple>()).asSequence().onEach { budget.tick() }.map { it.obj }.distinct()

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
            budget.tick("data collection")
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
