package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidatorProvider
import com.geoknoesis.kastor.rdf.shacl.toShaclValidationReportRdf
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A property path whose blank nodes are shared (`_:p0 sh:alternativePath ( _:p1 _:p1 )`, ...) is a DAG of a few
 * triples per level that unfolds into a tree of 2^levels nodes. The parser, the evaluator and the report writer must
 * stay linear in the shapes graph, and a path whose unfolding is beyond [ShaclPathParser.MAX_PATH_SIZE] is refused.
 */
class SharedSubPathTest {

    private val ex = "http://example.org/"
    private val q = Iri("${ex}q")

    /** `_:p0` .. `_:p<levels-1>`, each an alternative (or sequence) of the next one twice; the last one of `ex:q` twice. */
    private fun sharedPath(levels: Int, sequence: Boolean = false): List<RdfTriple> {
        val out = ArrayList<RdfTriple>()
        for (i in 0 until levels) {
            val node = BlankNode("p$i")
            val child: RdfTerm = if (i == levels - 1) q else BlankNode("p${i + 1}")
            val first = if (sequence) node else BlankNode("l${i}a")
            val second = BlankNode("l${i}b")
            if (!sequence) out.add(RdfTriple(node, SHACL.alternativePath, first))
            out.add(RdfTriple(first, RDF.first, child))
            out.add(RdfTriple(first, RDF.rest, second))
            out.add(RdfTriple(second, RDF.first, child))
            out.add(RdfTriple(second, RDF.rest, RDF.nil))
        }
        return out
    }

    private fun shapes(levels: Int): List<RdfTriple> {
        val shape = Iri("${ex}S")
        val property = BlankNode("ps")
        return sharedPath(levels) + listOf(
            RdfTriple(shape, RDF.type, SHACL.NodeShape),
            RdfTriple(shape, SHACL.targetNode, Iri("${ex}a")),
            RdfTriple(shape, SHACL.`property`, property),
            RdfTriple(property, SHACL.path, BlankNode("p0")),
            RdfTriple(property, SHACL.minCount, TypedLiteral("1", XSD.integer)),
        )
    }

    private fun graph(triples: List<RdfTriple>) = graphFromTriples(triples)

    @Test
    fun `a shared sub-path is parsed once and kept shared`() {
        val path = ShaclPathParser.parse(BlankNode("p0"), ShapeGraphIndex(sharedPath(10))) as ShaclPath.Alternative
        assertSame(path.options[0], path.options[1])
        var level: ShaclPath = path
        repeat(9) { level = (level as ShaclPath.Alternative).options[0] }
        assertEquals(ShaclPath.Alternative(listOf(ShaclPath.Predicate(q), ShaclPath.Predicate(q))), level)
    }

    @Test
    fun `a path that unfolds beyond the limit is refused in linear time`() {
        for (sequence in listOf(false, true)) {
            assertTimeoutPreemptively(Duration.ofSeconds(60)) {
                val error = assertThrows(ShapeCompileException::class.java) {
                    ShaclPathParser.parse(BlankNode("p0"), ShapeGraphIndex(sharedPath(60, sequence)))
                }
                assertTrue("${ShaclPathParser.MAX_PATH_SIZE}" in error.message.orEmpty(), error.message)
            }
        }
        // Validation reports it as a compile error rather than running out of memory or time.
        assertTimeoutPreemptively(Duration.ofSeconds(60)) {
            assertThrows(ShaclValidationException::class.java) {
                NativeShaclValidatorProvider().createValidator(ValidationConfig.default())
                    .validate(Rdf.graph { }, graph(shapes(60)))
            }
        }
    }

