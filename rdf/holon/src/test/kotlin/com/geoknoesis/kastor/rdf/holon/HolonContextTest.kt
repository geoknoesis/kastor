package com.geoknoesis.kastor.rdf.holon

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFH
import com.geoknoesis.kastor.rdf.vocab.RDFS
import org.junit.jupiter.api.Test
import kotlin.test.*

internal fun ex(name: String): Iri = Iri("https://example.org/$name")
internal fun t(s: RdfResource, p: Iri, o: RdfTerm): RdfTriple = RdfTriple(s, p, o)
internal fun g(vararg triples: RdfTriple): RdfGraph = HolonGraph(triples.asList())
internal fun registrations(vararg holons: RdfResource): List<RdfTriple> = holons.map { t(it, RDF.type, RDFH.Holon) }
internal fun member(reifier: RdfResource, holon: RdfResource, triple: RdfTriple): List<RdfTriple> =
  listOf(triple, t(reifier, RDF.reifies, TripleTerm(triple)), t(reifier, RDFH.inHolon, holon))
internal fun error(condition: HolonError, block: () -> Unit) {
  assertEquals(condition, assertFailsWith<HolonException>(block = block).condition)
}

class HolonContextTest {
  private val a = ex("A")
  private val b = ex("B")
  private val c = ex("C")
  private val value = ex("value")

  @Test fun `direct aggregate and visible obey all import boundaries`() {
    val m = HolonGraph(registrations(a, b, c, ex("Parent"), ex("Imported"), ex("Ignored")) + listOf(
      t(a, RDFH.parentHolon, ex("Parent")), t(b, RDFH.parentHolon, a), t(c, RDFH.parentHolon, b),
      t(a, RDFH.imports, ex("Imported")), t(b, RDFH.imports, ex("Ignored")),
      t(ex("Imported"), RDFH.imports, ex("Ignored")), t(ex("Parent"), RDFH.imports, ex("Ignored")),
    ))
    val names = listOf(a, b, c, ex("Parent"), ex("Imported"), ex("Ignored"))
    val source = HolonContext.of(m, names.associateWith { g(t(it, value, it)) }, sourceId = "test-source", filtered = true)
    fun selected(scope: HolonScope) = source.resolve(a, scope).graph.getTriples().map { it.subject }.toSet()
    assertEquals(setOf(a), selected(HolonScope.DIRECT))
    assertEquals(setOf(a, b, c), selected(HolonScope.AGGREGATED))
    assertEquals(setOf(a, b, c, ex("Imported")), selected(HolonScope.VISIBLE))
    val resolution = source.resolve(a, HolonScope.VISIBLE)
    assertEquals("test-source", resolution.sourceId)
    assertEquals(source.snapshotId, resolution.snapshotId)
    assertEquals(HolonProfile.DATASET, resolution.profile)
    assertSame(source.metadata, resolution.metadata)
    assertTrue(resolution.filtered)
  }

  @Test fun `registry is explicit and distinguishes known empty from unknown`() {
    val m = g(t(a, RDF.type, RDFH.CompleteHolon), t(b, RDFH.contentGraph, ex("Empty")),
      t(c, RDFH.parentHolon, a), t(ex("Type"), RDFS.subClassOf, RDFH.Holon), t(ex("Inferred"), RDF.type, ex("Type")))
    val source = HolonContext.of(m, mapOf(ex("UnregisteredGraph") to g()))
    assertEquals(setOf(a, b), source.registry)
    assertEquals(0, source.resolve(a).graph.size())
    assertEquals(0, source.resolve(b).graph.size())
    for (unknown in listOf(c, ex("Inferred"), ex("Empty"), ex("UnregisteredGraph"))) {
      error(HolonError.UNKNOWN_HOLON) { source.resolve(unknown) }
    }
  }

  @Test fun `all dataset mappings are validated even on unrelated holons`() {
    for ((rows, condition) in listOf(
      listOf(t(b, RDFH.contentGraph, ex("One")), t(b, RDFH.contentGraph, ex("Two"))) to HolonError.CONFLICTING_MAPPING,
      listOf(t(b, RDFH.contentGraph, string("not a name"))) to HolonError.INVALID_GRAPH_NAME,
      listOf(t(BlankNode("unmapped"), RDF.type, RDFH.Holon)) to HolonError.UNMAPPED_BLANK_NODE_HOLON,
    )) {
      val source = HolonContext.of(HolonGraph(registrations(a) + rows))
      error(condition) { source.resolve(a) }
    }
  }

