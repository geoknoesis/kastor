package com.geoknoesis.kastor.gen.processor.api.model

import com.geoknoesis.kastor.gen.processor.api.exceptions.toException
import com.squareup.kotlinpoet.FileSpec

/**
 * Result type for code generation operations.
 * Provides functional error handling instead of exceptions.
 *
 * @param T The success value type
 */
public sealed class GenerationResult<out T> {
    /**
     * Successful generation result.
     *
     * @property value The generated file specification
     */
    public data class Success<T>(val value: T) : GenerationResult<T>()
    
    /**
     * Failed generation result.
     */
    public sealed class Failure : GenerationResult<Nothing>() {
        /**
         * File-related error during generation.
         *
         * @property file The file path or name
         * @property cause The underlying exception
         */
        public data class FileError(
            val file: String,
            val cause: Throwable? = null
        ) : Failure()
        
        /**
         * Configuration error.
         *
         * @property message Error message
         * @property config The configuration that failed
         * @property reason Detailed reason for failure
         */
        public data class ConfigurationError(
            val message: String,
            val config: String,
            val reason: String
        ) : Failure()
        
        /**
         * Validation error.
         *
         * @property message Error message
         * @property violations List of validation violations
         */
        public data class ValidationError(
            val message: String,
            val violations: List<String>
        ) : Failure()
        
        /**
         * Processing error.
         *
         * @property message Error message
         * @property context Additional error context
         * @property cause The underlying exception
         */
        public data class ProcessingError(
            val message: String,
            val context: ErrorContext,
            val cause: Throwable? = null
        ) : Failure()
    }
    
    /**
     * Returns the value if successful, or null if failed.
     */
    public fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }
    
    /**
     * Returns the value if successful, or throws an exception if failed.
     */
    public fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw toException(this)
    }
    
    /**
     * Maps the success value using the given function.
     */
    public fun <R> map(transform: (T) -> R): GenerationResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }
    
    /**
     * Maps the failure using the given function.
     */
    public fun <R> mapFailure(transform: (Failure) -> GenerationResult<R>): GenerationResult<R> = when (this) {
        is Success -> Success(value) as GenerationResult<R>
        is Failure -> transform(this)
    }
    
    /**
     * Folds the result into a single value.
     */
    public fun <R> fold(
        onSuccess: (T) -> R,
        onFailure: (Failure) -> R
    ): R = when (this) {
        is Success -> onSuccess(value)
        is Failure -> onFailure(this)
    }
}

/**
 * Error context providing detailed information about where an error occurred.
 *
 * @property file The file path where the error occurred
 * @property line The line number (if applicable)
 * @property property The property name (if applicable)
 * @property shape The SHACL shape IRI (if applicable)
 * @property classIri The class IRI (if applicable)
 */
public data class ErrorContext(
    val file: String? = null,
    val line: Int? = null,
    val property: String? = null,
    val shape: String? = null,
    val classIri: String? = null
) {
    public companion object {
        /**
         * Creates an empty error context.
         */
        public fun empty(): ErrorContext = ErrorContext()
        
        /**
         * Creates an error context for a file.
         */
        public fun forFile(file: String, line: Int? = null): ErrorContext = ErrorContext(
            file = file,
            line = line
        )
        
        /**
         * Creates an error context for a property.
         */
        public fun forProperty(property: String, shape: String? = null): ErrorContext = ErrorContext(
            property = property,
            shape = shape
        )
        
        /**
         * Creates an error context for a class.
         */
        public fun forClass(classIri: String, shape: String? = null): ErrorContext = ErrorContext(
            classIri = classIri,
            shape = shape
        )
    }
}

/**
 * Extension function to convert a Result to an exception-based API.
 */
public fun <T> GenerationResult<T>.getOrThrowException(): T {
    return when (this) {
        is GenerationResult.Success -> value
        is GenerationResult.Failure -> throw toException(this)
    }
}

/**
 * Creates a successful Result.
 */
public fun <T> success(value: T): GenerationResult<T> = GenerationResult.Success(value)

/**
 * Creates a file error Result.
 */
public fun <T> fileError(file: String, cause: Throwable? = null): GenerationResult<T> =
    GenerationResult.Failure.FileError(file, cause)

/**
 * Creates a configuration error Result.
 */
public fun <T> configurationError(message: String, config: String, reason: String): GenerationResult<T> =
    GenerationResult.Failure.ConfigurationError(message, config, reason)

/**
 * Creates a validation error Result.
 */
public fun <T> validationError(message: String, violations: List<String>): GenerationResult<T> =
    GenerationResult.Failure.ValidationError(message, violations)

/**
 * Creates a processing error Result.
 */
public fun <T> processingError(message: String, context: ErrorContext, cause: Throwable? = null): GenerationResult<T> =
    GenerationResult.Failure.ProcessingError(message, context, cause)


