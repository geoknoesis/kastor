package com.geoknoesis.kastor.gen.runtime

/**
 * Marks declarations that are public only because other Kastor Gen modules (the validation adapters) need them.
 * They are **not** part of the supported API: they may change or disappear in any release, without deprecation, and
 * are left out of the binary compatibility dumps. Code outside Kastor Gen must not use them.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is internal Kastor Gen API, shared between Kastor Gen modules only. It may change without notice.",
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
annotation class KastorGenInternalApi
