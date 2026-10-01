package com.geoknoesis.kastor.rdf.shacl

/**
 * Ordering policy when multiple providers satisfy the requested [ValidationProfile].
 *
 * See [com.geoknoesis.kastor.rdf.shacl.ValidatorRegistry.createValidator].
 */
enum class EnginePreference {
    /** Prefer the Kastor native engine (`kastor`) when it matches; otherwise fall back. */
    NATIVE_FIRST,

    /**
     * Prefer non-native (bridge) providers such as `rdf4j` when registered; without a bridge the Kastor native
     * engine is used (the legacy `memory` provider id is an alias of the native engine, not a bridge).
     */
    BRIDGE_FIRST,

    /**
     * Prefer native when available (same as [NATIVE_FIRST] for current registry).
     * Intentionally heuristic; use [ValidationConfig.providerId] for reproducibility.
     */
    AUTO,
}
