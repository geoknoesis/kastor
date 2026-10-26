package com.geoknoesis.kastor.rdf

/**
 * Marks declarations of `:rdf:core` that are public only because other Kastor modules (the SHACL validator, Kastor
 * Gen, the providers) share them; Kotlin `internal` cannot cross module boundaries. They are **not** part of the
 * supported API: they may change or disappear in any release, without deprecation, and are left out of the binary
 * compatibility dump. Code outside Kastor must not use them.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is internal Kastor API, shared between Kastor modules only. It may change without notice.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
@MustBeDocumented
annotation class InternalKastorApi