  @Test fun `shared mapping gives distinct attributed graphs with equal content`() {
    val source = HolonContext.of(g(t(a, RDFH.contentGraph, c), t(b, RDFH.contentGraph, c), t(b, RDFH.parentHolon, a)),
      mapOf(c to g(t(a, value, string("one")))))
    val result = source.resolve(a, HolonScope.AGGREGATED)
    assertEquals(setOf(a, b), result.attributedGraphs.keys)
    assertEquals(1, result.graph.size())
    assertEquals(result.attributedGraphs.getValue(a).getTriples(), result.attributedGraphs.getValue(b).getTriples())
  }

  @Test fun `blank-node holons and graph names survive attribution`() {
    val holon = BlankNode("holon")
    val name = BlankNode("graph")
    val shared = BlankNode("shared")
    val triple = t(shared, value, TripleTerm(t(shared, value, string("x"))))
    val source = HolonContext.of(g(t(holon, RDFH.contentGraph, name)), mapOf(name to g(triple)))
    val result = source.resolve(holon)
    assertEquals(setOf(holon), result.attributedGraphs.keys)
    assertEquals(listOf(triple), result.graph.getTriples())
  }

  @Test fun `direct and global ignore invalid hierarchy but composed scopes reject it globally`() {
    for ((relations, condition) in listOf(
      listOf(t(b, RDFH.parentHolon, b)) to HolonError.PARENT_CYCLE,
      listOf(t(b, RDFH.parentHolon, a), t(b, RDFH.parentHolon, c)) to HolonError.MULTIPLE_PARENTS,
      listOf(t(ex("Missing"), RDFH.parentHolon, b)) to HolonError.UNREGISTERED_PARENT_ENDPOINT,
    )) {
      val source = HolonContext.of(HolonGraph(registrations(a, b, c) + relations))
      assertEquals(0, source.resolve(a).graph.size())
      assertTrue(source.resolve(a, HolonScope.GLOBAL).graph.size() > 0)
      for (scope in listOf(HolonScope.AGGREGATED, HolonScope.VISIBLE)) error(condition) { source.resolve(a, scope) }
    }
  }

  @Test fun `imports are single hop permit cycles and only selected root is checked`() {
    val m = HolonGraph(registrations(a, b) + listOf(t(a, RDFH.imports, b), t(b, RDFH.imports, a), t(b, RDFH.imports, ex("Missing"))))
    val source = HolonContext.of(m)
    assertEquals(setOf(a, b), source.resolve(a, HolonScope.VISIBLE).attributedGraphs.keys)
    source.resolve(b)
    source.resolve(b, HolonScope.AGGREGATED)
    error(HolonError.UNRESOLVED_IMPORT) { source.resolve(b, HolonScope.VISIBLE) }
    val self = HolonContext.of(g(t(a, RDF.type, RDFH.Holon), t(a, RDFH.imports, a)))
    assertEquals(setOf(a), self.resolve(a, HolonScope.VISIBLE).attributedGraphs.keys)
  }

  @Test fun `content-only control statements never become declarations`() {
    val triple = t(b, RDFH.parentHolon, a)
    val source = HolonContext.of(HolonGraph(registrations(a, b)), mapOf(b to g(triple)))
    assertEquals(0, source.resolve(a, HolonScope.AGGREGATED).graph.size())
    assertEquals(listOf(triple), source.resolve(b).graph.getTriples())
  }

  @Test fun `dataset membership-looking data remains inactive unless mixed is declared`() {
    val base = t(a, value, string("reified"))
    val m = HolonGraph(registrations(a) + member(ex("r"), a, base))
    val graph = g(t(a, value, string("named")))
    val dataset = HolonContext.of(m, mapOf(a to graph))
    assertEquals(1, dataset.resolve(a).graph.size())
    val mixed = HolonContext.of(m, mapOf(a to graph), profile = HolonProfile.MIXED)
    assertEquals(2, mixed.resolve(a).graph.size())
  }

