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

internal class ShapeGraphIndex(triples: List<RdfTriple>) {
    private val bySubject: Map<RdfResource, List<RdfTriple>> = triples.groupBy { it.subject }
    private val allTriples: List<RdfTriple> = triples

    fun objects(sub: RdfResource, pred: Iri): List<RdfTerm> =
        bySubject[sub]?.filter { it.predicate == pred }?.map { it.obj } ?: emptyList()

    fun objectSingle(sub: RdfResource, pred: Iri): RdfTerm? = objects(sub, pred).singleOrNull()

    /** Reifiers naming `claim` via `rdf:reifies` (Turtle 1.2 `- {| … |}` annotations). */
    fun reifiersForClaim(claim: RdfTriple): List<RdfResource> =
        allTriples.mapNotNull { t ->
            if (t.predicate != RDF.reifies) return@mapNotNull null
            val tt = t.obj as? TripleTerm ?: return@mapNotNull null
            if (tt.triple == claim) t.subject else null
        }

    fun parseRdfList(head: RdfTerm): List<RdfTerm> {
        val out = mutableListOf<RdfTerm>()
        var cur: RdfTerm? = head
        while (cur != null) {
            when (cur) {
                is Iri -> {
                    if (cur == RDF.nil) return out
                    throw ShapeCompileException("Invalid RDF collection head (expected blank node list cell): $cur")
                }
                is BlankNode -> {
                    val first = objectSingle(cur, RDF.first)
                        ?: throw ShapeCompileException("RDF list cell missing rdf:first on $cur")
                    val rest = objectSingle(cur, RDF.rest) ?: RDF.nil
                    out.add(first)
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
    fun parse(term: RdfTerm, shapes: ShapeGraphIndex): ShaclPath =
        when (term) {
            is Iri -> ShaclPath.Predicate(term)
            is BlankNode ->
                if (shapes.objectSingle(term, RDF.first) != null) {
                    ShaclPath.Sequence(shapes.parseRdfList(term).map { parse(it, shapes) })
                } else {
                    parseBlankPath(term, shapes)
                }
            else -> throw ShapeCompileException("Unsupported SHACL path term: $term")
        }

    private fun parseBlankPath(node: BlankNode, shapes: ShapeGraphIndex): ShaclPath {
        val triples = shapes.objects(node, SHACL.alternativePath).map { ShaclPath.Alternative(parseList(it, shapes)) }
            .plus(shapes.objects(node, SHACL.sequencePath).map { ShaclPath.Sequence(parseList(it, shapes)) })
            .plus(shapes.objects(node, SHACL.inversePath).map { ShaclPath.Inverse(parse(it, shapes)) })
            .plus(shapes.objects(node, SHACL.zeroOrMorePath).map { ShaclPath.ZeroOrMore(parse(it, shapes)) })
            .plus(shapes.objects(node, SHACL.oneOrMorePath).map { ShaclPath.OneOrMore(parse(it, shapes)) })
            .plus(shapes.objects(node, SHACL.zeroOrOnePath).map { ShaclPath.ZeroOrOne(parse(it, shapes)) })

        if (triples.size != 1) {
            throw ShapeCompileException(
                "SHACL path blank node must describe exactly one path constructor; got ${triples.size} for $node",
            )
        }
        return triples.first()
    }

    private fun parseList(head: RdfTerm, shapes: ShapeGraphIndex): List<ShaclPath> =
        shapes.parseRdfList(head).map { parse(it, shapes) }
}
