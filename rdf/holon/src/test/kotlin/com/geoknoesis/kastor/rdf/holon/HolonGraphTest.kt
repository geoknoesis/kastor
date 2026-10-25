package com.geoknoesis.kastor.rdf.holon

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** [HolonGraph.find] reads its indexes, and returns exactly what a scan of the graph returns, in graph order. */
class HolonGraphTest {
  @Test fun `indexed find agrees with a scan for every pattern`() {
    val subjects = listOf(ex("s1"), ex("s2"), BlankNode("b"))
    val predicates = listOf(ex("p1"), ex("p2"))
    val objects = listOf(ex("o1"), string("o2"), BlankNode("b"))
    val triples = subjects.flatMap { s -> predicates.flatMap { p -> objects.map { o -> t(s, p, o) } } }.shuffled(kotlin.random.Random(7))
    val graph = HolonGraph(triples.drop(3))
    val scan = object : RdfGraph {
      override fun hasTriple(triple: RdfTriple) = graph.hasTriple(triple)
      override fun getTriples() = graph.getTriples()
      override fun size() = graph.size()
    }
    for (s in subjects + listOf(null, ex("absent"))) for (p in predicates + listOf(null, ex("absent"))) for (o in objects + listOf(null)) {
      assertEquals(scan.find(s, p, o), graph.find(s, p, o), "pattern ($s, $p, $o)")
    }
  }
}
