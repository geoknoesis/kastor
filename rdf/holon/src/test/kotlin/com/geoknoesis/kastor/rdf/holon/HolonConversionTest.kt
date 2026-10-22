package com.geoknoesis.kastor.rdf.holon

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFH
import org.junit.jupiter.api.Test
import kotlin.test.*

class HolonConversionTest {
  private val a = ex("A")
  private val b = ex("B")
  private val base = t(a, ex("value"), string("one"))

  @Test fun `both conversion directions retain shared blank nodes empty holons and M`() {
    val shared = BlankNode("shared")
    val source = HolonContext.of(HolonGraph(registrations(a, b, ex("Empty")) + t(b, RDFH.parentHolon, a)),
      mapOf(a to g(t(shared, ex("value"), string("a"))), b to g(t(shared, ex("next"), a))))
    val reified = source.convert(HolonProfile.REIFIER)
    val back = reified.context.convert(HolonProfile.DATASET)
    for (context in listOf(reified.context, back.context)) {
      assertEquals(source.registry, context.registry)
      assertEquals(source.metadata.getTriples().toSet(), context.metadata.getTriples().toSet())
      for (holon in source.registry) for (scope in HolonScope.entries.filter { it != HolonScope.GLOBAL }) {
        assertEquals(source.resolve(holon, scope).graph.getTriples().toSet(), context.resolve(holon, scope).graph.getTriples().toSet())
      }
    }
    assertTrue(reified.discardedAssertions.size() == 0)
    assertEquals(HolonPreservation.CONTENT, back.preservation)
    assertTrue(back.discardedAssertions.size() > 0)
  }

  @Test fun `conversion does not promote content management declarations`() {
    val content = t(b, RDFH.parentHolon, a)
    val source = HolonContext.of(HolonGraph(registrations(a, b)), mapOf(b to g(content)))
    val target = source.convert(HolonProfile.REIFIER).context
    assertFalse(target.metadata.hasTriple(content))
    assertEquals(0, target.resolve(a, HolonScope.AGGREGATED).graph.size())
    assertEquals(listOf(content), target.resolve(b).graph.getTriples())
  }

  @Test fun `blank-node holon mappings are fresh and reported only when missing`() {
    for (existing in listOf(false, true)) {
      val h = BlankNode("h")
      val m = HolonGraph(registrations(h) + if (existing) listOf(t(h, RDFH.contentGraph, ex("mapped"))) else emptyList())
      val source = HolonContext.fromGraph(HolonGraph(member(ex("r"), h, base)), m, metadataSharesSourceScope = true)
      val result = source.convert(HolonProfile.DATASET)
      assertEquals(listOf(base), result.context.resolve(h).graph.getTriples())
      if (existing) {
        assertTrue(result.derivedMappings.isEmpty())
        assertEquals(m.getTriples().toSet(), result.context.metadata.getTriples().toSet())
      } else {
        assertEquals(setOf(h), result.derivedMappings.keys)
        val name = result.derivedMappings.getValue(h)
        assertTrue(name is Iri)
        assertTrue(result.context.metadata.hasTriple(t(h, RDFH.contentGraph, name)))
      }
    }
  }

  @Test fun `shared mappings permit equal contents and reject unequal contents`() {
    val m = g(t(a, RDFH.contentGraph, ex("shared")), t(b, RDFH.contentGraph, ex("shared")))
    for (equal in listOf(true, false)) {
      val rows = member(ex("rA"), a, base) + member(ex("rB"), b, if (equal) base else t(b, ex("value"), string("two")))
      val source = HolonContext.fromGraph(HolonGraph(rows), m)
      if (equal) {
        val result = source.convert(HolonProfile.DATASET)
        assertEquals(listOf(base), result.context.resolve(a).graph.getTriples())
        assertEquals(listOf(base), result.context.resolve(b).graph.getTriples())
      } else error(HolonError.UNREPRESENTABLE_CONVERSION) { source.convert(HolonProfile.DATASET) }
    }
  }

  @Test fun `reifier-profile invalid mappings cannot become invalid dataset targets`() {
    val source = HolonContext.fromGraph(HolonGraph(member(ex("r"), a, base)), g(t(a, RDFH.contentGraph, string("invalid"))))
    assertEquals(listOf(base), source.resolve(a).graph.getTriples())
    error(HolonError.UNREPRESENTABLE_CONVERSION) { source.convert(HolonProfile.DATASET) }
  }

