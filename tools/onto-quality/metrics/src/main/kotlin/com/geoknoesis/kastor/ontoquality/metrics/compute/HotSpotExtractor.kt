package com.geoknoesis.kastor.ontoquality.metrics.compute

import com.geoknoesis.kastor.ontoquality.metrics.FanOutDistribution
import com.geoknoesis.kastor.ontoquality.metrics.FanOutEntry
import com.geoknoesis.kastor.ontoquality.metrics.HierarchyDepthMetrics
import com.geoknoesis.kastor.ontoquality.metrics.ImportsMetrics
import com.geoknoesis.kastor.ontoquality.metrics.OwlExtensions
import com.geoknoesis.kastor.ontoquality.metrics.SubClassFanOutMetrics

internal object HotSpotExtractor {
    fun topNByFanOut(subClassChildrenOf: Map<String, Set<String>>, topN: Int): List<FanOutEntry> =
        subClassChildrenOf
            .map { (p, ch) -> FanOutEntry(p, ch.size) }
            .sortedWith(compareByDescending<FanOutEntry> { it.directChildCount }.thenBy { it.parent })
            .take(topN)

    fun fanOutDistribution(subClassChildrenOf: Map<String, Set<String>>): FanOutDistribution {
        var single = 0L
        var twoFive = 0L
        var sixTwenty = 0L
        var twentyOneFifty = 0L
        var overFifty = 0L
        for ((_, ch) in subClassChildrenOf) {
            val n = ch.size
            if (n <= 0) continue
            when {
                n == 1 -> single++
                n in 2..5 -> twoFive++
                n in 6..20 -> sixTwenty++
                n in 21..50 -> twentyOneFifty++
                else -> overFifty++
            }
        }
        return FanOutDistribution(single, twoFive, sixTwenty, twentyOneFifty, overFifty)
    }

    fun buildSubClassFanOut(
        subClassChildrenOf: Map<String, Set<String>>,
        config: com.geoknoesis.kastor.ontoquality.metrics.MetricsConfig,
    ): SubClassFanOutMetrics {
        val parentsWithChildren = subClassChildrenOf.count { it.value.isNotEmpty() }.toLong()
        val totalDirectEdges = subClassChildrenOf.values.sumOf { it.size }.toLong()
        val fanOuts = subClassChildrenOf.filter { it.value.isNotEmpty() }.mapValues { it.value.size }
        val maxFanOut = fanOuts.values.maxOrNull() ?: 0
        val maxParents =
            fanOuts.filter { it.value == maxFanOut && maxFanOut > 0 }.keys.sorted()
        val counts = fanOuts.values.map { it.toDouble() }
        val mean = if (counts.isEmpty()) 0.0 else counts.average()
        val median =
            if (counts.isEmpty()) {
                0.0
            } else {
                val s = counts.sorted()
                val mid = s.size / 2
                if (s.size % 2 == 0) (s[mid - 1] + s[mid]) / 2.0 else s[mid]
            }
        val fullChildren =
            if (config.unboundedFanOutBreakdown) {
                subClassChildrenOf.filterValues { it.isNotEmpty() }
            } else {
                emptyMap()
            }
        val fullFanOutMap = fullChildren.mapValues { it.value.size }
        return SubClassFanOutMetrics(
            parentsWithChildren = parentsWithChildren,
            totalDirectEdges = totalDirectEdges,
            maxFanOut = maxFanOut,
            maxFanOutParents = maxParents,
            meanFanOut = mean,
            medianFanOut = median,
            topNByFanOut = topNByFanOut(subClassChildrenOf, config.topNHotSpots),
            fanOutDistribution = fanOutDistribution(subClassChildrenOf),
            fullFanOutMap = fullFanOutMap,
            fullSubClassChildrenOf = fullChildren,
        )
    }

    fun buildHierarchyDepthMetrics(
        iq: IntermediateQuantities,
        maxDepthCap: Int,
        includeDepthByClass: Boolean,
    ): HierarchyDepthMetrics {
        val maxDepth = iq.ditDepthOf.values.maxOrNull() ?: 0
        val depthCapHit = iq.ditDepthOf.any { (_, d) -> d >= maxDepthCap }

        val deepest =
            iq.namedClasses
                .filter { it !in iq.cycleParticipants }
                .maxWithOrNull(compareBy({ iq.ditDepthOf[it] ?: 0 }, { it }))
        val example =
            if (deepest == null || (iq.ditDepthOf[deepest] ?: 0) <= 0) {
                null
            } else {
                reconstructChain(deepest, iq.ditDepthOf, iq.superClassesOf, iq.namedClasses, iq.cycleParticipants)
            }

        val atMax = iq.namedClasses.count { (iq.ditDepthOf[it] ?: 0) == maxDepth && it !in iq.cycleParticipants }.toLong()

        val depthByClass =
            if (includeDepthByClass) {
                iq.namedClasses.associateWith { cls -> iq.ditDepthOf[cls] ?: 0 }
            } else {
                emptyMap()
            }

        return HierarchyDepthMetrics(
            maxDepthFound = maxDepth,
            deepestChainExample = example,
            classesAtMaxDepth = atMax,
            cyclesDetected = iq.cycleParticipants.size.toLong(),
            cycleParticipants = iq.cycleParticipants.sorted(),
            depthCapHit = depthCapHit,
            depthByClass = depthByClass,
        )
    }

    private fun reconstructChain(
        leaf: String,
        depth: Map<String, Int>,
        superClassesOf: Map<String, Set<String>>,
        namedClasses: Set<String>,
        cycleParticipants: Set<String>,
    ): List<String> {
        val chain = ArrayDeque<String>()
        var cur: String? = leaf
        val targetDepth = depth[leaf] ?: 0
        var steps = 0
        while (cur != null && steps <= targetDepth + 2) {
            chain.addFirst(cur)
            val d = depth[cur] ?: 0
            if (d <= 0) break
            val parents =
                superClassesOf[cur].orEmpty().filter {
                    it in namedClasses && it !in cycleParticipants && (depth[it] ?: -1) == d - 1
                }
            cur = parents.minOrNull()
            steps++
        }
        return chain.toList()
    }

    fun buildOwlExtensions(
        iq: IntermediateQuantities,
        imports: ImportsMetrics,
        ontologyHeaders: List<com.geoknoesis.kastor.ontoquality.metrics.OntologyHeader>,
        config: com.geoknoesis.kastor.ontoquality.metrics.MetricsConfig,
    ): OwlExtensions =
        OwlExtensions(
            subClassFanOut = buildSubClassFanOut(iq.subClassChildrenOf, config),
            classHierarchyDepth =
                buildHierarchyDepthMetrics(
                    iq,
                    config.maxDepthCap,
                    includeDepthByClass = config.unboundedFanOutBreakdown,
                ),
            imports = imports,
            ontologyHeaders = ontologyHeaders,
            integrationNamedClasses =
                if (config.unboundedFanOutBreakdown) iq.namedClasses else emptySet(),
        )
}
