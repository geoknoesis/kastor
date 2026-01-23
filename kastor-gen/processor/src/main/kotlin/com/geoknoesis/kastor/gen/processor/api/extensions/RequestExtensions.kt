package com.geoknoesis.kastor.gen.processor.api.extensions

import com.geoknoesis.kastor.gen.processor.api.model.DslGenerationOptions
import com.geoknoesis.kastor.gen.processor.api.model.DslGenerationOptionsBuilder
import com.geoknoesis.kastor.gen.processor.api.model.InstanceDslRequest
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.dslOptions
import com.geoknoesis.kastor.gen.processor.internal.core.AnnotationParser
import com.geoknoesis.kastor.gen.processor.internal.core.OntologyFileReader
import com.google.devtools.ksp.processing.KSPLogger

/**
 * Extension functions for request objects and common operations.
 */

/**
 * Converts an InstanceDslGenerationRequest to an OntologyModel.
 */
internal fun AnnotationParser.InstanceDslGenerationRequest.toOntologyModel(reader: OntologyFileReader): OntologyModel {
    return reader.loadOntologyModel(shaclPath, contextPath)
}

/**
 * Builder for InstanceDslRequest using fluent DSL syntax.
 *
 * This function provides a type-safe, fluent API for creating instance DSL generation requests.
 * It combines the DSL name, package name, ontology model, and options into a single request object.
 *
 * @param dslName Name of the DSL to generate (must be a valid Kotlin identifier)
 * @param packageName Target package name for generated code
 * @param block Builder block for configuring the request
 * @return Configured InstanceDslRequest
 *
 * @throws IllegalArgumentException if dslName or packageName are invalid
 *
 * @sample com.example.CreateInstanceDslRequest
 *
 * @see InstanceDslRequest
 * @see InstanceDslRequestBuilder
 */
public fun instanceDslRequest(
    dslName: String,
    packageName: String,
    block: InstanceDslRequestBuilder.() -> Unit
): InstanceDslRequest {
    return InstanceDslRequestBuilder(dslName, packageName).apply(block).build()
}

/**
 * Builder for InstanceDslRequest.
 *
 * This builder provides a fluent API for configuring instance DSL generation requests.
 * Set the ontology model and options, then call `build()` to create the request.
 *
 * @param dslName Name of the DSL to generate
 * @param packageName Target package name for generated code
 *
 * @see instanceDslRequest
 * @see InstanceDslRequest
 */
public class InstanceDslRequestBuilder(
    private val dslName: String,
    private val packageName: String
) {
    lateinit var ontologyModel: OntologyModel
    var options: DslGenerationOptions = DslGenerationOptions()
    
    /**
     * Sets the ontology model directly.
     */
    fun ontologyModel(model: OntologyModel) {
        this.ontologyModel = model
    }
    
    /**
     * Loads ontology model from file paths.
     * Requires a logger for file reading operations.
     *
     * @param shaclPath Path to SHACL shapes file (relative to resources)
     * @param contextPath Optional path to JSON-LD context file (relative to resources)
     * @param logger KSP logger for file reading operations
     */
    fun fromOntology(shaclPath: String, contextPath: String? = null, logger: KSPLogger) {
        val reader = OntologyFileReader(logger)
        this.ontologyModel = reader.loadOntologyModel(shaclPath, contextPath)
    }
    
    /**
     * Configures generation options using a DSL builder.
     */
    fun options(block: DslGenerationOptionsBuilder.() -> Unit) {
        options = dslOptions(block)
    }
    
    /**
     * Alternative method name for configuring options (for better fluency).
     */
    fun withOptions(block: DslGenerationOptionsBuilder.() -> Unit) {
        options(block)
    }
    
    /**
     * Builds the InstanceDslRequest.
     */
    fun build(): InstanceDslRequest {
        return InstanceDslRequest(
            dslName = dslName,
            ontologyModel = ontologyModel,
            packageName = packageName,
            options = options
        )
    }
}

