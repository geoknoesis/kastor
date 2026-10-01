package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL

/**
 * A SHACL property path ([SHACL §2.3.1](https://www.w3.org/TR/shacl/#property-paths)), the value of `sh:path`.
 *
 * Build paths with the functions of [ShaclPathScope], which are in scope inside `nodeShape { }` and
 * `propertyShape { }` blocks, or with the constructors of the subclasses:
 *
 * ```kotlin
 * nodeShape("http://example.org/PersonShape") {
 *     property(inverse(EX.child)) { minCount = 1 }                       // ^ex:child
 *     property(sequence(EX.parent, EX.name)) { minCount = 1 }            // ex:parent / ex:name
 *     property(alternative(EX.phone, EX.email)) { minCount = 1 }         // ex:phone | ex:email
 *     property(zeroOrMore(RDFS.subClassOf)) { hasValue(EX.Agent) }       // rdfs:subClassOf*
 *     property(sequence(oneOrMore(EX.parent), predicate(EX.name))) { }   // ex:parent+ / ex:name
 * }
 * ```
 */
sealed class ShaclPropertyPath {
    /** A predicate path: one IRI. */
    data class Predicate(val iri: Iri) : ShaclPropertyPath()

    /** `[ sh:inversePath path ]`: traverses [path] backwards. */
    data class Inverse(val path: ShaclPropertyPath) : ShaclPropertyPath()

    /** A sequence path: an RDF list of at least two paths, traversed one after the other. */
    data class Sequence(val steps: List<ShaclPropertyPath>) : ShaclPropertyPath() {
        init {
            require(steps.size >= 2) { "A SHACL sequence path needs at least two members, got ${steps.size}" }
        }
    }

    /** `[ sh:alternativePath ( … ) ]`: any of at least two paths. */
    data class Alternative(val options: List<ShaclPropertyPath>) : ShaclPropertyPath() {
        init {
            require(options.size >= 2) { "A SHACL alternative path needs at least two members, got ${options.size}" }
        }
    }

    /** `[ sh:zeroOrMorePath path ]`. */
    data class ZeroOrMore(val path: ShaclPropertyPath) : ShaclPropertyPath()

    /** `[ sh:oneOrMorePath path ]`. */
    data class OneOrMore(val path: ShaclPropertyPath) : ShaclPropertyPath()

    /** `[ sh:zeroOrOnePath path ]`. */
    data class ZeroOrOne(val path: ShaclPropertyPath) : ShaclPropertyPath()
}

/**
 * Builders for [ShaclPropertyPath], available inside [NodeShapeDsl] and [PropertyShapeDsl] blocks. Each builder
 * takes IRIs (predicate paths) or paths; wrap an IRI with [predicate] to mix both in one call.
 */
interface ShaclPathScope {
    /** The predicate path of [iri] (to combine an IRI with other paths in [sequence] / [alternative]). */
    fun predicate(iri: Iri): ShaclPropertyPath = ShaclPropertyPath.Predicate(iri)

    /** `^path` */
    fun inverse(path: ShaclPropertyPath): ShaclPropertyPath = ShaclPropertyPath.Inverse(path)

    /** `^predicate` */
    fun inverse(iri: Iri): ShaclPropertyPath = inverse(predicate(iri))

    /** `first / second / …` */
    fun sequence(first: ShaclPropertyPath, second: ShaclPropertyPath, vararg rest: ShaclPropertyPath): ShaclPropertyPath =
        ShaclPropertyPath.Sequence(listOf(first, second) + rest)

    /** `first / second` over predicates. */
    fun sequence(first: Iri, second: Iri): ShaclPropertyPath = sequence(predicate(first), predicate(second))

    /** `first / second / third` over predicates (wrap IRIs with [predicate] for longer sequences). */
    fun sequence(first: Iri, second: Iri, third: Iri): ShaclPropertyPath =
        sequence(predicate(first), predicate(second), predicate(third))

    /** `first | second | …` */
    fun alternative(first: ShaclPropertyPath, second: ShaclPropertyPath, vararg rest: ShaclPropertyPath): ShaclPropertyPath =
        ShaclPropertyPath.Alternative(listOf(first, second) + rest)

    /** `first | second` over predicates. */
    fun alternative(first: Iri, second: Iri): ShaclPropertyPath = alternative(predicate(first), predicate(second))

    /** `first | second | third` over predicates (wrap IRIs with [predicate] for more alternatives). */
    fun alternative(first: Iri, second: Iri, third: Iri): ShaclPropertyPath =
        alternative(predicate(first), predicate(second), predicate(third))

    /** `path*` */
    fun zeroOrMore(path: ShaclPropertyPath): ShaclPropertyPath = ShaclPropertyPath.ZeroOrMore(path)

    /** `predicate*` */
    fun zeroOrMore(iri: Iri): ShaclPropertyPath = zeroOrMore(predicate(iri))

    /** `path+` */
    fun oneOrMore(path: ShaclPropertyPath): ShaclPropertyPath = ShaclPropertyPath.OneOrMore(path)

    /** `predicate+` */
    fun oneOrMore(iri: Iri): ShaclPropertyPath = oneOrMore(predicate(iri))

    /** `path?` */
    fun zeroOrOne(path: ShaclPropertyPath): ShaclPropertyPath = ShaclPropertyPath.ZeroOrOne(path)

    /** `predicate?` */
    fun zeroOrOne(iri: Iri): ShaclPropertyPath = zeroOrOne(predicate(iri))
}

/**
 * Serializes [path] to the standard SHACL path RDF and returns the `sh:path` value. The triples describing a complex
 * path (blank nodes, RDF lists) are appended to [triples]; a predicate path needs none.
 */
internal fun shaclPathToRdf(path: ShaclPropertyPath, nextBnode: (String) -> BlankNode, triples: MutableList<RdfTriple>): RdfTerm {
    fun list(members: List<ShaclPropertyPath>): RdfTerm {
        // Members first, so that nested structures are numbered before the list cells that refer to them.
        val terms = members.map { shaclPathToRdf(it, nextBnode, triples) }
        val cells = terms.map { nextBnode("path") }
        terms.forEachIndexed { i, term ->
            triples += RdfTriple(cells[i], RDF.first, term)
            triples += RdfTriple(cells[i], RDF.rest, cells.getOrNull(i + 1) ?: RDF.nil)
        }
        return cells.first()
    }
    fun unary(property: Iri, child: ShaclPropertyPath): RdfTerm {
        val value = shaclPathToRdf(child, nextBnode, triples)
        return nextBnode("path").also { triples += RdfTriple(it, property, value) }
    }
    return when (path) {
        is ShaclPropertyPath.Predicate -> path.iri
        is ShaclPropertyPath.Inverse -> unary(SHACL.inversePath, path.path)
        is ShaclPropertyPath.Sequence -> list(path.steps)
        is ShaclPropertyPath.Alternative -> {
            val head = list(path.options)
            nextBnode("path").also { triples += RdfTriple(it, SHACL.alternativePath, head) }
        }
        is ShaclPropertyPath.ZeroOrMore -> unary(SHACL.zeroOrMorePath, path.path)
        is ShaclPropertyPath.OneOrMore -> unary(SHACL.oneOrMorePath, path.path)
        is ShaclPropertyPath.ZeroOrOne -> unary(SHACL.zeroOrOnePath, path.path)
    }
}
