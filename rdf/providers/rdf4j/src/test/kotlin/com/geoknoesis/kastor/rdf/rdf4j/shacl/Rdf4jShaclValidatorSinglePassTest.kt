package com.geoknoesis.kastor.rdf.rdf4j.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A validation call materialises the data and shapes graphs once each, not once per statistic. */
class Rdf4jShaclValidatorSinglePassTest {

  private class CountingGraph(private val delegate: RdfGraph) : RdfGraph by delegate {
    var materialisations = 0

    override fun getTriples(): List<RdfTriple> {
      materialisations++
      return delegate.getTriples()
    }

    override fun find(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): List<RdfTriple> {
      materialisations++
      return delegate.find(subject, predicate, obj)
    }
  }

  private fun load(resource: String): RdfGraph =
      (Rdf4jShaclValidatorSinglePassTest::class.java.getResource(resource) ?: error("missing $resource"))
          .openStream().use { Rdf.parseFromInputStream(it, "TURTLE") }

  @Test
  fun `data and shapes graphs are each materialised once per validation`() {
    for (resource in listOf("/shacl/rdf4j-mincount-violation.ttl", "/shacl/rdf4j-mincount-valid.ttl")) {
      val parsed = load(resource)
      val data = CountingGraph(parsed)
      val shapes = CountingGraph(parsed)
      val report = Rdf4jShaclValidator(ValidationConfig()).validate(data, shapes)
      assertEquals(1, data.materialisations, "data graph, $resource")
      assertEquals(1, shapes.materialisations, "shapes graph, $resource")
      assertTrue(report.statistics.totalResources > 0, resource)
    }
  }
}
