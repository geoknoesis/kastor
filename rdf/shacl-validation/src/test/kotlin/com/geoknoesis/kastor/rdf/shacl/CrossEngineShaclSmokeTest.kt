package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.shacl.conformance.JenaShacl12Conformance
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ensures Kastor native, RDF4J (`:rdf:rdf4j` on the test classpath), and Jena agree on **`sh:conforms`**
 * for small hand-written graphs (no relative `<>` IRIs — RDF4J Turtle parsing in tests).
 */
class CrossEngineShaclSmokeTest {

  @Test
  fun minCountViolation_allEnginesReportInvalid() {
    assertInvalid("/cross-engine/mincount-violation.ttl")
  }

  @Test
  fun minCountValid_allEnginesReportValid() {
    assertValid("/cross-engine/mincount-valid.ttl")
  }

  @Test
  fun targetClassMaxCountViolation_allEnginesReportInvalid() {
    assertInvalid("/cross-engine/targetClass-violation.ttl")
  }

  @Test
  fun targetClassMaxCountValid_allEnginesReportValid() {
    assertValid("/cross-engine/targetClass-valid.ttl")
  }

  private fun assertInvalid(resource: String) {
    val path = resourcePath(resource)
    val graph = Rdf.parseFromFile(path.toString(), "TURTLE")

    val jenaExpected = JenaShacl12Conformance.validateToExpectedReport(path, path)
    assertFalse(jenaExpected.conforms, "jena baseline $resource")

    val kastor =
        ShaclValidation.validator(
            ValidationConfig(
                profile = ValidationProfile.SHACL_CORE,
                providerId = "kastor",
                parallelValidation = false,
            ),
        )
    assertFalse(kastor.validate(graph, graph).isValid, "kastor $resource")

    val rdf4j =
        ShaclValidation.validator(
            ValidationConfig(
                profile = ValidationProfile.SHACL_CORE,
                providerId = "rdf4j",
                parallelValidation = false,
            ),
        )
    assertFalse(rdf4j.validate(graph, graph).isValid, "rdf4j $resource")
  }

  private fun assertValid(resource: String) {
    val path = resourcePath(resource)
    val graph = Rdf.parseFromFile(path.toString(), "TURTLE")

    val jenaExpected = JenaShacl12Conformance.validateToExpectedReport(path, path)
    assertTrue(jenaExpected.conforms, "jena baseline $resource")

    val kastor =
        ShaclValidation.validator(
            ValidationConfig(
                profile = ValidationProfile.SHACL_CORE,
                providerId = "kastor",
                parallelValidation = false,
            ),
        )
    assertTrue(kastor.validate(graph, graph).isValid, "kastor $resource")

    val rdf4j =
        ShaclValidation.validator(
            ValidationConfig(
                profile = ValidationProfile.SHACL_CORE,
                providerId = "rdf4j",
                parallelValidation = false,
            ),
        )
    assertTrue(rdf4j.validate(graph, graph).isValid, "rdf4j $resource")
  }

  private fun resourcePath(resource: String): Path {
    val url =
        CrossEngineShaclSmokeTest::class.java.getResource(resource)
            ?: error("Missing test resource: $resource")
    return Path.of(url.toURI())
  }
}
