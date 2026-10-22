package com.geoknoesis.kastor.rdf.holon

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.dsl.GraphDsl
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFH
import com.geoknoesis.kastor.rdf.vocab.SHACL
import org.junit.jupiter.api.Test
import kotlin.test.*

class HolonDslTest {
  private val city = ex("City")
  private val district = ex("District")
  private val imported = ex("Imported")
  private val other = ex("Other")
  private val alice = ex("Alice")
  private val bob = ex("Bob")
  private val knows = ex("knows")
  private val name = ex("name")

  @Test fun `authoring and handle scopes retain single-hop imports and metadata boundaries`() {
    val context = holons(sourceId = "authored", filtered = true) {
      holon(city) {
        imports(imported)
        content { alice has knows with bob }
      }
      holon(district) {
        parent(city)
        imports(other)
        content { bob[name] = "Bob" }
      }
      holon(imported) {
        imports(other)
        content { alice[name] = "Alice" }
      }
      holon(other) { content { other[name] = "Excluded" } }
    }
    val holon = context[city]
    assertEquals(1, holon.content.size())
    assertEquals(holon.content.getTriples(), holon.direct().graph.getTriples())
    assertEquals(setOf(city, district), holon.aggregated().attributedGraphs.keys)
    assertEquals(setOf(city, district, imported), holon.visible().attributedGraphs.keys)
    assertEquals(3, holon.resolve(HolonScope.VISIBLE).graph.size())
    assertEquals("authored", holon.direct().sourceId)
    assertTrue(holon.direct().filtered)
    assertTrue(holon.global().graph.hasTriple(t(district, RDFH.parentHolon, city)))
    assertFalse(holon.content.hasTriple(t(city, RDF.type, RDFH.Holon)))
  }

  @Test fun `blank holons and shared nested blank nodes need no provider`() {
    val h = BlankNode("holon")
    val shared = BlankNode("shared")
    val triple = t(shared, knows, TripleTerm(t(shared, name, string("nested"))))
    val context = holons {
      holon(h) { content(g(triple)) }
      holon(city) { parent(h); content { shared[name] = "Shared" } }
      holon(other)
    }
    assertTrue(context.metadata.hasTriple(t(h, RDFH.contentGraph, h)))
    assertEquals(listOf(triple), context.holon(h).content.getTriples())
    assertEquals(setOf(h, city), context[h].aggregated().attributedGraphs.keys)
    assertEquals(0, context[other].content.size())
    assertEquals(setOf(other), context[other].direct().attributedGraphs.keys)
  }

  @Test fun `repeated content blocks and shared graph mappings use RDF set semantics`() {
    val graphName = ex("SharedGraph")
    val context = holons {
      holon(city, graphName) {
        content { alice[name] = "Alice" }
        content { alice[name] = "Alice" }
      }
      holon(district, graphName) { content { bob[name] = "Bob" } }
    }
    assertEquals(2, context[city].content.size())
    assertEquals(context[city].content.getTriples(), context[district].content.getTriples())
    assertTrue(context.metadata.hasTriple(t(city, RDFH.contentGraph, graphName)))
  }

  @Test fun `built contexts do not retain mutable content or captured builder state`() {
    val graph = GraphDsl().apply { alice[name] = "Alice" }.build()
    lateinit var root: HolonsDsl
    lateinit var definition: HolonDsl
    val context = holons {
      root = this
      holon(city) { definition = this; content(graph) }
      graph.addTriple(t(bob, name, string("Too late")))
    }
    definition.content { other[name] = "Too late" }
    root.holon(other)
    assertEquals(setOf(city), context.registry)
    assertEquals(listOf(t(alice, name, string("Alice"))), context[city].content.getTriples())
    assertFailsWith<IllegalArgumentException> { holons { holon(city); holon(city) } }
  }

  @Test fun `authoring rejects unresolved declarations and invalid hierarchy before returning`() {
    error(HolonError.UNRESOLVED_IMPORT) { holons { holon(city) { imports(other) } } }
    error(HolonError.UNREGISTERED_PARENT_ENDPOINT) { holons { holon(city) { parent(other) } } }
    error(HolonError.PARENT_CYCLE) {
      holons { holon(city) { parent(district) }; holon(district) { parent(city) } }
    }
    error(HolonError.MULTIPLE_PARENTS) {
      holons { holon(city) { parent(district); parent(other) }; holon(district); holon(other) }
    }
    assertEquals(0, holons().registry.size)
  }

