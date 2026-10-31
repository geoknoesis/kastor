package com.geoknoesis.kastor.rdf.lens

import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig

/** SHACL Core validation before compilation. No warning or truncation is ignored. */
object LensDefinitionShaclValidation {
    fun validateOrThrow(data: RdfGraph, shapes: RdfGraph) {
        val report = ShaclValidation.validator(
            ValidationConfig(providerId = "kastor", strictMode = true, maxViolations = 100)
        ).validate(data, shapes)
        require(report.isValid && !report.violationsTruncated) {
            "Lens SHACL validation failed: " +
                report.violations.take(3).joinToString("; ") { it.message }
        }
    }
}