  @Test fun `mixed membership and base assertions are read across all source graphs`() {
    val base = t(a, value, string("elsewhere"))
    val member = member(ex("r"), a, base).drop(1)
    val source = HolonContext.of(HolonGraph(registrations(a)), mapOf(ex("records") to HolonGraph(member), ex("base") to g(base)),
      profile = HolonProfile.MIXED)
    assertEquals(listOf(base), source.resolve(a).graph.getTriples())
  }

  @Test fun `reifier profile validates all memberships with named error conditions`() {
    val base = t(a, value, string("base"))
    val membership = t(ex("r"), RDFH.inHolon, a)
    for ((rows, condition) in listOf(
      listOf(t(ex("r"), RDFH.inHolon, b)) to HolonError.UNREGISTERED_MEMBERSHIP_HOLON,
      listOf(membership) to HolonError.MISSING_REIFICATION,
      listOf(membership, t(ex("r"), RDF.reifies, string("not a triple"))) to HolonError.NON_TRIPLE_TERM_REIFICATION,
      listOf(membership, t(ex("r"), RDF.reifies, TripleTerm(base))) to HolonError.MISSING_BASE_ASSERTION,
    )) {
      val source = HolonContext.fromGraph(HolonGraph(rows), HolonGraph(registrations(a)))
      error(condition) { source.resolve(a) }
    }
  }

  @Test fun `reification permits multiple values holons and repeated occurrences`() {
    val base = t(a, value, lang("bonjour", "fr", Direction.LTR))
    val other = t(b, value, TripleTerm(base))
    val rows = member(ex("r"), a, base) + member(ex("otherOccurrence"), a, base) +
      listOf(other, t(ex("r"), RDF.reifies, TripleTerm(other)), t(ex("r"), RDFH.inHolon, b))
    val source = HolonContext.fromGraph(HolonGraph(rows), HolonGraph(registrations(a, b)))
    assertEquals(setOf(base, other), source.resolve(a).graph.getTriples().toSet())
    assertEquals(setOf(base, other), source.resolve(b).graph.getTriples().toSet())
  }

  @Test fun `base may be asserted only in M and reifier mappings select no content`() {
    val base = t(a, value, string("M only"))
    val m = HolonGraph(registrations(a) + base + t(a, RDFH.contentGraph, string("ignored")))
    val source = HolonContext.fromGraph(HolonGraph(member(ex("r"), a, base).drop(1)), m)
    assertEquals(listOf(base), source.resolve(a).graph.getTriples())
    assertTrue(source.assertedGraph.hasTriple(base))
  }

  @Test fun `separately supplied metadata cannot identify source blank nodes by matching labels`() {
    val h = BlankNode("h")
    val base = t(a, value, string("base"))
    val source = HolonGraph(member(ex("r"), h, base))
    val metadata = HolonGraph(registrations(h))
    val separate = HolonContext.fromGraph(source, metadata)
    val separateH = separate.registry.single()
    assertEquals(h, separateH) // The caller's M/holon identity is retained; source nodes are renamed.
    error(HolonError.UNREGISTERED_MEMBERSHIP_HOLON) { separate.resolve(separateH) }
    assertEquals(1, HolonContext.fromGraph(source, metadata, metadataSharesSourceScope = true).resolve(h).graph.size())
  }

  @Test fun `the source graph itself may explicitly be M`() {
    val h = BlankNode("h")
    val base = t(a, value, string("base"))
    val graph = HolonGraph(registrations(h) + member(ex("r"), h, base))
    assertEquals(1, HolonContext.fromGraph(graph, graph).resolve(h).graph.size())
  }

  @Test fun `snapshots are stable after repository edits and do not own the source`() {
    Rdf.repository { providerId = "memory" }.use { repository ->
      repository.editDefaultGraph().addTriples(registrations(a))
      repository.editGraph(a).addTriple(t(a, value, string("old")))
      val snapshot = HolonContext.fromDataset(repository)
      repository.editGraph(a).clear()
      assertEquals(1, snapshot.resolve(a).graph.size())
      assertFalse(repository.isClosed())
      assertEquals(0, HolonContext.fromDataset(repository).resolve(a).graph.size())
    }
  }