  @Test fun `handles preserve lazy mode-specific validation for existing contexts`() {
    val source = HolonContext.of(g(t(city, RDF.type, RDFH.Holon), t(city, RDFH.parentHolon, other)))
    assertEquals(0, source.holon(city).content.size())
    error(HolonError.UNREGISTERED_PARENT_ENDPOINT) { source[city].aggregated() }
    error(HolonError.UNKNOWN_HOLON) { source[other] }
  }

  @Test fun `handles distinguish identical identifiers in different snapshots`() {
    val one = holons { holon(city) }
    val two = holons { holon(city) }
    assertEquals(one.holon(city), one[city])
    assertEquals(one.holon(city).hashCode(), one[city].hashCode())
    assertNotEquals(one[city], two[city])
    assertFalse(one[city].equals(city))
    assertTrue(one[city].toString().contains(one.snapshotId))
  }

  @Test fun `query operations join across a selected scope and preserve attribution`() {
    val context = holons {
      holon(city) { content { alice has knows with bob } }
      holon(district) { parent(city); content { bob[name] = "Bob" } }
    }
    val factory = { Rdf.memory() }
    context[city].asDataset(HolonScope.AGGREGATED, attributed = true, repositoryFactory = factory).use {
      assertEquals(setOf(city, district), it.namedGraphs.keys)
      val row = it.select(SparqlSelectQuery("""
        SELECT ?name WHERE { <${alice.value}> <${knows.value}> ?person . ?person <${name.value}> ?name }
      """.trimIndent())).first()
      assertEquals("Bob", row?.getString("name"))
    }
    context[city].asDataset().use { assertFalse(it.ask(SparqlAskQuery("ASK { GRAPH ?g { ?s ?p ?o } }"))) }
    error(HolonError.UNSUPPORTED_CAPABILITY) { context[city].asDataset(HolonScope.GLOBAL) }
  }

  @Test fun `conversion operates on the full context and preserves referenced holons`() {
    val context = holons {
      holon(city) { imports(imported); content { alice[name] = "Alice" } }
      holon(imported) { content { bob[name] = "Bob" } }
    }
    val report = context[city].convert(HolonProfile.REIFIER, HolonPreservation.ASSERTION)
    val converted = report.context[city]
    assertEquals(HolonProfile.REIFIER, converted.context.profile)
    assertEquals(0, report.discardedAssertions.size())
    assertEquals(context.registry, converted.context.registry)
    assertEquals(context[city].visible().graph.getTriples().toSet(), converted.visible().graph.getTriples().toSet())
    assertEquals(context[imported].content.getTriples(), converted.context[imported].content.getTriples())
    val roundTrip = converted.convert(HolonProfile.DATASET).context[city]
    assertEquals(context[city].content.getTriples(), roundTrip.content.getTriples())
  }

  @Test fun `validation operations honor explicit shapes and the selected scope`() {
    val shapeName = ex("Shapes")
    val required = g(t(ex("Shape"), RDF.type, SHACL.NodeShape), t(ex("Shape"), SHACL.targetClass, ex("Person")),
      t(ex("Shape"), SHACL.property, BlankNode("property")), t(BlankNode("property"), SHACL.path, name),
      t(BlankNode("property"), SHACL.minCount, 1.toLiteral()))
    val context = holons {
      holon(city) {
        importsShapes(shapeName)
        importsShapes(listOf(shapeName))
        content { alice `is` ex("Person"); alice[name] = "Alice" }
      }
      holon(district) { parent(city); content { bob `is` ex("Person") } }
    }
    val shapes = mapOf(shapeName to required)
    assertEquals(required.getTriples().toSet(), context[city].effectiveShapes(shapes).getTriples().toSet())
    assertTrue(context[city].validate(shapes).report.isValid)
    assertFalse(context[city].validate(shapes, HolonScope.AGGREGATED).report.isValid)
    assertEquals(0, context[district].effectiveShapes(shapes).size())
    error(HolonError.UNSUPPORTED_CAPABILITY) { context[city].validate(shapes, entailmentRegime = other) }
    error(HolonError.MISSING_SHAPES_GRAPH) { context[city].validate(emptyMap()) }
  }
}
