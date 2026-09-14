package com.geoknoesis.kastor.ontoquality.metrics.compute

import com.geoknoesis.kastor.ontoquality.metrics.VocabularyMetrics
import com.geoknoesis.kastor.ontoquality.metrics.integration.KastorMetricsProvider
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for hierarchy traversal: depth/path computations must be iterative (no call-stack
 * growth per level) and path counting must be a memoized DP (no root-to-leaf path enumeration).
 */
class HierarchyScaleRegressionTest {
    private fun cls(name: String) = Iri("http://example.org/scale#$name")

    private fun classTriples(names: Iterable<String>): List<RdfTriple> = names.map { RdfTriple(cls(it), RDF.type, OWL.Class) }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `50k class chain computes metrics without StackOverflowError`() {
        val n = 50_000
        val triples = ArrayList<RdfTriple>(2 * n)
        triples += classTriples((0 until n).map { "C$it" })
        for (i in 1 until n) triples += RdfTriple(cls("C$i"), RDFS.subClassOf, cls("C${i - 1}"))
        val graph = MemoryGraph(triples)

        val report = VocabularyMetrics.compute(graph)
        val oq = report.owl.oquare
        // Depth is capped at MetricsConfig.maxDepthCap (50) by design; the cap must not limit traversal.
        assertEquals(50.0, oq.depthOfInheritanceTree.rawValue)
        assertTrue(report.owl.extensions.classHierarchyDepth.depthCapHit)
        // One path from owl:Thing of n edges (n-1 named edges plus Thing -> C0): LCOMOnto is uncapped.
        assertEquals(n.toDouble(), oq.lackOfCohesionInMethods.rawValue, 1e-9)
        // n-1 parents with exactly one direct subclass each.
        assertEquals(1.0, oq.numberOfChildren.rawValue, 1e-9)

        // Importance scoring over the same chain must also finish (bottom-up descendant counts).
        val context = KastorMetricsProvider().compute(graph)
        assertEquals(n, context.entityImportance.size)
        assertEquals("1 direct subclasses; ${n - 1} transitive descendants", context.entityHints.getValue(cls("C0").value))
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `tangled 40-layer lattice with 2^40 root-to-leaf paths completes`() {
        val layers = 40
        val triples = ArrayList<RdfTriple>()
        for (l in 0 until layers) {
            triples += classTriples(listOf("L${l}a", "L${l}b"))
            if (l > 0) {
                for (child in listOf("a", "b")) for (parent in listOf("a", "b")) {
                    triples += RdfTriple(cls("L$l$child"), RDFS.subClassOf, cls("L${l - 1}$parent"))
                }
            }
        }
        val report = VocabularyMetrics.compute(MemoryGraph(triples))
        val oq = report.owl.oquare
        // Roots are at depth 1 below owl:Thing.
        assertEquals(layers.toDouble(), oq.depthOfInheritanceTree.rawValue)
        // Every one of the 2^40 paths has exactly 40 edges from owl:Thing.
        assertEquals(layers.toDouble(), oq.lackOfCohesionInMethods.rawValue, 1e-9)
        // Every non-root class has exactly two direct parents: published TMOnto = 78 tangled / 80 classes,
        // Kastor-adapted TMOntoKastor = mean parents of tangled classes = 2.
        assertEquals(78.0 / 80.0, oq.tangledness.rawValue, 1e-9)
        assertEquals(2.0, report.owl.kastorAdapted.tangledness.rawValue, 1e-9)
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `200-layer lattice saturates path counts instead of overflowing`() {
        val layers = 200
        val triples = ArrayList<RdfTriple>()
        for (l in 0 until layers) {
            triples += classTriples(listOf("L${l}a", "L${l}b"))
            if (l > 0) {
                for (child in listOf("a", "b")) for (parent in listOf("a", "b")) {
                    triples += RdfTriple(cls("L$l$child"), RDFS.subClassOf, cls("L${l - 1}$parent"))
                }
            }
        }
        val bundle = GraphScanner.scan(MemoryGraph(triples), com.geoknoesis.kastor.ontoquality.metrics.MetricsConfig())
        assertEquals(Long.MAX_VALUE, bundle.intermediate.pathsFromThingToLeaves)
        val lcom = OquareCalculators.lackOfCohesionInMethods(bundle.intermediate, scores = true)
        assertTrue(lcom.computable)
        assertEquals(layers.toDouble(), lcom.rawValue, 1e-6)
    }
}
