package com.geoknoesis.kastor.ontoquality.metrics

import com.geoknoesis.kastor.ontoquality.metrics.compute.GraphScanner
import com.geoknoesis.kastor.ontoquality.metrics.compute.OquareCalculators
import com.geoknoesis.kastor.ontoquality.metrics.compute.SkosCalculators
import com.geoknoesis.kastor.ontoquality.metrics.compute.HotSpotExtractor
import com.geoknoesis.kastor.rdf.RdfGraph
import java.time.Instant

object VocabularyMetrics {
    fun compute(graph: RdfGraph, config: MetricsConfig = MetricsConfig.default()): VocabularyMetricsReport {
        val bundle = GraphScanner.scan(graph, config)
        if (config.useInferredGraph) {
            // Caller must supply an inferred graph; flag reserved for API clarity.
        }
        val owl = computeOwl(graph, config, bundle)
        val skos = computeSkos(graph, config, bundle)
        return VocabularyMetricsReport(
            graph = bundle.graphMetrics,
            owl = owl,
            skos = skos,
            moduleVersion = VocabularyMetricsReport.MODULE_VERSION,
            oquareVersion = VocabularyMetricsReport.OQUARE_VERSION,
            computedAt = Instant.now(),
        )
    }

    fun computeOwl(graph: RdfGraph, config: MetricsConfig = MetricsConfig.default()): OwlMetricsSection {
        val bundle = GraphScanner.scan(graph, config)
        return computeOwl(graph, config, bundle)
    }

    fun computeSkos(graph: RdfGraph, config: MetricsConfig = MetricsConfig.default()): SkosMetricsSection {
        val bundle = GraphScanner.scan(graph, config)
        return computeSkos(graph, config, bundle)
    }

    fun computeGraph(graph: RdfGraph): GraphMetricsSection = GraphScanner.scan(graph, MetricsConfig.default()).graphMetrics

    private fun computeOwl(
        @Suppress("UNUSED_PARAMETER") graph: RdfGraph,
        config: MetricsConfig,
        bundle: com.geoknoesis.kastor.ontoquality.metrics.compute.ScanBundle,
    ): OwlMetricsSection {
        val iq = bundle.intermediate
        val scores = config.emitOQuaREScores
        val oq =
            OquareMetrics(
                depthOfInheritanceTree = OquareCalculators.depthOfInheritanceTree(iq, scores),
                numberOfAncestorClasses = OquareCalculators.numberOfAncestorClasses(iq, scores),
                numberOfChildren = OquareCalculators.numberOfChildren(iq, scores),
                couplingBetweenObjects = OquareCalculators.couplingBetweenObjects(iq, scores),
                weightedMethodCount = OquareCalculators.weightedMethodCount(iq, scores),
                responseForClass = OquareCalculators.responseForClass(iq, scores),
                numberOfProperties = OquareCalculators.numberOfProperties(iq, scores),
                lackOfCohesionInMethods = OquareCalculators.lackOfCohesionInMethods(iq, scores),
                relationshipRichness = OquareCalculators.relationshipRichness(iq, scores),
                inheritanceRichness = OquareCalculators.inheritanceRichness(iq, scores),
                attributeRichness = OquareCalculators.attributeRichness(iq, scores),
                classRichness = OquareCalculators.classRichness(iq, scores),
                annotationRichness = OquareCalculators.annotationRichness(iq, scores),
                propertiesRichness = OquareCalculators.propertiesRichness(iq, scores),
                tangledness = OquareCalculators.tangledness(iq, scores),
            )
        val extensions =
            HotSpotExtractor.buildOwlExtensions(iq, bundle.imports, bundle.ontologyHeaders, config)
        return OwlMetricsSection(entityCounts = bundle.owlEntityCounts, oquare = oq, extensions = extensions)
    }

    private fun computeSkos(
        graph: RdfGraph,
        config: MetricsConfig,
        bundle: com.geoknoesis.kastor.ontoquality.metrics.compute.ScanBundle,
    ): SkosMetricsSection = SkosCalculators.compute(graph, bundle.skosScratch, config)
}
