package com.geoknoesis.kastor.benchmarks.shacl

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Runs every SHACL benchmark workload's validation once at small sizes so that `test` (and CI) catches workloads that
 * throw or no longer validate, without running JMH.
 */
class ShaclBenchmarkWorkloadSmokeTest {

    @Test
    fun `every complex shapes workload validates without errors`() {
        val validator = ShaclBenchmarkSupport.nativeValidator()
        val data = ComplexShapesBenchmarkSupport.data(64)
        for (workload in ComplexShapesBenchmarkSupport.WORKLOADS) {
            validator.validate(data, ComplexShapesBenchmarkSupport.shapes(workload))
        }
    }

    @Test
    fun `recursiveNode workload conforms over the benchmark chain length and beyond`() {
        val validator = ShaclBenchmarkSupport.nativeValidator()
        for (people in listOf(1000, 5000)) {
            val report = validator.validate(ComplexShapesBenchmarkSupport.data(people), ComplexShapesBenchmarkSupport.shapes("recursiveNode"))
            assertTrue(report.isValid, "recursiveNode with $people people: ${report.violations.take(3)}")
        }
    }

    @Test
    fun `monotone recursion and targetWhere workloads conform`() {
        val validator = ShaclBenchmarkSupport.nativeValidator()
        val data = ComplexShapesBenchmarkSupport.data(1000)
        for (workload in listOf("monotoneRecursion", "targetWhere")) {
            val report = validator.validate(data, ComplexShapesBenchmarkSupport.shapes(workload))
            assertTrue(report.isValid, "$workload: ${report.violations.take(3)}")
        }
    }

    @Test
    fun `core scaling and bundled workloads validate`() {
        val validator = ShaclBenchmarkSupport.nativeValidator()
        validator.validate(CoreBenchmarkSupport.validationData(1000), CoreBenchmarkSupport.validationShapes())
        // The JMH workload files live in the jmh source set (not on the test classpath); Gradle runs tests from the project dir.
        validator.validate(
            com.geoknoesis.kastor.rdf.Rdf.parseFromFile("src/jmh/resources/jmh-workload/data.ttl", "TURTLE"),
            com.geoknoesis.kastor.rdf.Rdf.parseFromFile("src/jmh/resources/jmh-workload/shapes.ttl", "TURTLE"),
        )
    }
}
