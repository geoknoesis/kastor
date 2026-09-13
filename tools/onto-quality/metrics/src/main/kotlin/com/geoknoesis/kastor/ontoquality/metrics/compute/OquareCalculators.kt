package com.geoknoesis.kastor.ontoquality.metrics.compute

import com.geoknoesis.kastor.ontoquality.metrics.KastorMetricsVocab
import com.geoknoesis.kastor.ontoquality.metrics.MetricValue

/**
 * OQuaRE structural metrics.
 *
 * **Source.** Formulas follow the OQuaRE metric table of Duque-Ramos et al. (2014), *Evaluating the Good
 * Ontology Design Guideline (GoodOD) with the ontology quality requirements and evaluation method and
 * metrics (OQuaRE)*, PLoS ONE 9(8), https://doi.org/10.1371/journal.pone.0104463, as operationalised by
 * the reference implementation [tecnomod-um/oquare-metrics](https://github.com/tecnomod-um/oquare-metrics).
 * Where the paper leaves an RDF-level choice open, the interpretation used here is stated per metric.
 *
 * **Notation** (all over the *asserted* graph, after namespace exclusion):
 * - `C` named classes; `Root` classes with no named (non-cycle) superclass, i.e. direct children of owl:Thing;
 * - `Sup_C` / `Sub_C` direct named superclasses / subclasses of C; `|SubClassOf|` = Σ|Sup_C|;
 * - `P_C` declared object/datatype properties *used* by C — `p rdfs:domain C` or an `owl:Restriction`
 *   on `p` attached to C by `rdfs:subClassOf` / `owl:equivalentClass` (each (C, p) pair counted once);
 * - `leaves` classes without named (non-cycle) subclasses; paths run from a root to a leaf over the acyclic
 *   part of the hierarchy (cycle participants are excluded and reported separately).
 */
internal object OquareCalculators {
    /** DITOnto = max over leaves of the length (edges) of the longest root-to-leaf path. */
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

    /** NACOnto = Σ|Sup_leaf| / |leaves| (mean number of direct superclasses per leaf class). */
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

