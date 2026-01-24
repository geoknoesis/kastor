package com.geoknoesis.kastor.gen.processor.api.model

import com.geoknoesis.kastor.gen.processor.api.exceptions.toException
import com.squareup.kotlinpoet.FileSpec

/**
 * Result type for code generation operations.
 * Provides functional error handling instead of exceptions.
 *
 * @param T The success value type
 */
sealed class GenerationResult<out T> {
    /**
     * Successful generation result.
     *
     * @property value The generated file specification
     */
    data class Success<T>(val value: T) : GenerationResult<T>()
    
    /**
     * Failed generation result.
     */
    sealed class Failure : GenerationResult<Nothing>() {
        /**
         * File-related error during generation.
         *
         * @property file The file path or name
         * @property cause The underlying exception
         */
        data class FileError(
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
        data class ConfigurationError(
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
        data class ValidationError(
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
        data class ProcessingError(
            val message: String,
            val context: ErrorContext,
            val cause: Throwable? = null
        ) : Failure()
    }
    
    /**
     * Returns the value if successful, or null if failed.
     */
    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }
    
    /**
     * Returns the value if successful, or throws an exception if failed.
     */
    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw toException(this)
    }
    
    /**
     * Maps the success value using the given function.
     */
    fun <R> map(transform: (T) -> R): GenerationResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }
    
    /**
     * Maps the failure using the given function.
     */
    fun <R> mapFailure(transform: (Failure) -> GenerationResult<R>): GenerationResult<R> = when (this) {
        is Success -> Success(value) as GenerationResult<R>
        is Failure -> transform(this)
    }
    
    /**
     * Folds the result into a single value.
     */
    fun <R> fold(
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
data class ErrorContext(
    val file: String? = null,
    val line: Int? = null,
    val property: String? = null,
    val shape: String? = null,
    val classIri: String? = null
) {
    companion object {
        /**
         * Creates an empty error context.
         */
        fun empty() = ErrorContext()
        
        /**
         * Creates an error context for a file.
         */
        fun forFile(file: String, line: Int? = null) = ErrorContext(
            file = file,
            line = line
        )
        
        /**
         * Creates an error context for a property.
         */
        fun forProperty(property: String, shape: String? = null) = ErrorContext(
            property = property,
            shape = shape
        )
        
        /**
         * Creates an error context for a class.
         */
        fun forClass(classIri: String, shape: String? = null) = ErrorContext(
            classIri = classIri,
            shape = shape
        )
    }
}

/**
 * Extension function to convert a Result to an exception-based API.
 */
fun <T> GenerationResult<T>.getOrThrowException(): T {
    return when (this) {
        is GenerationResult.Success -> value
        is GenerationResult.Failure -> throw toException(this)
    }
}

/**
 * Creates a successful Result.
 */
fun <T> success(value: T): GenerationResult<T> = GenerationResult.Success(value)

/**
 * Creates a file error Result.
 */
fun <T> fileError(file: String, cause: Throwable? = null): GenerationResult<T> =
    GenerationResult.Failure.FileError(file, cause)

/**
 * Creates a configuration error Result.
 */
fun <T> configurationError(message: String, config: String, reason: String): GenerationResult<T> =
    GenerationResult.Failure.ConfigurationError(message, config, reason)

/**
 * Creates a validation error Result.
 */
fun <T> validationError(message: String, violations: List<String>): GenerationResult<T> =
    GenerationResult.Failure.ValidationError(message, violations)

/**
 * Creates a processing error Result.
 */
fun <T> processingError(message: String, context: ErrorContext, cause: Throwable? = null): GenerationResult<T> =
    GenerationResult.Failure.ProcessingError(message, context, cause)


