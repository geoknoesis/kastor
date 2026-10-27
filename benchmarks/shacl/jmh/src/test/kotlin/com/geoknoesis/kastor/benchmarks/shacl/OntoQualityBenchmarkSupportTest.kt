package com.geoknoesis.kastor.benchmarks.shacl

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The onto-quality workload runs the default check and its SPARQL and REGEX detectors report what it plants. */
class OntoQualityBenchmarkSupportTest {
    @Test
    fun `the default check finds the planted cycle and class name`() {
        val report = OntoQualityBenchmarkSupport.check(OntoQualityBenchmarkSupport.checker(), OntoQualityBenchmarkSupport.ontology(40))
        val shapes = report.findings.mapNotNull { it.violation.shapeUri?.substringAfter('#') }.toSet()
        assertTrue("NoSubClassCycleShape" in shapes, shapes.toString())
        assertTrue("ClassNameUpperCamelShape" in shapes, shapes.toString())
    }
}
