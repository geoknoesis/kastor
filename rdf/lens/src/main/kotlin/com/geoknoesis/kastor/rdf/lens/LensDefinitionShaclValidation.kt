package com.geoknoesis.kastor.rdf.lens

import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.Rdf
import java.time.Duration
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig

/** SHACL Core validation before compilation. No warning or truncation is ignored. */
object LensDefinitionShaclValidation {
    private val bundledShapesText by lazy {
        checkNotNull(LensDefinitionShaclValidation::class.java.getResource("/lens-execution-shapes.ttl")) {
            "Bundled lens SHACL shape resource missing"
        }.readText()
    }

    /** Return an independently parsed graph, never shared mutable shape state. */
    fun packagedShapes(): RdfGraph = Rdf.parse(bundledShapesText, "TURTLE")

    fun validateOrThrow(data: RdfGraph, shapes: RdfGraph = packagedShapes()) {
        val report = ShaclValidation.validator(
            ValidationConfig(providerId = "kastor", strictMode = true, maxViolations = 100,
                maxCombinedGraphTriples = 200_000L, timeout = Duration.ofSeconds(30))
        ).validate(data, shapes)
        require(report.isValid && !report.violationsTruncated) {
            "Lens SHACL validation failed: " +
                report.violations.take(3).joinToString("; ") { it.message }
        }
    }
}
