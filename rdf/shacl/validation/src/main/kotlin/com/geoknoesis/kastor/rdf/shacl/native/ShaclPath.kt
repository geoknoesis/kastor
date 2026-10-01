package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException

internal class ShapeGraphIndex(triples: List<RdfTriple>, private val budget: ValidationBudget = ValidationBudget.NONE) {
    /** All shapes-graph triples (retained for SHACL-SPARQL `$shapesGraph` access). */
    val triples: List<RdfTriple> = triples
    private val bySubject = HashMap<RdfResource, LinkedHashMap<Iri, MutableList<RdfTerm>>>()
    private val byPredicateObject = HashMap<Iri, HashMap<RdfTerm, MutableList<RdfResource>>>()
    private val reifiers = HashMap<RdfTriple, MutableList<RdfResource>>()

    init {
        for (t in triples) {
            budget.tick("shape indexing")
            bySubject.getOrPut(t.subject) { LinkedHashMap() }.getOrPut(t.predicate) { ArrayList(1) }.add(t.obj)
            byPredicateObject.getOrPut(t.predicate) { HashMap() }.getOrPut(t.obj) { ArrayList(1) }.add(t.subject)
            if (t.predicate == RDF.reifies) {
                (t.obj as? TripleTerm)?.let { reifiers.getOrPut(it.triple) { ArrayList(1) }.add(t.subject) }
            }
        }
        budget.check("shape indexing")
    }

    fun objects(sub: RdfResource, pred: Iri): List<RdfTerm> = bySubject[sub]?.get(pred) ?: emptyList()

    fun hasSubject(sub: RdfResource): Boolean = bySubject.containsKey(sub)

    /** Predicates of the triples whose subject is [sub]. */
    fun predicates(sub: RdfResource): Set<Iri> = bySubject[sub]?.keys ?: emptySet()

    fun subjects(pred: Iri, obj: RdfTerm): List<RdfResource> = byPredicateObject[pred]?.get(obj) ?: emptyList()

    fun objectSingle(sub: RdfResource, pred: Iri): RdfTerm? = objects(sub, pred).singleOrNull()

    /** Reifiers naming `claim` via `rdf:reifies` (Turtle 1.2 `- {| … |}` annotations); indexed, O(1). */
    fun reifiersForClaim(claim: RdfTriple): List<RdfResource> = reifiers[claim] ?: emptyList()

    /** SHACL instance test in the shapes graph: `rdf:type/rdfs:subClassOf*` reaches [cls]. */
    fun isInstanceOf(node: RdfResource, cls: Iri): Boolean {
        val seen = HashSet<RdfTerm>()
        val queue = ArrayDeque<RdfTerm>(objects(node, RDF.type))
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (c == cls) return true
            if (c is RdfResource && seen.add(c)) queue.addAll(objects(c, com.geoknoesis.kastor.rdf.vocab.RDFS.subClassOf))
        }
        return false
    }

    /** Triples describing the blank-node structure reachable from [root] (e.g. a complex `sh:path`). */
    fun blankNodeClosure(root: RdfTerm): List<RdfTriple> {
        val out = mutableListOf<RdfTriple>()
        val seen = HashSet<BlankNode>()
        fun visit(term: RdfTerm) {
            if (term !is BlankNode || !seen.add(term)) return
            bySubject[term]?.forEach { (p, objs) ->
                objs.forEach { o ->
                    out.add(RdfTriple(term, p, o))
                    visit(o)
                }
            }
        }
        visit(root)
        return out
    }

    /**
     * Members of the RDF list starting at [head]. The list must be well-formed: every cell is a blank node with
     * exactly one `rdf:first` and exactly one `rdf:rest`, ending in `rdf:nil`. A missing or repeated `rdf:rest` /
     * `rdf:first` raises [ShapeCompileException]: it would otherwise silently truncate (or fork) `sh:in`, `sh:or`,
     * path lists and the like.
     */
    fun parseRdfList(head: RdfTerm): List<RdfTerm> {
        val out = mutableListOf<RdfTerm>()
        var cur: RdfTerm? = head
        val visited = mutableSetOf<RdfTerm>()
        while (cur != null) {
            budget.check("shape collection")
            if (!visited.add(cur)) throw ShapeCompileException("Cyclic RDF collection in shapes")
            when (cur) {
                is Iri -> {
                    if (cur == RDF.nil) return out
                    throw ShapeCompileException("Invalid RDF collection head (expected blank node list cell): $cur")
                }
                is BlankNode -> {
                    val firsts = objects(cur, RDF.first)
                    if (firsts.size != 1) {
                        throw ShapeCompileException("Ill-formed RDF list: cell $cur has ${firsts.size} rdf:first values (exactly one required)")
                    }
                    val rests = objects(cur, RDF.rest)
                    if (rests.size != 1) {
                        throw ShapeCompileException("Ill-formed RDF list: cell $cur has ${rests.size} rdf:rest values (exactly one required)")
                    }
                    val rest = rests[0]
                    out.add(firsts[0])
                    if (rest is Iri && rest == RDF.nil) break
                    cur = rest
                }
                else -> throw ShapeCompileException("Invalid RDF list cell term: $cur")
            }
        }
        return out
    }
}

