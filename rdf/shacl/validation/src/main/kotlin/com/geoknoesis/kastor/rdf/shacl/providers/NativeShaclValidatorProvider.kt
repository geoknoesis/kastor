package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.shacl.PerformanceProfile
import com.geoknoesis.kastor.rdf.shacl.ShaclValidator
import com.geoknoesis.kastor.rdf.shacl.ShaclValidatorProvider
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationProfile
import com.geoknoesis.kastor.rdf.shacl.ValidatorCapabilities

/**
 * SPI provider for the Kastor native SHACL 1.2 Core engine (`getType()` = `kastor`).
 */
class NativeShaclValidatorProvider : ShaclValidatorProvider {

    override fun priority(): Int = 10

    override fun getType(): String = "kastor"

    override val name: String = "Kastor Native SHACL Validator"

    override val version: String = "1.0.0"

    override fun createValidator(config: ValidationConfig): ShaclValidator = NativeShaclValidator(config)

    override fun getCapabilities(): ValidatorCapabilities =
        ValidatorCapabilities(
            supportsShaclCore = true,
            supportsShaclSparql = true,
            supportsShaclJs = false,
            supportsShaclPy = false,
            supportsShaclDash = false,
            supportsCustomConstraints = false,
            supportsParallelValidation = true,
            supportsStreamingValidation = false,
            supportsIncrementalValidation = false,
            supportsRdf12TripleTermsInData = true,
            supportsRdf12TripleTermsInShapeParameters = true,
            maxGraphSize = Long.MAX_VALUE,
            performanceProfile = PerformanceProfile.FAST,
        )

    override fun getSupportedProfiles(): List<ValidationProfile> =
        listOf(
            ValidationProfile.SHACL_CORE,
            ValidationProfile.PERMISSIVE,
            ValidationProfile.STRICT,
        )

    override fun isSupported(profile: ValidationProfile): Boolean = profile in getSupportedProfiles()
}
