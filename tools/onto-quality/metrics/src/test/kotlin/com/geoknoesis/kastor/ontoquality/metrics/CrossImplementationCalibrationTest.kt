package com.geoknoesis.kastor.ontoquality.metrics

import com.geoknoesis.kastor.rdf.Rdf
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Logs metrics for [cross-impl fixtures](resources/cross-impl) for comparison with
 * [tecnomod-um/oquare-metrics](https://github.com/tecnomod-um/oquare-metrics). v0.1 does not fail on drift.
 */
class CrossImplementationCalibrationTest {
    private val log = LoggerFactory.getLogger(javaClass)

    @Test
    fun `log metrics for minimal-branches fixture`() {
        val ttl =
            javaClass.getResourceAsStream("/cross-impl/minimal-branches.ttl")!!
                .bufferedReader()
                .readText()
        val g = Rdf.parse(ttl, "TURTLE")
        val r = VocabularyMetrics.compute(g)
        log.info(
            "cross-impl minimal-branches — DITOnto={} NOCOnto={} RROnto={} INROnto={}",
            r.owl.oquare.depthOfInheritanceTree.rawValue,
            r.owl.oquare.numberOfChildren.rawValue,
            r.owl.oquare.relationshipRichness.rawValue,
            r.owl.oquare.inheritanceRichness.rawValue,
        )
        log.info("Full OQuaRE raw list: {}", r.owl.oquare.toList().associate { it.oquareName to it.rawValue })
    }
}
