package com.geoknoesis.kastor.rdf.rdf4j.shacl

import com.geoknoesis.kastor.rdf.shacl.PerformanceProfile
import com.geoknoesis.kastor.rdf.shacl.ShaclValidator
import com.geoknoesis.kastor.rdf.shacl.ShaclValidatorProvider
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationProfile
import com.geoknoesis.kastor.rdf.shacl.ValidatorCapabilities

/**
 * SPI provider for Eclipse RDF4J [ShaclSail] (`getType()` = `rdf4j`).
 *
 * Requires this module (`:rdf:rdf4j`) on the classpath together with `:rdf:shacl-validation`.
 */
class Rdf4jShaclValidatorProvider : ShaclValidatorProvider {

  override fun priority(): Int = 45

  override fun getType(): String = "rdf4j"

  override val name: String = "Eclipse RDF4J SHACL (ShaclSail)"

  override val version: String = "5.3"

  override fun createValidator(config: ValidationConfig): ShaclValidator = Rdf4jShaclValidator(config)

  override fun getCapabilities(): ValidatorCapabilities =
      ValidatorCapabilities(
          supportsShaclCore = true,
          supportsShaclSparql = true,
          supportsShaclJs = false,
          supportsShaclPy = false,
          supportsShaclDash = false,
          supportsCustomConstraints = false,
          supportsParallelValidation = false,
          supportsStreamingValidation = false,
          supportsIncrementalValidation = false,
          supportsRdf12TripleTermsInData = false,
          supportsRdf12TripleTermsInShapeParameters = false,
          maxGraphSize = Long.MAX_VALUE,
          performanceProfile = PerformanceProfile.MEDIUM,
      )

  override fun getSupportedProfiles(): List<ValidationProfile> =
      listOf(
          ValidationProfile.SHACL_CORE,
          ValidationProfile.PERMISSIVE,
          ValidationProfile.STRICT,
      )

  override fun isSupported(profile: ValidationProfile): Boolean = profile in getSupportedProfiles()
}
