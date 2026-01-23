package com.geoknoesis.kastor.gen.processor.model

import com.geoknoesis.kastor.gen.annotations.ValidationMode

/**
 * DSL builder for DslGenerationOptions.
 * Provides a fluent API for configuring generation options.
 * 
 * @example
 * ```
 * val options = dslOptions {
 *     validation {
 *         enabled = true
 *         mode = ValidationMode.EMBEDDED
 *     }
 *     output {
 *         supportLanguageTags = true
 *         defaultLanguage = "en"
 *     }
 * }
 * ```
 */
@DslMarker
annotation class DslOptionsMarker

/**
 * Creates DslGenerationOptions using a fluent DSL builder.
 *
 * This function provides a type-safe, fluent API for configuring DSL generation options.
 * The builder uses nested scopes for validation, naming, and output configuration.
 *
 * @param block DSL builder block for configuring options
 * @return Configured DslGenerationOptions instance
 *
 * @sample com.example.ConfigureDslOptions
 *
 * @see DslGenerationOptions
 * @see DslGenerationOptionsBuilder
 */
public fun dslOptions(block: DslGenerationOptionsBuilder.() -> Unit): DslGenerationOptions {
    return DslGenerationOptionsBuilder().apply(block).build()
}

/**
 * Builder for DslGenerationOptions.
 *
 * This builder provides a fluent API for configuring all aspects of DSL generation.
 * Use the nested builder methods (`validation`, `naming`, `output`) to configure
 * specific aspects of generation.
 *
 * @see dslOptions
 */
@DslOptionsMarker
public class DslGenerationOptionsBuilder {
    private var validationConfig: DslGenerationOptions.ValidationConfig = DslGenerationOptions.ValidationConfig()
    private var namingConfig: DslGenerationOptions.NamingConfig = DslGenerationOptions.NamingConfig()
    private var outputConfig: DslGenerationOptions.OutputConfig = DslGenerationOptions.OutputConfig()
    
    /**
     * Configures validation settings for generated DSL.
     *
     * @param block Builder block for validation configuration
     * @see ValidationConfigBuilder
     */
    fun validation(block: ValidationConfigBuilder.() -> Unit) {
        validationConfig = ValidationConfigBuilder().apply(block).build()
    }
    
    /**
     * Configures naming settings for generated code.
     *
     * @param block Builder block for naming configuration
     * @see NamingConfigBuilder
     */
    fun naming(block: NamingConfigBuilder.() -> Unit) {
        namingConfig = NamingConfigBuilder().apply(block).build()
    }
    
    /**
     * Configures output settings for generated code.
     *
     * @param block Builder block for output configuration
     * @see OutputConfigBuilder
     */
    fun output(block: OutputConfigBuilder.() -> Unit) {
        outputConfig = OutputConfigBuilder().apply(block).build()
    }
    
    fun build(): DslGenerationOptions {
        return DslGenerationOptions(
            validation = validationConfig,
            naming = namingConfig,
            output = outputConfig
        )
    }
}

/**
 * Builder for ValidationConfig.
 *
 * Configures validation behavior for generated DSL builders.
 *
 * @property enabled Whether validation is enabled
 * @property mode Validation mode (EMBEDDED, EXTERNAL, NONE)
 * @property strict Whether to use strict validation
 * @property validateOnBuild Whether to validate when building instances
 */
@DslOptionsMarker
public class ValidationConfigBuilder {
    var enabled: Boolean = true
    var mode: ValidationMode = ValidationMode.EMBEDDED
    var strict: Boolean = false
    var validateOnBuild: Boolean = true
    
    fun build(): DslGenerationOptions.ValidationConfig {
        return DslGenerationOptions.ValidationConfig(
            enabled = enabled,
            mode = mode,
            strict = strict,
            validateOnBuild = validateOnBuild
        )
    }
}

/**
 * Builder for NamingConfig.
 *
 * Configures naming conventions for generated code.
 *
 * @property strategy Naming strategy (CAMEL_CASE, SNAKE_CASE, PASCAL_CASE)
 * @property usePropertyNames Whether to use property names from SHACL shapes
 */
@DslOptionsMarker
public class NamingConfigBuilder {
    var strategy: NamingStrategy = NamingStrategy.CAMEL_CASE
    var usePropertyNames: Boolean = true
    
    fun build(): DslGenerationOptions.NamingConfig {
        return DslGenerationOptions.NamingConfig(
            strategy = strategy,
            usePropertyNames = usePropertyNames
        )
    }
}

/**
 * Builder for OutputConfig.
 *
 * Configures output formatting and features for generated code.
 *
 * @property includeComments Whether to include comments in generated code
 * @property includeKdoc Whether to include KDoc documentation
 * @property supportLanguageTags Whether to support language tags for string properties
 * @property defaultLanguage Default language tag to use (if null, no default)
 */
@DslOptionsMarker
public class OutputConfigBuilder {
    var includeComments: Boolean = true
    var includeKdoc: Boolean = true
    var supportLanguageTags: Boolean = true
    var defaultLanguage: String? = null
    
    fun build(): DslGenerationOptions.OutputConfig {
        return DslGenerationOptions.OutputConfig(
            includeComments = includeComments,
            includeKdoc = includeKdoc,
            supportLanguageTags = supportLanguageTags,
            defaultLanguage = defaultLanguage
        )
    }
}