/**
 * Enhanced fluent DSL builder for InstanceDslRequest.
 *
 * This function provides a more fluent API for creating instance DSL generation requests.
 * It allows setting the DSL name, package name, ontology source, and options in a more
 * natural, composable way.
 *
 * @param dslName Name of the DSL to generate (must be a valid Kotlin identifier)
 * @param packageName Target package name for generated code
 * @param block Builder block for configuring the request
 * @return Configured InstanceDslRequest
 *
 * @throws IllegalArgumentException if dslName or packageName are invalid
 *
 * @sample com.example.CreateDslRequest
 *
 * @see InstanceDslRequest
 * @see InstanceDslRequestBuilder
 */
public fun dsl(
    dslName: String,
    packageName: String,
    block: InstanceDslRequestBuilder.() -> Unit
): InstanceDslRequest {
    return InstanceDslRequestBuilder(dslName, packageName).apply(block).build()
}

/**
 * Enhanced fluent DSL builder with method-based configuration.
 *
 * This builder provides an even more fluent API where you can set properties
 * using method calls instead of constructor parameters. This allows for
 * better composition and readability.
 *
 * Example usage:
 * ```kotlin
 * val request = dsl {
 *     name("skos")
 *     packageName("com.example")
 *     fromOntology("shapes.ttl", "context.jsonld", logger)
 *     withOptions {
 *         validation { enabled = true }
 *     }
 * }
 * ```
 *
 * @param block Builder block for configuring the request
 * @return Configured InstanceDslRequest
 *
 * @throws IllegalArgumentException if required properties are not set
 *
 * @sample com.example.CreateDslRequestFluent
 *
 * @see FluentDslBuilder
 * @see InstanceDslRequest
 */
public fun dsl(block: FluentDslBuilder.() -> Unit): InstanceDslRequest {
    return FluentDslBuilder().apply(block).build()
}

/**
 * Fluent DSL builder with method-based property setting.
 *
 * This builder allows setting all properties using method calls for maximum fluency.
 * All properties must be set before calling `build()`, otherwise an exception will be thrown.
 *
 * @see dsl
 * @see InstanceDslRequest
 */
public class FluentDslBuilder {
    private var dslName: String? = null
    private var packageName: String? = null
    private var ontologyModel: OntologyModel? = null
    private var options: DslGenerationOptions = DslGenerationOptions()
    
    /**
     * Sets the DSL name.
     */
    fun name(dslName: String) {
        this.dslName = dslName
    }
    
    /**
     * Sets the target package name.
     */
    fun packageName(packageName: String) {
        this.packageName = packageName
    }
    
    /**
     * Sets the ontology model directly.
     */
    fun ontologyModel(model: OntologyModel) {
        this.ontologyModel = model
    }
    
    /**
     * Loads ontology model from file paths.
     * Requires a logger for file reading operations.
     *
     * @param shaclPath Path to SHACL shapes file (relative to resources)
     * @param contextPath Optional path to JSON-LD context file (relative to resources)
     * @param logger KSP logger for file reading operations
     */
    fun fromOntology(shaclPath: String, contextPath: String? = null, logger: KSPLogger) {
        val reader = OntologyFileReader(logger)
        this.ontologyModel = reader.loadOntologyModel(shaclPath, contextPath)
    }
    
    /**
     * Configures generation options using a DSL builder.
     */
    fun withOptions(block: DslGenerationOptionsBuilder.() -> Unit) {
        options = dslOptions(block)
    }
    
    /**
     * Builds the InstanceDslRequest.
     */
    fun build(): InstanceDslRequest {
        requireNotNull(dslName) { "dslName must be set using name()" }
        requireNotNull(packageName) { "packageName must be set using packageName()" }
        requireNotNull(ontologyModel) { "ontologyModel must be set using ontologyModel() or fromOntology()" }
        
        return InstanceDslRequest(
            dslName = dslName!!,
            ontologyModel = ontologyModel!!,
            packageName = packageName!!,
            options = options
        )
    }
}

