package com.geoknoesis.kastor.rdf.holon

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFH
import com.geoknoesis.kastor.rdf.vocab.SHACL
import org.junit.jupiter.api.Test
import kotlin.test.*

class HolonValidationTest {
  private val a = ex("A")
  private val b = ex("B")
  private val shapes = ex("Shapes")
  private val property = BlankNode("property")
  private val required = g(t(ex("S"), RDF.type, SHACL.NodeShape), t(ex("S"), SHACL.targetClass, ex("Thing")),
    t(ex("S"), SHACL.property, property), t(property, SHACL.path, ex("name")), t(property, SHACL.minCount, 1.toLiteral()))

  private fun context() = HolonContext.of(
    HolonGraph(registrations(a, b) + t(a, RDFH.importsShapes, shapes) + t(b, RDFH.parentHolon, a)),
    mapOf(a to g(t(ex("good"), RDF.type, ex("Thing")), t(ex("good"), ex("name"), string("name"))),
      b to g(t(ex("bad"), RDF.type, ex("Thing")))), sourceId = "validation-source", filtered = true,
  )

  @Test fun `domain violations are results distinct from structural failures`() {
    val context = context()
    val direct = context.validate(a, mapOf(shapes to required))
    assertTrue(direct.report.isValid)
    assertEquals(setOf(shapes), direct.shapeGraphNames)
    assertEquals("validation-source", direct.resolution.sourceId)
    assertTrue(direct.resolution.filtered)
    assertEquals(required.getTriples().toSet(), direct.effectiveShapes.getTriples().toSet())
    val aggregate = context.validate(a, mapOf(shapes to required), HolonScope.AGGREGATED)
    assertFalse(aggregate.report.isValid)
    assertEquals(Iri("http://www.w3.org/ns/entailment/Simple"), aggregate.entailmentRegime)
  }

  @Test fun `shape imports are explicit and never inherited from parents or content imports`() {
    val context = context()
    val child = context.validate(b, emptyMap())
    assertTrue(child.report.isValid)
    assertEquals(0, child.effectiveShapes.size())
    val importer = HolonContext.of(HolonGraph(registrations(a, b) + t(a, RDFH.imports, b) + t(b, RDFH.importsShapes, shapes)))
    assertEquals(0, importer.effectiveShapes(a, emptyMap()).size())
  }

  @Test fun `missing shapes are errors but empty supplied graphs are valid`() {
    error(HolonError.MISSING_SHAPES_GRAPH) { context().validate(a, emptyMap()) }
    assertTrue(context().validate(a, mapOf(shapes to g())).report.isValid)
    val invalid = HolonContext.of(HolonGraph(registrations(a) + t(a, RDFH.importsShapes, string("not an IRI"))))
    error(HolonError.MISSING_SHAPES_GRAPH) { invalid.validate(a, emptyMap()) }
  }

  @Test fun `ill-formed shapes and conflicting shape unions are rejected before validation`() {
    val invalid = g(t(ex("P"), RDF.type, SHACL.PropertyShape), t(ex("P"), SHACL.minCount, 1.toLiteral()))
    error(HolonError.ILL_FORMED_SHAPES_GRAPH) { context().validate(a, mapOf(shapes to invalid)) }
    val other = ex("OtherShapes")
    val c = HolonContext.of(HolonGraph(registrations(a) + t(a, RDFH.importsShapes, shapes) + t(a, RDFH.importsShapes, other)))
    val one = g(t(ex("P"), RDF.type, SHACL.PropertyShape), t(ex("P"), SHACL.path, ex("name")), t(ex("P"), SHACL.minCount, 1.toLiteral()))
    val two = g(t(ex("P"), RDF.type, SHACL.PropertyShape), t(ex("P"), SHACL.path, ex("name")), t(ex("P"), SHACL.minCount, 2.toLiteral()))
    error(HolonError.ILL_FORMED_SHAPES_GRAPH) { c.validate(a, mapOf(shapes to one, other to two)) }
  }

  @Test fun `global validation reads unrelated data and shapes stay outside G`() {
    val context = HolonContext.of(HolonGraph(registrations(a) + t(a, RDFH.importsShapes, shapes)),
      mapOf(ex("Unselected") to g(t(ex("bad"), RDF.type, ex("Thing")))))
    assertTrue(context.validate(a, mapOf(shapes to required)).report.isValid)
    assertFalse(context.validate(a, mapOf(shapes to required), HolonScope.GLOBAL).report.isValid)
    assertFalse(context.assertedGraph.hasTriple(required.getTriples().first()))
  }

  @Test fun `data sh shapesGraph does not discover additional constraints`() {
    val source = HolonContext.of(HolonGraph(registrations(a)), mapOf(a to g(t(a, SHACL.shapesGraph, shapes))))
    assertTrue(source.validate(a, mapOf(shapes to required)).report.isValid)
  }

  @Test fun `non-simple validation regime is rejected rather than ignored`() {
    error(HolonError.UNSUPPORTED_CAPABILITY) { context().validate(a, mapOf(shapes to required), entailmentRegime = ex("Regime")) }
  }
}