  @Test fun `assertion preservation retains unfiled occurrence metadata outside M and content`() {
    val provenance = t(ex("r"), ex("source"), ex("Document"))
    val m = HolonGraph(registrations(a))
    val source = HolonContext.fromGraph(HolonGraph(member(ex("r"), a, base) + provenance), m)
    val result = source.convert(HolonProfile.DATASET, HolonPreservation.ASSERTION)
    assertEquals(0, result.discardedAssertions.size())
    source.assertedGraph.getTriples().forEach { assertTrue(result.context.assertedGraph.hasTriple(it)) }
    assertFalse(result.context.metadata.hasTriple(provenance))
    assertFalse(result.context.resolve(a).graph.hasTriple(provenance))
    assertEquals(2, result.context.namedGraphs.size)
  }

  @Test fun `dataset to reifier assertion preservation retains ordinary unfiled assertions`() {
    val provenance = t(a, ex("source"), ex("Document"))
    val source = HolonContext.of(HolonGraph(registrations(a) + provenance), mapOf(a to g(base)))
    val result = source.convert(HolonProfile.REIFIER, HolonPreservation.ASSERTION)
    assertEquals(0, result.discardedAssertions.size())
    assertTrue(result.context.assertedGraph.hasTriple(provenance))
    assertFalse(result.context.resolve(a).graph.hasTriple(provenance))
  }

  @Test fun `preserving inactive invalid memberships fails instead of returning invalid reifier data`() {
    val inactive = t(ex("old"), RDFH.inHolon, a)
    val source = HolonContext.of(HolonGraph(registrations(a)), mapOf(ex("Unfiled") to g(inactive)))
    source.resolve(a)
    error(HolonError.UNREPRESENTABLE_CONVERSION) { source.convert(HolonProfile.REIFIER, HolonPreservation.ASSERTION) }
    val contentOnly = source.convert(HolonProfile.REIFIER)
    assertTrue(contentOnly.discardedAssertions.hasTriple(inactive))
    assertEquals(0, contentOnly.context.resolve(a).graph.size())
    val filed = HolonContext.of(HolonGraph(registrations(a)), mapOf(a to g(inactive)))
    error(HolonError.UNREPRESENTABLE_CONVERSION) { filed.convert(HolonProfile.REIFIER) }
  }

  @Test fun `even valid retained memberships must not add holon content`() {
    val source = HolonContext.of(HolonGraph(registrations(a)), mapOf(ex("Unfiled") to HolonGraph(member(ex("old"), a, base))))
    assertEquals(0, source.resolve(a).graph.size())
    error(HolonError.UNREPRESENTABLE_CONVERSION) { source.convert(HolonProfile.REIFIER, HolonPreservation.ASSERTION) }
  }

  @Test fun `unfiled graph names cannot collide with selected content graphs`() {
    val m = g(t(a, RDFH.contentGraph, Iri("urn:rdfh:unfiled")))
    val source = HolonContext.fromGraph(g(base), m)
    val target = source.convert(HolonProfile.DATASET, HolonPreservation.ASSERTION).context
    assertEquals(0, target.resolve(a).graph.size())
    assertTrue(target.assertedGraph.hasTriple(base))
    assertNotEquals(Iri("urn:rdfh:unfiled"), target.namedGraphs.filterValues { it.size() > 0 }.keys.single())
  }

  @Test fun `conversion reports every discarded assertion and unsupported target explicitly`() {
    val source = HolonContext.fromGraph(HolonGraph(member(ex("r"), a, base)), HolonGraph(registrations(a)))
    val result = source.convert(HolonProfile.DATASET)
    assertEquals(source.assertedGraph.getTriples().filterNot(result.context.assertedGraph::hasTriple).toSet(), result.discardedAssertions.getTriples().toSet())
    error(HolonError.UNSUPPORTED_CAPABILITY) { source.convert(HolonProfile.MIXED) }
    val invalid = HolonContext.of(g(t(a, RDFH.contentGraph, string("invalid"))))
    error(HolonError.INVALID_GRAPH_NAME) { invalid.convert(HolonProfile.REIFIER) }
  }
}
