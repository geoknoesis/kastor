package com.geoknoesis.kastor.ontoquality.metrics.compute

import com.geoknoesis.kastor.ontoquality.metrics.KastorMetricsVocab
import com.geoknoesis.kastor.ontoquality.metrics.MetricValue

internal object OquareCalculators {
    fun depthOfInheritanceTree(q: IntermediateQuantities, scores: Boolean): MetricValue {
        if (q.namedClasses.isEmpty()) return notComputable("depthOfInheritanceTree", "DITOnto", "no classes")
        val maxDepth = q.ditDepthOf.values.maxOrNull() ?: 0
        return MetricValue(
            metricIri = KastorMetricsVocab.depthOfInheritanceTree,
            oquareName = "DITOnto",
            rawValue = maxDepth.toDouble(),
            score = if (scores) OquareScoring.scoreDIT(maxDepth) else null,
            computable = true,
            notes =
                if (q.cycleParticipants.isNotEmpty()) {
                    "Excluded ${q.cycleParticipants.size} cycle participants from depth computation"
                } else {
                    null
                },
        )
    }

    fun numberOfAncestorClasses(q: IntermediateQuantities, scores: Boolean): MetricValue {
        if (q.leaves.isEmpty()) return notComputable("numberOfAncestorClasses", "NACOnto", "no leaf classes")
        val mean =
            q.leaves
                .map { (q.superClassesOf[it] ?: emptySet()).size.toDouble() }
                .average()
        return MetricValue(
            metricIri = KastorMetricsVocab.numberOfAncestorClasses,
            oquareName = "NACOnto",
            rawValue = mean,
            score = if (scores) OquareScoring.scoreNAC(mean) else null,
            computable = true,
            notes = null,
        )
    }

    fun numberOfChildren(q: IntermediateQuantities, scores: Boolean): MetricValue {
        val denom = q.namedClasses.size - 1
        if (denom <= 0) return notComputable("numberOfChildren", "NOCOnto", "fewer than 2 classes")
        val sumChildren = q.subClassChildrenOf.values.sumOf { it.size }
        val mean = sumChildren.toDouble() / denom
        return MetricValue(
            metricIri = KastorMetricsVocab.numberOfChildren,
            oquareName = "NOCOnto",
            rawValue = mean,
            score = if (scores) OquareScoring.scoreNOC(mean) else null,
            computable = true,
            notes = null,
        )
    }

    fun couplingBetweenObjects(q: IntermediateQuantities, scores: Boolean): MetricValue {
        val denom = q.namedClasses.size - 1
        if (denom <= 0) return notComputable("couplingBetweenObjects", "CBOOnto", "fewer than 2 classes")
        val sumParents = q.superClassesOf.values.sumOf { it.size }
        val mean = sumParents.toDouble() / denom
        return MetricValue(
            metricIri = KastorMetricsVocab.couplingBetweenObjects,
            oquareName = "CBOOnto",
            rawValue = mean,
            score = if (scores) OquareScoring.scoreCBO(mean) else null,
            computable = true,
            notes = null,
        )
    }

    fun weightedMethodCount(q: IntermediateQuantities, scores: Boolean): MetricValue {
        if (q.namedClasses.isEmpty()) return notComputable("weightedMethodCount", "WMCOnto", "no classes")
        val sum =
            q.namedClasses.sumOf { c ->
                (q.propertiesByDomain[c]?.size ?: 0) + (q.subClassChildrenOf[c]?.size ?: 0)
            }
        val mean = sum.toDouble() / q.namedClasses.size
        return MetricValue(
            metricIri = KastorMetricsVocab.weightedMethodCount,
            oquareName = "WMCOnto",
            rawValue = mean,
            score = if (scores) OquareScoring.scoreWMC(mean) else null,
            computable = true,
            notes = null,
        )
    }

    fun responseForClass(q: IntermediateQuantities, scores: Boolean): MetricValue {
        if (q.namedClasses.isEmpty()) return notComputable("responseForClass", "RFCOnto", "no classes")
        val sum = q.namedClasses.sumOf { (q.propertiesByDomain[it]?.size ?: 0) }
        val mean = sum.toDouble() / q.namedClasses.size
        return MetricValue(
            metricIri = KastorMetricsVocab.responseForClass,
            oquareName = "RFCOnto",
            rawValue = mean,
            score = if (scores) OquareScoring.scoreRFC(mean) else null,
            computable = true,
            notes = null,
        )
    }

    fun numberOfProperties(q: IntermediateQuantities, scores: Boolean): MetricValue {
        if (q.namedClasses.isEmpty()) return notComputable("numberOfProperties", "NOMOnto", "no classes")
        val sum =
            q.namedClasses.sumOf { c ->
                (q.propertiesByDomain[c] ?: emptySet())
                    .count { it in q.datatypeProperties || it in q.objectProperties }
            }
        val mean = sum.toDouble() / q.namedClasses.size
        return MetricValue(
            metricIri = KastorMetricsVocab.numberOfProperties,
            oquareName = "NOMOnto",
            rawValue = mean,
            score = if (scores) OquareScoring.scoreNOM(mean) else null,
            computable = true,
            notes = null,
        )
    }

