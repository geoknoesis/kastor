package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException

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
            // `sh:path ( )` is rdf:nil: an empty sequence, not the predicate rdf:nil.
            RDF.nil -> throw ShapeCompileException(
                "Ill-formed SHACL path: an empty list is not a sequence path (a sequence path is a list of at least two paths)",
            )
            is Iri -> ShaclPath.Predicate(term)
            is BlankNode ->
                if (shapes.objects(term, RDF.first).isNotEmpty() || shapes.objects(term, RDF.rest).isNotEmpty()) {
                    ShaclPath.Sequence(members("sequence path", term, shapes, next))
                } else {
                    parseBlankPath(term, shapes, next)
                }
            else -> throw ShapeCompileException("Unsupported SHACL path term: $term")
        }
    }

    /** The member paths of the list [head] of a sequence or alternative path: SHACL requires at least two. */
    private fun members(kind: String, head: RdfTerm, shapes: ShapeGraphIndex, next: Set<RdfTerm>): List<ShaclPath> {
        val terms = shapes.parseRdfList(head)
        if (terms.size < 2) {
            throw ShapeCompileException(
                "Ill-formed SHACL path: $kind with ${terms.size} member(s); SHACL requires a list of at least two paths - " +
                    "write a single member as the path itself",
            )
        }
        return terms.map { parse(it, shapes, next) }
    }

    private fun parseBlankPath(node: BlankNode, shapes: ShapeGraphIndex, next: Set<RdfTerm>): ShaclPath {
        val triples = shapes.objects(node, SHACL.alternativePath).map { ShaclPath.Alternative(members("alternative path", it, shapes, next)) }
            .plus(shapes.objects(node, SHACL.sequencePath).map { ShaclPath.Sequence(members("sequence path", it, shapes, next)) })
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
}
