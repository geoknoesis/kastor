package com.geoknoesis.kastor.rdf.rdf4j.shacl

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationProfile
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Rdf4jShaclValidatorProviderTest {

  @Test
  fun `minCount violation detected with rdf4j provider`() {
    val url =
        Rdf4jShaclValidatorProviderTest::class.java.getResource("/shacl/rdf4j-mincount-violation.ttl")
            ?: error("missing test resource /shacl/rdf4j-mincount-violation.ttl")
    val data = url.openStream().use { Rdf.parseFromInputStream(it, "TURTLE") }
    val v =
        ShaclValidation.validator(
            ValidationConfig(
                profile = ValidationProfile.SHACL_CORE,
                providerId = "rdf4j",
                parallelValidation = false,
            ),
        )
    val report = v.validate(data, data)
    assertFalse(report.isValid)
    assertTrue(report.violations.isNotEmpty())
  }

  @Test
  fun `minCount satisfied reports valid`() {
    val url =
        Rdf4jShaclValidatorProviderTest::class.java.getResource("/shacl/rdf4j-mincount-valid.ttl")
            ?: error("missing test resource /shacl/rdf4j-mincount-valid.ttl")
    val data = url.openStream().use { Rdf.parseFromInputStream(it, "TURTLE") }
    val v =
        ShaclValidation.validator(
            ValidationConfig(
                profile = ValidationProfile.SHACL_CORE,
                providerId = "rdf4j",
                parallelValidation = false,
            ),
        )
    val report = v.validate(data, data)
    assertTrue(report.isValid)
    assertTrue(report.violations.isEmpty())
  }
}
