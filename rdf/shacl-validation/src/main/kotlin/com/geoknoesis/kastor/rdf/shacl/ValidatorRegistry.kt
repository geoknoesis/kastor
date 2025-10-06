package com.geoknoesis.kastor.rdf.shacl

import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for discovering and managing SHACL validator providers.
 */
object ValidatorRegistry {
    
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
        val provider = findProviderForProfile(config.profile)
            ?: throw IllegalArgumentException("No validator provider found for profile: ${config.profile}")
        return provider.createValidator(config)
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
            serviceLoader.forEach { provider ->
                providers[provider.getType()] = provider
                discoveredProviders.add(provider)
            }
        } catch (e: Exception) {
            // Service loader might not find providers during development
            // This is expected and not a fatal error
        }
        
        return discoveredProviders.toList()
    }
    
    /**
     * Get all registered providers.
     */
    fun getProviders(): List<ShaclValidatorProvider> {
        return providers.values.toList()
    }
    
    /**
     * Get supported validation profiles.
     */
    fun getSupportedProfiles(): List<ValidationProfile> {
        return providers.values
            .flatMap { it.getSupportedProfiles() }
            .distinct()
            .sorted()
    }
    
    /**
     * Check if a validation profile is supported.
     */
    fun isSupported(profile: ValidationProfile): Boolean {
        return providers.values.any { it.isSupported(profile) }
    }
    
    /**
     * Get providers that support a specific profile.
     */
    fun getProvidersForProfile(profile: ValidationProfile): List<ShaclValidatorProvider> {
        return providers.values.filter { it.isSupported(profile) }
    }
    
    /**
     * Get the best provider for a profile based on capabilities.
     */
    fun getBestProviderForProfile(profile: ValidationProfile): ShaclValidatorProvider? {
        val supportingProviders = getProvidersForProfile(profile)
        if (supportingProviders.isEmpty()) return null
        
        // Prefer providers with higher performance profiles for now
        return supportingProviders.maxByOrNull { provider ->
            val capabilities = provider.getCapabilities()
            when (capabilities.performanceProfile) {
                PerformanceProfile.FAST -> 3
                PerformanceProfile.MEDIUM -> 2
                PerformanceProfile.THOROUGH -> 1
            }
        }
    }
    
    /**
     * Find a provider that supports the given profile.
     */
    private fun findProviderForProfile(profile: ValidationProfile): ShaclValidatorProvider? {
        return getBestProviderForProfile(profile)
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
}

/**
 * Registry statistics.
 */
data class RegistryStatistics(
    val totalProviders: Int,
    val supportedProfiles: List<ValidationProfile>,
    val unsupportedProfiles: List<ValidationProfile>,
    val providersByProfile: Map<ValidationProfile, List<String>>
)
