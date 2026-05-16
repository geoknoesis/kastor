package com.geoknoesis.kastor.examples.workshop

/**
 * Instructor entry point: runs Part A (hand RDF) then Part B (Kastor Gen materialization).
 *
 * ```bash
 * ./gradlew :examples:dcat-catalog-workshop:run
 * ```
 */
fun main() {
    println("=== Kastor DCAT catalog workshop (hands-on) ===\n")
    workshopHandRdf()
    println()
    workshopGenMaterialize()
}