    @Test
    fun `path parsing consumes the budget`() {
        var expired = false
        val budget = ValidationBudget(Duration.ofSeconds(1)) { if (expired) 1_000_000_000_000L else 0L }
        val index = ShapeGraphIndex(sharedPath(10), budget)
        expired = true
        val error = assertThrows(ShaclValidationException::class.java) { ShaclPathParser.parse(BlankNode("p0"), index) }
        assertTrue("timed out" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a shared path is evaluated and reported in time linear in the shapes graph`() {
        val levels = 11 // 4095 nodes unfolded, just below the limit
        val validator = NativeShaclValidatorProvider().createValidator(ValidationConfig.default())
        val conforming = validator.validate(Rdf.graph { Iri("${ex}a") - q - Iri("${ex}b") }, graph(shapes(levels)))
        assertTrue(conforming.isValid, "${conforming.violations}")

        val report = validator.validate(Rdf.graph { Iri("${ex}a") - Iri("${ex}other") - Iri("${ex}b") }, graph(shapes(levels)))
        val rdf = report.toShaclValidationReportRdf().getTriples()
        val root = rdf.single { it.predicate == SHACL.resultPath }.obj
        // Too large to unfold, the copy shares its blank nodes as the shapes graph does: one copy per source blank node.
        val bySubject = rdf.groupBy { it.subject }
        val reached = HashSet<RdfResource>()
        val queue = ArrayDeque<RdfTerm>(listOf(root))
        var pathTriples = 0
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst() as? BlankNode ?: continue
            if (!reached.add(node)) continue
            for (t in bySubject[node].orEmpty()) { pathTriples++; queue.addLast(t.obj) }
        }
        assertEquals(sharedPath(levels).size, pathTriples)
    }
    @Test
    fun `the longest sequence path accepted is compiled and reported without deep recursion`() {
        val members = ShaclPathParser.MAX_PATH_SIZE - 1
        val cells = (0 until members).map { BlankNode("c$it") }
        val list = cells.flatMapIndexed { i, cell ->
            listOf(RdfTriple(cell, RDF.first, Iri("${ex}p$i")), RdfTriple(cell, RDF.rest, cells.getOrElse(i + 1) { RDF.nil }))
        }
        val shapes = list + listOf(
            RdfTriple(Iri("${ex}S"), RDF.type, SHACL.NodeShape),
            RdfTriple(Iri("${ex}S"), SHACL.targetNode, Iri("${ex}a")),
            RdfTriple(Iri("${ex}S"), SHACL.`property`, BlankNode("ps")),
            RdfTriple(BlankNode("ps"), SHACL.path, cells[0]),
            RdfTriple(BlankNode("ps"), SHACL.minCount, TypedLiteral("1", XSD.integer)),
        )
        val report = NativeShaclValidatorProvider().createValidator(ValidationConfig.default()).validate(Rdf.graph { }, graph(shapes))
        assertEquals(1, report.violations.size)
        assertEquals(list.size, report.violations.single().resultPathTriples.size)
        val tooLong = list + RdfTriple(BlankNode("x"), RDF.first, Iri("${ex}x")) + RdfTriple(BlankNode("x"), RDF.rest, cells[0])
        assertThrows(ShapeCompileException::class.java) { ShaclPathParser.parse(BlankNode("x"), ShapeGraphIndex(tooLong)) }
    }
    @Test
    fun `a small shared path is reported unfolded, as the W3C suite expects`() {
        // ( _:inv _:inv ): two sh:inversePath nodes in the result path (path-complex-002), not one shared node.
        val inv = BlankNode("inv")
        val shapes = listOf(
            RdfTriple(inv, SHACL.inversePath, q),
            RdfTriple(BlankNode("c0"), RDF.first, inv), RdfTriple(BlankNode("c0"), RDF.rest, BlankNode("c1")),
            RdfTriple(BlankNode("c1"), RDF.first, inv), RdfTriple(BlankNode("c1"), RDF.rest, RDF.nil),
            RdfTriple(Iri("${ex}S"), RDF.type, SHACL.PropertyShape),
            RdfTriple(Iri("${ex}S"), SHACL.targetNode, Iri("${ex}a")),
            RdfTriple(Iri("${ex}S"), SHACL.path, BlankNode("c0")),
            RdfTriple(Iri("${ex}S"), SHACL.minCount, TypedLiteral("1", XSD.integer)),
        )
        val report = NativeShaclValidatorProvider().createValidator(ValidationConfig.default()).validate(Rdf.graph { }, graph(shapes))
        val rdf = report.toShaclValidationReportRdf().getTriples()
        assertEquals(2, rdf.count { it.predicate == SHACL.inversePath })
        assertEquals(2, rdf.filter { it.predicate == SHACL.inversePath }.map { it.subject }.toSet().size)
    }
}