  @Test fun `global contains M default and unselected graphs without filing them`() {
    val m = HolonGraph(registrations(a))
    val unfiled = t(b, value, string("unfiled"))
    val source = HolonContext.of(g(unfiled), mapOf(c to g(t(c, value, c))), m)
    assertEquals(0, source.resolve(a).graph.size())
    assertEquals(3, source.resolve(a, HolonScope.GLOBAL).graph.size())
  }

  @Test fun `empty attribution and collections are immutable`() {
    val source = HolonContext.of(HolonGraph(registrations(a)))
    val result = source.resolve(a)
    assertEquals(setOf(a), result.attributedGraphs.keys)
    assertEquals(0, result.attributedGraphs.getValue(a).size())
    assertFailsWith<UnsupportedOperationException> { (source.registry as MutableSet).clear() }
    assertFailsWith<UnsupportedOperationException> { (result.attributedGraphs as MutableMap).clear() }
    assertFalse(result.graph is MutableRdfGraph)
  }

  @Test fun `reifier source cannot silently contain named graphs`() {
    error(HolonError.UNSUPPORTED_CAPABILITY) { HolonContext.of(g(), mapOf(a to g()), profile = HolonProfile.REIFIER) }
  }

  @Test fun `low-level reifier input also requires an explicit M`() {
    error(HolonError.UNSUPPORTED_CAPABILITY) { HolonContext.of(HolonGraph(registrations(a)), profile = HolonProfile.REIFIER) }
  }

  @Test fun `M from a named graph of the source repository shares its blank-node scope`() {
    val h = BlankNode("h")
    val base = t(a, value, string("base"))
    Rdf.repository { providerId = "memory" }.use { repository ->
      repository.editDefaultGraph().addTriples(member(ex("r"), h, base))
      repository.editGraph(ex("m")).addTriples(registrations(h) + t(h, RDFH.contentGraph, ex("content")))
      val context = HolonContext.fromDataset(repository, repository.getGraph(ex("m")), mixed = true)
      assertEquals(listOf(base), context.resolve(h).graph.getTriples())
    }
  }

  @Test fun `a fresh wrapper of the default graph is still the shared default scope`() {
    val h = BlankNode("h")
    val base = t(a, value, string("base"))
    Rdf.repository { providerId = "memory" }.use { repository ->
      repository.editDefaultGraph().addTriples(registrations(h) + t(h, RDFH.contentGraph, ex("content")) + member(ex("r"), h, base))
      val wrapper = GraphRef(repository.defaultGraph, repository)
      assertNotSame(repository.defaultGraph, wrapper)
      val context = HolonContext.fromDataset(repository, wrapper, mixed = true)
      assertEquals(listOf(base), context.resolve(h).graph.getTriples())
      assertEquals(repository.defaultGraph.size(), context.assertedGraph.size())
    }
  }

  @Test fun `reifier M from the source repository keeps every source blank node`() {
    val h = BlankNode("h")
    val reifier = BlankNode("r")
    val base = t(a, value, string("base"))
    Rdf.repository { providerId = "memory" }.use { repository ->
      repository.editDefaultGraph().addTriples(member(reifier, h, base))
      repository.editGraph(ex("m")).addTriples(registrations(h))
      val context = HolonContext.fromGraph(repository.defaultGraph, repository.getGraph(ex("m")))
      assertEquals(listOf(base), context.resolve(h).graph.getTriples())
      assertTrue(context.defaultGraph.hasTriple(t(reifier, RDFH.inHolon, h)))
    }
  }

  @Test fun `independent M retains its holon identity and source blank-node sharing stays intact`() {
    val h = BlankNode("same-label")
    Rdf.repository { providerId = "memory" }.use { repository ->
      repository.editGraph(a).addTriple(t(h, value, TripleTerm(t(h, value, b))))
      repository.editGraph(b).addTriple(t(h, value, string("shared")))
      val metadata = g(t(h, RDFH.contentGraph, a))
      val context = HolonContext.fromDataset(repository, metadata)
      assertEquals(setOf(h), context.registry)
      val triple = context.resolve(h).graph.getTriples().single()
      assertNotEquals(h, triple.subject)
      assertEquals(triple.subject, (triple.obj as TripleTerm).triple.subject)
      assertEquals(triple.subject, context.namedGraphs.getValue(b).getTriples().single().subject)
    }
  }
}
