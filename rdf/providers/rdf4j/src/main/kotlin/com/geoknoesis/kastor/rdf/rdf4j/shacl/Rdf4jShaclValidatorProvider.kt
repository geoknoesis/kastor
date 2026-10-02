package com.geoknoesis.kastor.rdf.rdf4j.shacl

import com.geoknoesis.kastor.rdf.shacl.PerformanceProfile
import com.geoknoesis.kastor.rdf.shacl.ShaclValidator
import com.geoknoesis.kastor.rdf.shacl.ShaclValidatorProvider
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationProfile
import com.geoknoesis.kastor.rdf.shacl.ValidatorCapabilities

/**
 * SPI provider for Eclipse RDF4J `ShaclSail` (`getType()` = `rdf4j`).
 *
 * Requires this module (`:rdf:rdf4j`) on the classpath together with `:rdf:shacl-validation`.
 *
 * **What the capabilities mean.** RDF4J 5.3 `ShaclSail` implements SHACL Core except `sh:xone`, the paths
 * `sh:zeroOrMorePath` / `sh:oneOrMorePath` / `sh:zeroOrOnePath` and `sh:qualifiedValueShapesDisjoint`, and of
 * SHACL-SPARQL the `sh:sparql` constraints with `sh:select` (not SPARQL-based constraint components, not `sh:ask`).
 * `supportsShaclCore` and `supportsShaclSparql` (with the [ValidationProfile.SHACL_CORE] and
 * [ValidationProfile.SHACL_SPARQL] profiles) are claimed on these terms, as the native engine claims SHACL-SPARQL
 * without constraint components: a shapes graph that uses one of the missing features is **never validated as if the
 * feature were absent**. The validator detects it and fails, or skips it with a warning, as
 * `ValidationConfig.unsupportedFeatures` says. Parallel and streaming validation are not offered
 * (`ValidationConfig.parallelValidation` / `streamingMode` are rejected), and triple terms in shape parameters are
 * not supported.
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
          ValidationProfile.SHACL_SPARQL,
          ValidationProfile.PERMISSIVE,
          ValidationProfile.STRICT,
      )

  override fun isSupported(profile: ValidationProfile): Boolean = profile in getSupportedProfiles()
}