/** Normalized SHACL property path for the native path engine. */
internal sealed class ShaclPath {
    data class Predicate(val iri: Iri) : ShaclPath()
    data class Inverse(val child: ShaclPath) : ShaclPath()
    data class Sequence(val segments: List<ShaclPath>) : ShaclPath()
    data class Alternative(val options: List<ShaclPath>) : ShaclPath()
    data class ZeroOrMore(val child: ShaclPath) : ShaclPath()
    data class OneOrMore(val child: ShaclPath) : ShaclPath()
    data class ZeroOrOne(val child: ShaclPath) : ShaclPath()
}

internal object ShaclPathParser {
    fun parse(term: RdfTerm, shapes: ShapeGraphIndex): ShaclPath = parse(term, shapes, emptySet())

    private fun parse(term: RdfTerm, shapes: ShapeGraphIndex, ancestors: Set<RdfTerm>): ShaclPath {
        if (term in ancestors || ancestors.size >= 128) throw ShapeCompileException("Cyclic or excessively nested SHACL path")
        val next = ancestors + term
        return when (term) {
            is Iri -> ShaclPath.Predicate(term)
            is BlankNode ->
                if (shapes.objectSingle(term, RDF.first) != null) {
                    ShaclPath.Sequence(shapes.parseRdfList(term).map { parse(it, shapes, next) })
                } else {
                    parseBlankPath(term, shapes, next)
                }
            else -> throw ShapeCompileException("Unsupported SHACL path term: $term")
        }

    }

    private fun parseBlankPath(node: BlankNode, shapes: ShapeGraphIndex, next: Set<RdfTerm>): ShaclPath {
        val triples = shapes.objects(node, SHACL.alternativePath).map { ShaclPath.Alternative(parseList(it, shapes, next)) }
            .plus(shapes.objects(node, SHACL.sequencePath).map { ShaclPath.Sequence(parseList(it, shapes, next)) })
            .plus(shapes.objects(node, SHACL.inversePath).map { ShaclPath.Inverse(parse(it, shapes, next)) })
            .plus(shapes.objects(node, SHACL.zeroOrMorePath).map { ShaclPath.ZeroOrMore(parse(it, shapes, next)) })
            .plus(shapes.objects(node, SHACL.oneOrMorePath).map { ShaclPath.OneOrMore(parse(it, shapes, next)) })
            .plus(shapes.objects(node, SHACL.zeroOrOnePath).map { ShaclPath.ZeroOrOne(parse(it, shapes, next)) })

        if (triples.size != 1) {
            throw ShapeCompileException(
                "SHACL path blank node must describe exactly one path constructor; got ${triples.size} for $node",
            )
        }
        return triples.first()
    }

    private fun parseList(head: RdfTerm, shapes: ShapeGraphIndex, next: Set<RdfTerm>): List<ShaclPath> =
        shapes.parseRdfList(head).map { parse(it, shapes, next) }
}
