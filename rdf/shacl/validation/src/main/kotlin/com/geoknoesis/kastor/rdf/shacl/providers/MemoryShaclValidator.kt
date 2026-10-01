package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ShaclValidator
import com.geoknoesis.kastor.rdf.shacl.ShaclValidatorProvider
import com.geoknoesis.kastor.rdf.shacl.ShapeCacheControl
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationProfile
import com.geoknoesis.kastor.rdf.shacl.ValidatorCapabilities

private const val MEMORY_DEPRECATION =
    "The \"memory\" SHACL provider is an alias of the Kastor native engine (provider id \"kastor\"); use " +
        "NativeShaclValidatorProvider / ValidationConfig(providerId = \"kastor\") or leave providerId unset."

/**
 * Legacy `memory` provider id, kept so that `ValidationConfig(providerId = "memory")` and existing service
 * registrations keep working. It is an **alias of the Kastor native engine**: validators, capabilities and supported
 * profiles are those of [NativeShaclValidatorProvider].
 *
 * Before, this provider was a separate stub that ignored targets (every subject was validated against every shape),
 * understood only `sh:minCount`, `sh:maxCount`, `sh:datatype` and `sh:class`, and reported any shape list as valid
 * while claiming SHACL Core support. Validation through it now has native semantics; in particular
 * `validate(graph, List<ShaclShape>)` and `validateConstraints` throw [UnsupportedOperationException] for non-empty
 * lists instead of returning a conforming report, and `parallelValidation` / `streamingMode` are rejected as the
 * native engine rejects them.
 *
 * The registry never prefers it over the native provider, and it is not a "bridge" for
 * [com.geoknoesis.kastor.rdf.shacl.EnginePreference.BRIDGE_FIRST].
 */
@Deprecated(MEMORY_DEPRECATION, ReplaceWith("NativeShaclValidatorProvider()"))
class MemoryShaclValidatorProvider : ShaclValidatorProvider {

    private val native = NativeShaclValidatorProvider()

    /** Always after the native provider (`kastor`, priority 10). */
    override fun priority(): Int = 100

    override fun getType(): String = "memory"

    override val name: String = "Memory SHACL Validator"

    override val version: String = "1.0.0"

    @Suppress("DEPRECATION")
    override fun createValidator(config: ValidationConfig): ShaclValidator = MemoryShaclValidator(config)

    /** The capabilities of the native engine this provider delegates to. */
    override fun getCapabilities(): ValidatorCapabilities = native.getCapabilities()

    override fun getSupportedProfiles(): List<ValidationProfile> = native.getSupportedProfiles()

    override fun isSupported(profile: ValidationProfile): Boolean = native.isSupported(profile)
}

/**
 * Legacy validator class of the `memory` provider: every call is delegated to the Kastor native engine (see
 * [MemoryShaclValidatorProvider] for what changed).
 */
@Deprecated(MEMORY_DEPRECATION)
class MemoryShaclValidator private constructor(private val native: NativeShaclValidator) :
    ShaclValidator by native, ShapeCacheControl by native {

    constructor(config: ValidationConfig) : this(NativeShaclValidator(config))
}

/**
 * Legacy validation exception of the former memory validator. Nothing throws it any more; catch
 * [ShaclValidationException] instead.
 */
@Deprecated("No longer thrown; catch ShaclValidationException.", ReplaceWith("ShaclValidationException"))
class ValidationException(message: String, cause: Throwable? = null) :
    ShaclValidationException(message, cause)
