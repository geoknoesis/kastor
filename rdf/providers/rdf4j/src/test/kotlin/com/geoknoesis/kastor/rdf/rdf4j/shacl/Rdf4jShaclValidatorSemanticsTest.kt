package com.geoknoesis.kastor.rdf.rdf4j.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Rdf4jShaclValidatorSemanticsTest {

  private fun validator(maxViolations: Int = 1000) =
      ShaclValidation.validator(
          ValidationConfig(
              profile = ValidationProfile.SHACL_CORE,
              providerId = "rdf4j",
              parallelValidation = false,
              maxViolations = maxViolations,
          ),
      )

  @Test
  fun `truncating violations never flips an invalid report to valid`() {
    val graph =
        Rdf4jShaclValidatorSemanticsTest::class.java.getResource("/shacl/rdf4j-mincount-two-focus.ttl")!!.openStream().use {
          Rdf.parseFromInputStream(it, "TURTLE")
        }
    val report = validator(maxViolations = 1).validate(graph, graph)
    assertTrue(report.violationsTruncated)
    assertFalse(report.isValid, "a truncated report of a non-conforming graph must stay invalid")
  }

  private val shapes =
      """
      @prefix sh: <http://www.w3.org/ns/shacl#> .
      @prefix ex: <http://example.org/> .
      ex:PersonShape a sh:NodeShape ;
        sh:targetClass ex:Person ;
        sh:property [ sh:path ex:address ; sh:minCount 1 ; sh:node ex:AddressShape ] .
      ex:AddressShape a sh:NodeShape ;
        sh:property [ sh:path ex:city ; sh:minCount 1 ] .
      """.trimIndent()

  private val data =
      """
      @prefix ex: <http://example.org/> .
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      ex:Student rdfs:subClassOf ex:Person .
      ex:alice a ex:Person ; ex:address [ ex:city "Paris" ] .
      ex:bob a ex:Student .
      """.trimIndent()

  @Test
  fun `validateResource sees nested blank-node values`() {
    val report =
        validator().validateResource(Rdf.parse(data, "TURTLE"), Rdf.parse(shapes, "TURTLE"), Iri("http://example.org/alice"))
    assertTrue(report.isValid, "alice's nested address has a city: ${report.violations}")
  }

  @Test
  fun `validateResource honours subclass targets in the data graph`() {
    val bob = Iri("http://example.org/bob")
    val report = validator().validateResource(Rdf.parse(data, "TURTLE"), Rdf.parse(shapes, "TURTLE"), bob)
    assertFalse(report.isValid, "bob is a Person via rdfs:subClassOf and has no address")
    assertTrue(report.violations.isNotEmpty())
    assertTrue(report.violations.all { it.focusNode == bob })
    assertEquals(false, validator().validate(Rdf.parse(data, "TURTLE"), Rdf.parse(shapes, "TURTLE")).isValid)
  }
}
