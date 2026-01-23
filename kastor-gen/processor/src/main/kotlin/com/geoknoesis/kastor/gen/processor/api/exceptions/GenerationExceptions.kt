package com.geoknoesis.kastor.gen.processor.api.exceptions

import com.geoknoesis.kastor.gen.processor.api.model.ErrorContext
import com.geoknoesis.kastor.gen.processor.api.model.GenerationResult
import com.squareup.kotlinpoet.FileSpec

/**
 * Base sealed class for all code generation exceptions.
 *
 * This exception hierarchy provides structured error information with context details,
 * making it easier to handle and diagnose generation errors. All generation exceptions
 * extend this class and include relevant context information.
 *
 * @param message Human-readable error message
 * @param details Additional error details as key-value pairs
 * @param cause The underlying exception that caused this error
 *
 * @see MissingShapeException
 * @see InvalidConfigurationException
 * @see FileNotFoundException
 * @see ValidationException
 * @see FileGenerationException
 * @see ProcessingException
 */
sealed class GenerationException(
    message: String,
    val details: Map<String, Any> = emptyMap(),
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Thrown when a SHACL shape is missing for a class.
 *
 * This exception is thrown when code generation requires a SHACL shape for a class,
 * but no shape is found in the ontology model.
 *
 * @param classIri The IRI of the class that is missing a shape
 * @param cause The underlying exception that caused this error
 *
 * @sample com.example.HandleMissingShape
 */
class MissingShapeException(
    val classIri: String,
    val context: ErrorContext? = null,
    cause: Throwable? = null
) : GenerationException(
    "No SHACL shape found for class: $classIri${context?.let { " (file: ${it.file ?: "unknown"})" } ?: ""}",
    buildMap {
        put("classIri", classIri)
        context?.let { ctx ->
            ctx.file?.let { put("file", it) }
            ctx.line?.let { put("line", it) }
            ctx.shape?.let { put("shape", it) }
        }
    },
    cause
) {
    companion object {
        /**
         * Creates a MissingShapeException from an error context.
         */
        fun fromContext(
            classIri: String,
            context: ErrorContext,
            cause: Throwable? = null
        ): MissingShapeException {
            return MissingShapeException(
                classIri = classIri,
                context = context,
                cause = cause
            )
        }
    }
}

/**
 * Thrown when configuration is invalid.
 *
 * This exception is thrown when generation options or request parameters are invalid
 * or inconsistent.
 *
 * @param config The configuration that failed validation
 * @param reason Detailed reason why the configuration is invalid
 * @param cause The underlying exception that caused this error
 *
 * @sample com.example.HandleInvalidConfiguration
 */
class InvalidConfigurationException(
    val config: String,
    val reason: String,
    val context: ErrorContext? = null,
    cause: Throwable? = null
) : GenerationException(
    "Invalid configuration: $config - $reason${context?.let { " (${it.file ?: "unknown location"})" } ?: ""}",
    buildMap {
        put("config", config)
        put("reason", reason)
        context?.let { ctx ->
            ctx.file?.let { put("file", it) }
            ctx.line?.let { put("line", it) }
        }
    },
    cause
) {
    companion object {
        /**
         * Creates an InvalidConfigurationException from an error context.
         */
        fun fromContext(
            config: String,
            reason: String,
            context: ErrorContext,
            cause: Throwable? = null
        ): InvalidConfigurationException {
            return InvalidConfigurationException(
                config = config,
                reason = reason,
                context = context,
                cause = cause
            )
        }
    }
}

/**
 * Thrown when a required file is not found.
 *
 * This exception is thrown when an ontology file (SHACL, JSON-LD context, or OWL)
 * cannot be found at the specified path.
 *
 * @param path The file path that was not found
 * @param cause The underlying exception that caused this error
 *
 * @sample com.example.HandleFileNotFound
 */
class FileNotFoundException(
    val path: String,
    val context: ErrorContext? = null,
    cause: Throwable? = null
) : GenerationException(
    "File not found: $path${context?.let { " (requested from: ${it.file ?: "unknown"})" } ?: ""}",
    buildMap {
        put("path", path)
        context?.let { ctx ->
            ctx.file?.let { put("requestedFrom", it) }
            ctx.line?.let { put("line", it) }
        }
    },
    cause
) {
    companion object {
        /**
         * Creates a FileNotFoundException from an error context.
         */
        fun fromContext(
            path: String,
            context: ErrorContext,
            cause: Throwable? = null
        ): FileNotFoundException {
            return FileNotFoundException(
                path = path,
                context = context,
                cause = cause
            )
        }
    }
}

/**
 * Thrown when validation fails during generation.
 *
 * This exception is thrown when SHACL validation fails during code generation,
 * typically when validating the generated code or input data.
 *
 * @param message Human-readable error message
 * @param violations List of validation violation messages
 * @param cause The underlying exception that caused this error
 *
 * @sample com.example.HandleValidationError
 */
class ValidationException(
    message: String,
    val violations: List<String> = emptyList(),
    val context: ErrorContext? = null,
    cause: Throwable? = null
) : GenerationException(
    message,
    buildMap {
        put("violations", violations)
        context?.let { ctx ->
            ctx.property?.let { put("property", it) }
            ctx.shape?.let { put("shape", it) }
            ctx.classIri?.let { put("classIri", it) }
            ctx.file?.let { put("file", it) }
        }
    },
    cause
) {
    companion object {
        /**
         * Creates a ValidationException from an error context.
         */
        fun fromContext(
            message: String,
            violations: List<String> = emptyList(),
            context: ErrorContext,
            cause: Throwable? = null
        ): ValidationException {
            return ValidationException(
                message = message,
                violations = violations,
                context = context,
                cause = cause
            )
        }
    }
}

/**
 * Thrown when file generation fails.
 *
 * This exception is thrown when writing a generated file to disk fails,
 * typically due to I/O errors or permission issues.
 *
 * @param fileSpec The FileSpec that failed to write
 * @param packageName The package name where the file should be written
 * @param cause The underlying exception that caused this error
 *
 * @sample com.example.HandleFileGenerationError
 */
class FileGenerationException(
    val fileSpec: FileSpec,
    val packageName: String,
    cause: Throwable? = null
) : GenerationException(
    "Failed to write generated file: ${fileSpec.name} in package $packageName",
    mapOf(
        "fileName" to fileSpec.name,
        "packageName" to packageName
    ),
    cause
)

/**
 * Thrown when processing annotations fails.
 *
 * This exception is thrown when the KSP processor fails to process annotations,
 * typically due to invalid annotation parameters or missing required files.
 *
 * @param message Human-readable error message
 * @param annotationName The name of the annotation that failed to process
 * @param cause The underlying exception that caused this error
 *
 * @sample com.example.HandleProcessingError
 */
class ProcessingException(
    message: String,
    val annotationName: String? = null,
    cause: Throwable? = null
) : GenerationException(
    message,
    annotationName?.let { mapOf("annotation" to it) } ?: emptyMap(),
    cause
) {
    companion object {
        /**
         * Creates a ProcessingException from an error context.
         */
        fun fromContext(
            message: String,
            context: ErrorContext,
            cause: Throwable? = null
        ): ProcessingException {
            return ProcessingException(
                message = message,
                annotationName = context.file,
                cause = cause
            )
        }
    }
}

/**
 * Converts a GenerationResult.Failure to a GenerationException.
 *
 * This function provides a bridge between functional error handling (Result types)
 * and exception-based error handling.
 *
 * @param failure The failure result to convert
 * @return An appropriate GenerationException for the failure type
 */
fun toException(failure: GenerationResult.Failure): GenerationException {
    return when (failure) {
        is GenerationResult.Failure.FileError ->
            FileNotFoundException.fromContext(
                path = failure.file,
                context = ErrorContext.forFile(failure.file),
                cause = failure.cause
            )
        is GenerationResult.Failure.ConfigurationError ->
            InvalidConfigurationException.fromContext(
                config = failure.config,
                reason = failure.reason,
                context = ErrorContext.empty(),
                cause = null
            )
        is GenerationResult.Failure.ValidationError ->
            ValidationException.fromContext(
                message = failure.message,
                violations = failure.violations,
                context = ErrorContext.empty(),
                cause = null
            )
        is GenerationResult.Failure.ProcessingError ->
            ProcessingException.fromContext(
                message = failure.message,
                context = failure.context,
                cause = failure.cause
            )
    }
}

