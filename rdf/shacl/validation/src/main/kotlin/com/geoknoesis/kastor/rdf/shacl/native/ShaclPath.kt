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

/**
 * Parses `sh:path` values into [ShaclPath]s.
 *
 * A blank node referenced several times (`_:p0 sh:alternativePath ( _:p1 _:p1 )`) is parsed once per [parse] call and
 * its [ShaclPath] shared, so the result is a DAG as large as the shapes-graph structure. Its *unfolding* (the tree
 * the evaluator, `$PATH` rendering and structural equality walk) can still be exponential in the number of shared
 * levels, so a path that unfolds to more than [MAX_PATH_SIZE] nodes is refused with [ShapeCompileException]; nesting
 * beyond [MAX_PATH_DEPTH] and cycles are refused too. Every node parsed ticks the shapes graph's budget.
 */
internal object ShaclPathParser {
    /** The most nodes the unfolded tree of one path may have (paths written by hand have a few dozen at most). */
    const val MAX_PATH_SIZE = 4096

    /** The deepest nesting of path constructors accepted. */
    const val MAX_PATH_DEPTH = 128

    fun parse(term: RdfTerm, shapes: ShapeGraphIndex): ShaclPath = Parse(shapes).path(term)

    /** One [parse] call: its memo of parsed nodes and their unfolded sizes, and the nodes being parsed (cycle check). */
    private class Parse(private val shapes: ShapeGraphIndex) {
        private val parsed = HashMap<RdfTerm, ShaclPath>()
        private val sizes = java.util.IdentityHashMap<ShaclPath, Int>()
        private val inProgress = LinkedHashSet<RdfTerm>()

        fun path(term: RdfTerm): ShaclPath {
            parsed[term]?.let { return it }
            shapes.tick("path compilation")
            if (term in inProgress || inProgress.size >= MAX_PATH_DEPTH) throw ShapeCompileException("Cyclic or excessively nested SHACL path")
            inProgress.add(term)
            val path = try {
                build(term)
            } finally {
                inProgress.remove(term)
            }
            val size = 1L + children(path).sumOf { sizes.getValue(it).toLong() }
            if (size > MAX_PATH_SIZE) {
                throw ShapeCompileException(
                    "SHACL path $term unfolds to more than $MAX_PATH_SIZE path nodes (shared sub-paths count once per use)",
                )
            }
            sizes[path] = size.toInt()
            parsed[term] = path
            return path
        }

        private fun children(path: ShaclPath): List<ShaclPath> = when (path) {
            is ShaclPath.Predicate -> emptyList()
            is ShaclPath.Inverse -> listOf(path.child)
            is ShaclPath.Sequence -> path.segments
            is ShaclPath.Alternative -> path.options
            is ShaclPath.ZeroOrMore -> listOf(path.child)
            is ShaclPath.OneOrMore -> listOf(path.child)
            is ShaclPath.ZeroOrOne -> listOf(path.child)
        }

        private fun build(term: RdfTerm): ShaclPath = when (term) {
            // `sh:path ( )` is rdf:nil: an empty sequence, not the predicate rdf:nil.
            RDF.nil -> throw ShapeCompileException(
                "Ill-formed SHACL path: an empty list is not a sequence path (a sequence path is a list of at least two paths)",
            )
            is Iri -> ShaclPath.Predicate(term)
            is BlankNode ->
                if (shapes.objects(term, RDF.first).isNotEmpty() || shapes.objects(term, RDF.rest).isNotEmpty()) {
                    ShaclPath.Sequence(members("sequence path", term))
                } else {
                    blankPath(term)
                }
            else -> throw ShapeCompileException("Unsupported SHACL path term: $term")
        }

        /** The member paths of the list [head] of a sequence or alternative path: SHACL requires at least two. */
        private fun members(kind: String, head: RdfTerm): List<ShaclPath> {
            val terms = shapes.parseRdfList(head)
            if (terms.size < 2) {
                throw ShapeCompileException(
                    "Ill-formed SHACL path: $kind with ${terms.size} member(s); SHACL requires a list of at least two paths - " +
                        "write a single member as the path itself",
                )
            }
            return terms.map { path(it) }
        }

        private fun blankPath(node: BlankNode): ShaclPath {
            val constructors = shapes.objects(node, SHACL.alternativePath).size + shapes.objects(node, SHACL.sequencePath).size +
                shapes.objects(node, SHACL.inversePath).size + shapes.objects(node, SHACL.zeroOrMorePath).size +
                shapes.objects(node, SHACL.oneOrMorePath).size + shapes.objects(node, SHACL.zeroOrOnePath).size
            if (constructors != 1) {
                throw ShapeCompileException(
                    "SHACL path blank node must describe exactly one path constructor; got $constructors for $node",
                )
            }
            shapes.objects(node, SHACL.alternativePath).singleOrNull()?.let { return ShaclPath.Alternative(members("alternative path", it)) }
            shapes.objects(node, SHACL.sequencePath).singleOrNull()?.let { return ShaclPath.Sequence(members("sequence path", it)) }
            shapes.objects(node, SHACL.inversePath).singleOrNull()?.let { return ShaclPath.Inverse(path(it)) }
            shapes.objects(node, SHACL.zeroOrMorePath).singleOrNull()?.let { return ShaclPath.ZeroOrMore(path(it)) }
            shapes.objects(node, SHACL.oneOrMorePath).singleOrNull()?.let { return ShaclPath.OneOrMore(path(it)) }
            return ShaclPath.ZeroOrOne(path(shapes.objects(node, SHACL.zeroOrOnePath).single()))
        }
    }
}
