package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.MutableRdfGraph
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationReport
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidatorProvider
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The DSL expresses every SHACL property path (inverse, sequence, alternative, zero-or-more, one-or-more,
 * zero-or-one) as the standard path RDF, and the native validator evaluates the shapes it produces.
 */
class ShaclDslPropertyPathTest {

    private val ex = "http://example.org/"
    private fun iri(local: String) = Iri(ex + local)
    private val shape = iri("S")

    private fun List<RdfTriple>.objectsOf(s: RdfTerm, p: Iri): List<RdfTerm> = filter { it.subject == s && it.predicate == p }.map { it.obj }

    private fun List<RdfTriple>.list(head: RdfTerm): List<RdfTerm> {
        val out = mutableListOf<RdfTerm>()
        var node = head
        while (node != RDF.nil) {
            assertTrue(node is BlankNode, "list cell must be a blank node: $node")
            out += objectsOf(node, RDF.first).single()
            node = objectsOf(node, RDF.rest).single()
        }
        return out
    }

    /** The `sh:path` value of the single property shape of `ex:S`. */
    private fun MutableRdfGraph.pathOfOnlyProperty(): Pair<List<RdfTriple>, RdfTerm> {
        val triples = getTriples().toList()
        val propertyShape = triples.objectsOf(shape, SHACL.`property`).single()
        return triples to triples.objectsOf(propertyShape, SHACL.path).single()
    }

    private fun shapeWith(configure: NodeShapeDsl.() -> Unit): MutableRdfGraph = shacl { nodeShape(ex + "S") { targetClass(iri("T")); configure() } }

    // --- emitted RDF -----------------------------------------------------------------------------------------------

    @Test
    fun `unary path builders emit one blank node with the SHACL path property`() {
        val cases: List<Pair<Iri, NodeShapeDsl.() -> ShaclPropertyPath>> = listOf(
            SHACL.inversePath to { inverse(iri("p")) },
            SHACL.zeroOrMorePath to { zeroOrMore(iri("p")) },
            SHACL.oneOrMorePath to { oneOrMore(iri("p")) },
            SHACL.zeroOrOnePath to { zeroOrOne(iri("p")) },
        )
        for ((predicate, build) in cases) {
            val (triples, path) = shapeWith { property(build()) { minCount = 1 } }.pathOfOnlyProperty()
            assertTrue(path is BlankNode, "$predicate: $path")
            assertEquals(listOf(RdfTriple(path as BlankNode, predicate, iri("p"))), triples.filter { it.subject == path }, predicate.value)
        }
    }

    @Test
    fun `a sequence path is an RDF list and an alternative path wraps one`() {
        val (sequenceTriples, sequence) = shapeWith { property(sequence(iri("p"), iri("q"), iri("r"))) { minCount = 1 } }.pathOfOnlyProperty()
        assertEquals(listOf<RdfTerm>(iri("p"), iri("q"), iri("r")), sequenceTriples.list(sequence))

        val (alternativeTriples, alternative) = shapeWith { property(alternative(iri("p"), iri("q"))) { minCount = 1 } }.pathOfOnlyProperty()
        val head = alternativeTriples.objectsOf(alternative, SHACL.alternativePath).single()
        assertEquals(listOf<RdfTerm>(iri("p"), iri("q")), alternativeTriples.list(head))
        assertEquals(1, alternativeTriples.count { it.subject == alternative })
    }

    @Test
    fun `paths nest`() {
        val (triples, path) = shapeWith {
            property(sequence(inverse(iri("child")), zeroOrMore(alternative(iri("p"), iri("q"))), predicate(iri("name")))) { minCount = 1 }
        }.pathOfOnlyProperty()
        val steps = triples.list(path)
        assertEquals(3, steps.size)
        assertEquals(listOf<RdfTerm>(iri("child")), triples.objectsOf(steps[0], SHACL.inversePath))
        val closure = triples.objectsOf(steps[1], SHACL.zeroOrMorePath).single()
        assertEquals(listOf<RdfTerm>(iri("p"), iri("q")), triples.list(triples.objectsOf(closure, SHACL.alternativePath).single()))
        assertEquals(iri("name"), steps[2])
    }

    @Test
    fun `sequence and alternative paths need at least two members`() {
        assertThrows(IllegalArgumentException::class.java) { ShaclPropertyPath.Sequence(listOf(ShaclPropertyPath.Predicate(iri("p")))) }
        assertThrows(IllegalArgumentException::class.java) { ShaclPropertyPath.Alternative(emptyList()) }
    }

    // --- source compatibility of the IRI API -------------------------------------------------------------------------

