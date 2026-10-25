package com.geoknoesis.kastor.rdf.examples

/**
 * What every example prints when a step it demonstrates fails and it carries on: one fixed marker, so that the smoke
 * test (ExampleMainsSmokeTest) catches every caught failure whatever its message says.
 */
internal const val EXAMPLE_FAILURE_MARKER = "EXAMPLE FAILURE"

/** Reports a failed step of an example as `✗ EXAMPLE FAILURE: <what>: <message>` on standard output. */
internal fun reportFailure(what: String, failure: Exception) {
    println("✗ $EXAMPLE_FAILURE_MARKER: $what: ${failure.javaClass.simpleName}: ${failure.message}")
}
