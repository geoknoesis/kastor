package com.geoknoesis.kastor.rdf.shacl

import java.util.ServiceConfigurationError
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Central registry for discovering and managing SHACL validator providers.
 */
object ValidatorRegistry {

    private val log = Logger.getLogger(ValidatorRegistry::class.java.name)

    private val providers = ConcurrentHashMap<String, ShaclValidatorProvider>()

    init {
        discoverProviders()
    }

    /**
     * Register a validator provider.
     */
    fun register(provider: ShaclValidatorProvider) {
        providers[provider.getType()] = provider
    }

    /**
     * Create a validator with the given configuration.
     */
    fun createValidator(config: ValidationConfig): ShaclValidator {
        val matching = providers.values.filter { providerMatchesProfile(it, config.profile) }
        if (matching.isEmpty()) {
            throw UnsupportedProfileException(
                "No SHACL validator provider supports profile ${config.profile}. Registered provider ids: ${providers.keys.sorted()}"
            )
        }

        config.providerId?.let { id ->
            val p = providers[id] ?: throw ProviderNotFoundException(id)
            if (!providerMatchesProfile(p, config.profile)) {
                throw UnsupportedProfileException(
                    "Provider '$id' does not support profile ${config.profile}"
                )
            }
            logResolved(p, config)
            return p.createValidator(config)
        }

        val sorted = sortProviders(matching, config.enginePreference)
        val p = sorted.first()
        logResolved(p, config)
        return p.createValidator(config)
    }

    /**
     * Create a validator for a specific profile.
     */
    fun createValidator(profile: ValidationProfile): ShaclValidator {
        val config = when (profile) {
            ValidationProfile.SHACL_CORE -> ValidationConfig.shaclCore()
            ValidationProfile.SHACL_SPARQL -> ValidationConfig.shaclSparql()
            ValidationProfile.STRICT -> ValidationConfig.strict()
            ValidationProfile.PERMISSIVE -> ValidationConfig.default()
            else -> ValidationConfig.default()
        }
        return createValidator(config)
    }

    /**
     * Discover all available validator providers.
     */
    fun discoverProviders(): List<ShaclValidatorProvider> {
        val discoveredProviders = mutableListOf<ShaclValidatorProvider>()
        try {
            val serviceLoader = java.util.ServiceLoader.load(ShaclValidatorProvider::class.java)
            for (provider in serviceLoader) {
                try {
                    val id = provider.getType()
                    providers[id] = provider
                    discoveredProviders.add(provider)
                } catch (e: Exception) {
                    log.log(
                        Level.WARNING,
                        "SHACL validator provider ${provider.javaClass.name} failed to register (getType or init)",
                        e,
                    )
                }
            }
        } catch (e: ServiceConfigurationError) {
            log.log(Level.WARNING, "SHACL ServiceLoader configuration error (META-INF/services)", e)
        } catch (e: Exception) {
            log.log(Level.WARNING, "SHACL ServiceLoader discovery failed", e)
        }
        return discoveredProviders.toList()
    }

    /**
     * Get all registered providers.
     */
    fun getProviders(): List<ShaclValidatorProvider> = providers.values.toList()

    /**
     * Get supported validation profiles.
     */
    fun getSupportedProfiles(): List<ValidationProfile> =
        providers.values
            .flatMap { it.getSupportedProfiles() }
            .distinct()
            .sorted()

    /**
     * Check if a validation profile is supported.
     */
    fun isSupported(profile: ValidationProfile): Boolean =
        providers.values.any { providerMatchesProfile(it, profile) }

    /**
     * Get providers that support a specific profile.
     */
    fun getProvidersForProfile(profile: ValidationProfile): List<ShaclValidatorProvider> =
        providers.values.filter { providerMatchesProfile(it, profile) }

    /**
     * Get the best provider for a profile based on capabilities and stable ordering.
     */
    fun getBestProviderForProfile(profile: ValidationProfile): ShaclValidatorProvider? {
        val supportingProviders = getProvidersForProfile(profile)
        if (supportingProviders.isEmpty()) return null
        return sortProviders(supportingProviders, EnginePreference.AUTO).first()
    }

    /**
     * Clear all registered providers (mainly for testing).
     */
    fun clear() {
        providers.clear()
    }

    /**
     * Get registry statistics.
     */
    fun getRegistryStatistics(): RegistryStatistics {
        val allProfiles = ValidationProfile.values().toList()
        val supportedProfiles = getSupportedProfiles()
        val unsupportedProfiles = allProfiles - supportedProfiles.toSet()
        return RegistryStatistics(
            totalProviders = providers.size,
            supportedProfiles = supportedProfiles,
            unsupportedProfiles = unsupportedProfiles,
            providersByProfile = allProfiles.associateWith { profile ->
                getProvidersForProfile(profile).map { it.name }
            }
        )
    }

    private fun providerMatchesProfile(provider: ShaclValidatorProvider, profile: ValidationProfile): Boolean {
        if (!provider.isSupported(profile)) return false
        val cap = provider.getCapabilities()
        return when (profile) {
            ValidationProfile.SHACL_SPARQL -> cap.supportsShaclSparql
            ValidationProfile.SHACL_JS -> cap.supportsShaclJs
            ValidationProfile.SHACL_PY -> cap.supportsShaclPy
            ValidationProfile.SHACL_DASH -> cap.supportsShaclDash
            ValidationProfile.CUSTOM -> cap.supportsCustomConstraints
            ValidationProfile.COMPREHENSIVE ->
                cap.supportsShaclCore && cap.supportsShaclSparql && cap.supportsShaclJs && cap.supportsShaclPy
            else -> cap.supportsShaclCore
        }
    }

    private fun isKastorNative(p: ShaclValidatorProvider): Boolean = p.getType() == "kastor"

    private fun sortProviders(
        matching: Collection<ShaclValidatorProvider>,
        preference: EnginePreference,
    ): List<ShaclValidatorProvider> {
        val nativeFirst = preference == EnginePreference.NATIVE_FIRST || preference == EnginePreference.AUTO
        return matching.sortedWith(
            compareBy<ShaclValidatorProvider> {
                when {
                    nativeFirst && isKastorNative(it) -> 0
                    nativeFirst -> 1
                    !nativeFirst && isKastorNative(it) -> 1
                    else -> 0
                }
            }.thenBy { it.priority() }
                .thenBy { it.getType() },
        )
    }

    private fun logResolved(p: ShaclValidatorProvider, config: ValidationConfig) {
        log.info(
            "SHACL validator resolved: type=${p.getType()}, enginePreference=${config.enginePreference}, profile=${config.profile}",
        )
    }
}

data class RegistryStatistics(
    val totalProviders: Int,
    val supportedProfiles: List<ValidationProfile>,
    val unsupportedProfiles: List<ValidationProfile>,
    val providersByProfile: Map<ValidationProfile, List<String>>,
)
