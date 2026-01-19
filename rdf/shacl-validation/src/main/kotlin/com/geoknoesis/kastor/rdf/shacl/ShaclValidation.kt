package com.geoknoesis.kastor.rdf.shacl

/**
 * Factory object for accessing SHACL validation capabilities.
 * This is the main entry point for SHACL validation in Kastor.
 */
object ShaclValidation {
    
    /**
     * Create a validator with the given configuration.
     */
    fun validator(config: ValidationConfig): ShaclValidator {
        return ValidatorRegistry.createValidator(config)
    }
    
    /**
     * Create a validator for a specific validation profile.
     */
    fun validator(profile: ValidationProfile): ShaclValidator {
        return ValidatorRegistry.createValidator(profile)
    }
    
    /**
     * Create a default validator.
     */
    fun validator(): ShaclValidator {
        return ValidatorRegistry.createValidator(ValidationConfig.default())
    }
    
    /**
     * Create a strict validator.
     */
    fun strictValidator(): ShaclValidator {
        return ValidatorRegistry.createValidator(ValidationConfig.strict())
    }
    
    /**
     * Create a validator optimized for large graphs.
     */
    fun largeGraphValidator(): ShaclValidator {
        return ValidatorRegistry.createValidator(ValidationConfig.forLargeGraphs())
    }
    
    /**
     * Create a validator optimized for fast validation.
     */
    fun fastValidator(): ShaclValidator {
        return ValidatorRegistry.createValidator(ValidationConfig.forFastValidation())
    }
    
    /**
     * Create a validator for memory-constrained environments.
     */
    fun memoryConstrainedValidator(): ShaclValidator {
        return ValidatorRegistry.createValidator(ValidationConfig.forMemoryConstrained())
    }
    
    /**
     * Get all available validator providers.
     */
    fun validatorProviders(): List<ShaclValidatorProvider> {
        return ValidatorRegistry.getProviders()
    }
    
    /**
     * Get supported validation profiles.
     */
    fun supportedProfiles(): List<ValidationProfile> {
        return ValidatorRegistry.getSupportedProfiles()
    }
    
    /**
     * Check if a validation profile is supported.
     */
    fun isSupported(profile: ValidationProfile): Boolean {
        return ValidatorRegistry.isSupported(profile)
    }
    
    /**
     * Get the best validator provider for a profile.
     */
    fun getBestProvider(profile: ValidationProfile): ShaclValidatorProvider? {
        return ValidatorRegistry.getBestProviderForProfile(profile)
    }
    
    /**
     * Get registry statistics.
     */
    fun getRegistryStatistics(): RegistryStatistics {
        return ValidatorRegistry.getRegistryStatistics()
    }
}