    /** NOCOnto = Σ|Sub_C| / (|C| − |Root|). */
    fun numberOfChildren(q: IntermediateQuantities, scores: Boolean): MetricValue {
        val denom = nonRootClasses(q)
        if (denom <= 0) return notComputable("numberOfChildren", "NOCOnto", "no non-root classes")
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

    /** CBOOnto = Σ|Sup_C| / (|C| − |Root|). */
    fun couplingBetweenObjects(q: IntermediateQuantities, scores: Boolean): MetricValue {
        val denom = nonRootClasses(q)
        if (denom <= 0) return notComputable("couplingBetweenObjects", "CBOOnto", "no non-root classes")
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

    /** WMCOnto = Σ(|P_C| + |Sub_C|) / |C|. */
    fun weightedMethodCount(q: IntermediateQuantities, scores: Boolean): MetricValue {
        if (q.namedClasses.isEmpty()) return notComputable("weightedMethodCount", "WMCOnto", "no classes")
        val sum =
            q.namedClasses.sumOf { c ->
                (q.propertiesOfClass[c]?.size ?: 0) + (q.subClassChildrenOf[c]?.size ?: 0)
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

    /** RFCOnto = Σ(|P_C| + |Sup_C|) / (|C| − |Root|). */
    fun responseForClass(q: IntermediateQuantities, scores: Boolean): MetricValue {
        val denom = nonRootClasses(q)
        if (denom <= 0) return notComputable("responseForClass", "RFCOnto", "no non-root classes")
        val sum = q.namedClasses.sumOf { (q.propertiesOfClass[it]?.size ?: 0) + (q.superClassesOf[it]?.size ?: 0) }
        val mean = sum.toDouble() / denom
        return MetricValue(
            metricIri = KastorMetricsVocab.responseForClass,
            oquareName = "RFCOnto",
            rawValue = mean,
            score = if (scores) OquareScoring.scoreRFC(mean) else null,
            computable = true,
            notes = null,
        )
    }

    /** NOMOnto = Σ|P_C| / |C| (property usages per class). */
    fun numberOfProperties(q: IntermediateQuantities, scores: Boolean): MetricValue {
        if (q.namedClasses.isEmpty()) return notComputable("numberOfProperties", "NOMOnto", "no classes")
        val mean = q.propertyUsageCount.toDouble() / q.namedClasses.size
        return MetricValue(
            metricIri = KastorMetricsVocab.numberOfProperties,
            oquareName = "NOMOnto",
            rawValue = mean,
            score = if (scores) OquareScoring.scoreNOM(mean) else null,
            computable = true,
            notes = null,
        )
    }

    /**
     * LCOMOnto = Σ length(path) / |paths| over all root-to-leaf paths (mean path length). Both sums come from
     * the memoized hierarchy DP, so tangled hierarchies with exponentially many paths are handled exactly.
     */
    fun lackOfCohesionInMethods(q: IntermediateQuantities, scores: Boolean): MetricValue {
        if (q.leaves.isEmpty() || q.pathCount <= 0.0) {
            return notComputable("lackOfCohesionInMethods", "LCOMOnto", "no leaves or no paths")
        }
        val value = q.totalPathLength / q.pathCount
        if (!value.isFinite()) {
            return notComputable("lackOfCohesionInMethods", "LCOMOnto", "path count exceeds floating-point range")
        }
        return MetricValue(
            metricIri = KastorMetricsVocab.lackOfCohesionInMethods,
            oquareName = "LCOMOnto",
            rawValue = value,
            score = if (scores) OquareScoring.scoreLCOM(value) else null,
            computable = true,
            notes = null,
        )
    }

    /**
     * RROnto = Σ|P_C| / (|SubClassOf| + Σ|P_C|): property usages relative to all class relationships.
     * rdf:type, domain/range declarations themselves, owl:imports and annotations are **not** relationships.
     */
    fun relationshipRichness(q: IntermediateQuantities, scores: Boolean): MetricValue {
        val total = q.subClassEdgeCount + q.propertyUsageCount
        if (total == 0L) return notComputable("relationshipRichness", "RROnto", "no relationship edges")
        val value = q.propertyUsageCount.toDouble() / total
        return MetricValue(
            metricIri = KastorMetricsVocab.relationshipRichness,
            oquareName = "RROnto",
            rawValue = value,
            score = if (scores) OquareScoring.scoreRichness(value) else null,
            computable = true,
            notes = null,
        )
    }

    /** INROnto = |SubClassOf| / |C| (mean number of direct subclass links per class). */
    fun inheritanceRichness(q: IntermediateQuantities, scores: Boolean): MetricValue {
        if (q.namedClasses.isEmpty()) return notComputable("inheritanceRichness", "INROnto", "no classes")
        val value = q.subClassEdgeCount.toDouble() / q.namedClasses.size
        return MetricValue(
            metricIri = KastorMetricsVocab.inheritanceRichness,
            oquareName = "INROnto",
            rawValue = value,
            score = if (scores) OquareScoring.scoreRichness(value) else null,
            computable = true,
            notes = null,
        )
    }

    /** AROnto = Σ|Att_C| / |C|, with attributes = datatype properties whose rdfs:domain is a named class. */
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

    /** CROnto = |I| / |C|: distinct rdf:type assertions to named classes (mean instances per class). */
    fun classRichness(q: IntermediateQuantities, scores: Boolean): MetricValue {
        if (q.namedClasses.isEmpty()) return notComputable("classRichness", "CROnto", "no classes")
        val value = q.instanceAssertionCount.toDouble() / q.namedClasses.size
        val notes =
            if (q.instanceAssertionCount == 0L) {
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

    /** ANOnto = Σ|annotations_C| / |C| with annotations = rdfs:label, rdfs:comment, skos:definition values. */
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

    /** PROnto = |P| / (|SubClassOf| + |P|) with P = declared object and datatype properties. */
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

    /**
     * TMOnto = Σ|Sup_C| / |C_DP| over classes C_DP with more than one direct superclass (mean number of
     * direct parents of multiply-inheriting classes; ≥ 2 whenever defined, matching the OQuaRE 1–5 scale).
     * A hierarchy without multiple inheritance yields 0 (best score).
     */
    fun tangledness(q: IntermediateQuantities, scores: Boolean): MetricValue {
        if (q.namedClasses.isEmpty()) return notComputable("tangledness", "TMOnto", "no classes")
        val tangled = q.superClassesOf.values.filter { it.size > 1 }
        val value = if (tangled.isEmpty()) 0.0 else tangled.sumOf { it.size }.toDouble() / tangled.size
        return MetricValue(
            metricIri = KastorMetricsVocab.tangledness,
            oquareName = "TMOnto",
            rawValue = value,
            score = if (scores) OquareScoring.scoreTM(value) else null,
            computable = true,
            notes = if (tangled.isEmpty()) "no classes with multiple direct superclasses" else null,
        )
    }

    private fun nonRootClasses(q: IntermediateQuantities): Int = q.namedClasses.size - q.roots.size

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
