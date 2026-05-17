package com.geoknoesis.kastor.rdf.rdf4j.shacl

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationProfile
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Rdf4jShaclValidatorBridgeLimitsTest {

  @Test
  fun combinedTripleCap_throwsBeforeRdf4jMaterializesLargeGraph() {
    val url =
        Rdf4jShaclValidatorBridgeLimitsTest::class.java.getResource("/shacl/rdf4j-mincount-violation.ttl")
            ?: error("missing resource")
    val graph = url.openStream().use { Rdf.parseFromInputStream(it, "TURTLE") }
    val v =
        ShaclValidation.validator(
            ValidationConfig(
                profile = ValidationProfile.SHACL_CORE,
                providerId = "rdf4j",
                parallelValidation = false,
                maxCombinedGraphTriples = 1L,
            ),
        )
    val ex = assertThrows(ShaclValidationException::class.java) { v.validate(graph, graph) }
    assertTrue(ex.message!!.contains("maxCombinedGraphTriples"), ex.message)
  }

  @Test
  fun violationsTruncated_whenReportExceedsMaxViolations() {
    val url =
        Rdf4jShaclValidatorBridgeLimitsTest::class.java.getResource("/shacl/rdf4j-mincount-two-focus.ttl")
            ?: error("missing resource")
    val graph = url.openStream().use { Rdf.parseFromInputStream(it, "TURTLE") }
    val v =
        ShaclValidation.validator(
            ValidationConfig(
                profile = ValidationProfile.SHACL_CORE,
                providerId = "rdf4j",
                parallelValidation = false,
                maxViolations = 1,
            ),
        )
    val report = v.validate(graph, graph)
    assertTrue(report.violationsTruncated, "expected more than one violation in fixture")
    assertTrue(report.violations.size == 1)
  }
}