    fun lackOfCohesionInMethods(q: IntermediateQuantities, scores: Boolean): MetricValue {
        if (q.leaves.isEmpty() || q.pathsFromThingToLeaves == 0L) {
            return notComputable("lackOfCohesionInMethods", "LCOMOnto", "no leaves or no paths")
        }
        val sumLeafDepths = q.leaves.sumOf { (q.ditDepthOf[it] ?: 0).toDouble() }
        val value = sumLeafDepths / q.pathsFromThingToLeaves.toDouble()
        return MetricValue(
            metricIri = KastorMetricsVocab.lackOfCohesionInMethods,
            oquareName = "LCOMOnto",
            rawValue = value,
            score = if (scores) OquareScoring.scoreLCOM(value) else null,
            computable = true,
            notes = null,
        )
    }

    fun relationshipRichness(q: IntermediateQuantities, scores: Boolean): MetricValue {
        val total = q.subClassEdgeCount + q.nonSubClassEdgeCount
        if (total == 0L) return notComputable("relationshipRichness", "RROnto", "no relationship edges")
        val value = q.nonSubClassEdgeCount.toDouble() / total
        return MetricValue(
            metricIri = KastorMetricsVocab.relationshipRichness,
            oquareName = "RROnto",
            rawValue = value,
            score = if (scores) OquareScoring.scoreRichness(value) else null,
            computable = true,
            notes = null,
        )
    }

    fun inheritanceRichness(q: IntermediateQuantities, scores: Boolean): MetricValue {
        val total = q.subClassEdgeCount + q.nonSubClassEdgeCount
        if (total == 0L) return notComputable("inheritanceRichness", "INROnto", "no relationship edges")
        val value = q.subClassEdgeCount.toDouble() / total
        return MetricValue(
            metricIri = KastorMetricsVocab.inheritanceRichness,
            oquareName = "INROnto",
            rawValue = value,
            score = if (scores) OquareScoring.scoreRichness(value) else null,
            computable = true,
            notes = null,
        )
    }

    fun attributeRichness(q: IntermediateQuantities, scores: Boolean): MetricValue {
        if (q.namedClasses.isEmpty()) return notComputable("attributeRichness", "AROnto", "no classes")
        val value = q.datatypePropertyDomainAssertions.toDouble() / q.namedClasses.size
        return MetricValue(
            metricIri = KastorMetricsVocab.attributeRichness,
            oquareName = "AROnto",
            rawValue = value,
            score = if (scores) OquareScoring.scoreRichness(value) else null,
            computable = true,
            notes = null,
        )
    }

    fun classRichness(q: IntermediateQuantities, scores: Boolean): MetricValue {
        if (q.namedClasses.isEmpty()) return notComputable("classRichness", "CROnto", "no classes")
        val value = q.classesWithInstances.size.toDouble() / q.namedClasses.size
        val notes =
            if (q.classesWithInstances.isEmpty()) {
                "TBox-only graph; CROnto reflects unpopulated TBox, not quality"
            } else {
                null
            }
        return MetricValue(
            metricIri = KastorMetricsVocab.classRichness,
            oquareName = "CROnto",
            rawValue = value,
            score = if (scores) OquareScoring.scoreRichness(value) else null,
            computable = true,
            notes = notes,
        )
    }

    fun annotationRichness(q: IntermediateQuantities, scores: Boolean): MetricValue {
        if (q.namedClasses.isEmpty()) return notComputable("annotationRichness", "ANOnto", "no classes")
        val value = q.annotationAssertionsOnClasses.toDouble() / q.namedClasses.size
        return MetricValue(
            metricIri = KastorMetricsVocab.annotationRichness,
            oquareName = "ANOnto",
            rawValue = value,
            score = if (scores) OquareScoring.scoreRichness(value) else null,
            computable = true,
            notes = null,
        )
    }

    fun propertiesRichness(q: IntermediateQuantities, scores: Boolean): MetricValue {
        val totalProperties = q.objectProperties.size + q.datatypeProperties.size
        val denom = q.subClassEdgeCount + totalProperties
        if (denom == 0L) return notComputable("propertiesRichness", "PROnto", "no edges or properties")
        val value = totalProperties.toDouble() / denom
        return MetricValue(
            metricIri = KastorMetricsVocab.propertiesRichness,
            oquareName = "PROnto",
            rawValue = value,
            score = if (scores) OquareScoring.scoreRichness(value) else null,
            computable = true,
            notes = null,
        )
    }

    fun tangledness(q: IntermediateQuantities, scores: Boolean): MetricValue {
        if (q.leaves.isEmpty()) return notComputable("tangledness", "TMOnto", "no leaves")
        val value = q.pathsFromThingToLeaves.toDouble() / q.leaves.size
        return MetricValue(
            metricIri = KastorMetricsVocab.tangledness,
            oquareName = "TMOnto",
            rawValue = value,
            score = if (scores) OquareScoring.scoreTM(value) else null,
            computable = true,
            notes = null,
        )
    }

    private fun notComputable(metricLocal: String, oquareName: String, reason: String): MetricValue =
        MetricValue(
            metricIri = "${KastorMetricsVocab.NS}$metricLocal",
            oquareName = oquareName,
            rawValue = 0.0,
            score = null,
            computable = false,
            notes = reason,
        )
}