    @Test
    fun `the Iri path API is unchanged and a complex path is replaced like any single-valued parameter`() {
        var seen: PropertyShapeDsl? = null
        val graph = shacl {
            propertyShape(ex + "PS") {
                seen = this
                path = iri("p")
                assertEquals(iri("p"), path)
                assertEquals(ShaclPropertyPath.Predicate(iri("p")), propertyPath)
                path("http://example.org/q")
                assertEquals(iri("q"), path)
                path(sequence(iri("p"), iri("q")))
                assertNull(path, "a complex path is not an IRI")
                assertEquals(ShaclPropertyPath.Sequence(listOf(ShaclPropertyPath.Predicate(iri("p")), ShaclPropertyPath.Predicate(iri("q")))), propertyPath)
                path = iri("r")
            }
        }
        // Replacing the sequence path removed its list cells: only the last assignment remains.
        val triples = graph.getTriples().toList()
        assertEquals(listOf<RdfTerm>(iri("r")), triples.objectsOf(iri("PS"), SHACL.path))
        assertTrue(triples.none { it.predicate == RDF.first || it.predicate == RDF.rest }, triples.toString())
        assertEquals(iri("r"), seen!!.path)
    }

    @Test
    fun `property with an Iri or a string still emits a predicate path`() {
        val triples = shacl {
            prefix("ex", ex)
            nodeShape(ex + "S") {
                property(iri("p")) { minCount = 1 }
                property("ex:q") { minCount = 1 }
                property(predicate(iri("r"))) { minCount = 1 }
            }
        }.getTriples().toList()
        val paths = triples.objectsOf(shape, SHACL.`property`).map { triples.objectsOf(it, SHACL.path).single() }
        assertEquals(setOf<RdfTerm>(iri("p"), iri("q"), iri("r")), paths.toSet())
    }

    // --- validation with the native engine ---------------------------------------------------------------------------

    private fun validate(shapes: RdfGraph, data: String): ValidationReport =
        NativeShaclValidatorProvider().createValidator(ValidationConfig())
            .validate(Rdf.parse("@prefix ex: <$ex> .\n$data", RdfFormat.TURTLE), shapes)

    private fun failing(report: ValidationReport) = report.violations.map { it.focusNode }.toSet()

    @Test
    fun `inverse path shapes validate with the native validator`() {
        // Every ex:T must be the ex:child of exactly one node.
        val shapes = shapeWith { property(inverse(iri("child"))) { minCount = 1; maxCount = 1 } }
        val report = validate(shapes, "ex:a a ex:T . ex:b a ex:T . ex:c a ex:T . ex:p ex:child ex:a , ex:c . ex:q ex:child ex:c .")
        assertEquals(setOf<RdfTerm>(iri("b"), iri("c")), failing(report))
        assertEquals(setOf(ConstraintType.MIN_COUNT, ConstraintType.MAX_COUNT), report.violations.map { it.constraint.constraintType }.toSet())
    }

    @Test
    fun `sequence and alternative path shapes validate with the native validator`() {
        val sequenceShapes = shapeWith { property(sequence(iri("parent"), iri("name"))) { minCount = 1 } }
        val data = "ex:a a ex:T ; ex:parent ex:p . ex:p ex:name 'P' . ex:b a ex:T ; ex:parent ex:q . ex:c a ex:T ; ex:phone '1' . ex:d a ex:T ; ex:email 'd@x' ."
        assertEquals(setOf<RdfTerm>(iri("b"), iri("c"), iri("d")), failing(validate(sequenceShapes, data)))

        val alternativeShapes = shapeWith { property(alternative(iri("phone"), iri("email"))) { minCount = 1 } }
        assertEquals(setOf<RdfTerm>(iri("a"), iri("b")), failing(validate(alternativeShapes, data)))
    }

    @Test
    fun `closure path shapes validate with the native validator`() {
        val data = "ex:a a ex:T ; ex:next ex:b . ex:b a ex:T ; ex:next ex:c . ex:c a ex:T ."
        // zeroOrMore: the node itself and everything reachable; ex:c is reachable from all three.
        assertTrue(validate(shapeWith { property(zeroOrMore(iri("next"))) { hasValue(iri("c")) } }, data).isValid)
        // oneOrMore: ex:c reaches nothing, so it does not reach ex:c.
        assertEquals(setOf<RdfTerm>(iri("c")), failing(validate(shapeWith { property(oneOrMore(iri("next"))) { hasValue(iri("c")) } }, data)))
        // zeroOrOne: the node itself or a direct successor.
        assertEquals(setOf<RdfTerm>(iri("a")), failing(validate(shapeWith { property(zeroOrOne(iri("next"))) { hasValue(iri("c")) } }, data)))
    }

    @Test
    fun `a nested path shape validates with the native validator`() {
        // The names of all ancestors, where an ancestor is reached through ex:parent or the inverse of ex:child.
        val ancestor = "alternative(parent, ^child)+"
        val shapes = shapeWith {
            property(sequence(oneOrMore(alternative(predicate(iri("parent")), inverse(iri("child")))), predicate(iri("name")))) { minCount = 2 }
        }
        val data = "ex:a a ex:T ; ex:parent ex:b . ex:b ex:name 'B' . ex:c ex:child ex:b ; ex:name 'C' . ex:d a ex:T ; ex:parent ex:c ."
        assertEquals(setOf<RdfTerm>(iri("d")), failing(validate(shapes, data)), ancestor)
    }
}
