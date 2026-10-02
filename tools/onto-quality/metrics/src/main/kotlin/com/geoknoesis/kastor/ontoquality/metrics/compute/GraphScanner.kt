package com.geoknoesis.kastor.ontoquality.metrics.compute

import com.geoknoesis.kastor.ontoquality.metrics.GraphMetricsSection
import com.geoknoesis.kastor.ontoquality.metrics.ImportsMetrics
import com.geoknoesis.kastor.ontoquality.metrics.MetricsConfig
import com.geoknoesis.kastor.ontoquality.metrics.OntologyHeader
import com.geoknoesis.kastor.ontoquality.metrics.OwlEntityCounts
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.SKOS
import kotlin.math.min

internal data class ScanBundle(
    val graphMetrics: GraphMetricsSection,
    val owlEntityCounts: OwlEntityCounts,
    val intermediate: IntermediateQuantities,
    val skosScratch: SkosScratch,
    val imports: ImportsMetrics,
    val ontologyHeaders: List<OntologyHeader>,
)

internal data class SkosScratch(
    val concepts: MutableSet<String> = mutableSetOf(),
    val schemes: MutableSet<String> = mutableSetOf(),
    var collectionCount: Long = 0,
    var orderedCollectionCount: Long = 0,
    var broaderEdges: Long = 0,
    var narrowerEdges: Long = 0,
    var relatedEdges: Long = 0,
    var broaderTransitiveEdges: Long = 0,
    var narrowerTransitiveEdges: Long = 0,
    var exactMatchEdges: Long = 0,
    var closeMatchEdges: Long = 0,
    var broadMatchEdges: Long = 0,
    var narrowMatchEdges: Long = 0,
    var relatedMatchEdges: Long = 0,
    val parentToNarrowers: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    /** `skos:related` IRI pairs, collected once so sibling-cohort metrics need no further graph scans. */
    val relatedPairs: MutableList<Pair<String, String>> = mutableListOf(),
)

internal object GraphScanner {
    private val VERSION_HINT = Regex("\\d+\\.\\d+")

    private fun subjectKey(s: RdfResource): String =
        when (s) {
            is Iri -> s.value
            is BlankNode -> s.toString()
        }

    private fun excluded(cfg: MetricsConfig, iri: String): Boolean = cfg.excludedNamespaces.any { iri.startsWith(it) }

    /**
     * VoID distinct-object key for a literal: the full RDF term (lexical form, datatype IRI, language tag and base
     * direction), so `"1"`, `"1"^^xsd:integer` and `"1"@en` are distinct objects. The lexical form is
     * length-prefixed so no lexical content can collide with another term's key. Language tags are compared
     * case-insensitively (their RDF value space is lower case).
     */
    private fun literalKey(o: Literal): String {
        val lang = (o as? LangString)?.let { "@${it.lang.lowercase()}${it.direction?.let { d -> "--$d" } ?: ""}" } ?: ""
        return "L${o.lexical.length}:${o.lexical}^^${o.datatype.value}$lang"
    }

    /**
     * VoID distinct-object key for any object term. RDF 1.2 triple terms are keyed by their components, so
     * `<<( :a :b :c )>>` occurring twice is one distinct object and differs from `<<( :a :b "c" )>>`.
     */
    private fun objectKey(o: RdfTerm): String? =
        when (o) {
            is Iri -> o.value
            is Literal -> literalKey(o)
            is BlankNode -> o.toString()
            is TripleTerm -> "T(${objectKey(o.triple.subject)} ${o.triple.predicate.value} ${objectKey(o.triple.obj)})"
            else -> null
        }

    private val OWL_SOME_VALUES_FROM = "${OWL.namespace}someValuesFrom"
    private val OWL_ALL_VALUES_FROM = "${OWL.namespace}allValuesFrom"
    private val OWL_ON_CLASS = "${OWL.namespace}onClass"
    private val OWL_INTERSECTION_OF = "${OWL.namespace}intersectionOf"
    private val OWL_UNION_OF = "${OWL.namespace}unionOf"
    private val RDF_FIRST = "${RDF.namespace}first"
    private val RDF_REST = "${RDF.namespace}rest"

    /**
     * The anonymous class-expression nodes of [start]: the node itself and, through `owl:intersectionOf` /
     * `owl:unionOf` lists (nested to any depth), every blank-node operand. A defined class
     * (`owl:equivalentClass [ owl:intersectionOf ( :Base [ a owl:Restriction … ] ) ]`) has its restrictions there.
     * Iterative, and each node and list cell is visited once, so malformed (cyclic) lists terminate.
     */
    private fun expressionNodes(
        start: String,
        operandLists: Map<String, List<String>>,
        listFirst: Map<String, String>,
        listRest: Map<String, String>,
    ): Set<String> {
        if (start !in operandLists) return setOf(start)
        val seen = LinkedHashSet<String>()
        val cells = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue.addLast(start)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!seen.add(node)) continue
            for (head in operandLists[node].orEmpty()) {
                var cell: String? = head
                while (cell != null && cells.add(cell)) {
                    listFirst[cell]?.let(queue::addLast)
                    cell = listRest[cell]
                }
            }
        }
        return seen
    }

    fun scan(graph: RdfGraph, config: MetricsConfig): ScanBundle {
        val owlThing = OWL.Thing.value
        val owlNothing = "${OWL.namespace}Nothing"

        var tripleCount = 0L
        val distinctSubjects = mutableSetOf<String>()
        val distinctPredicates = mutableSetOf<String>()
        val distinctObjects = mutableSetOf<String>()
        var blankNodeSubjects = 0L
        var literalObjects = 0L
        var iriObjects = 0L
        val distinctClassesUsed = mutableSetOf<String>()

        val subclassPairs = mutableListOf<Pair<String, String>>()
        val domainPairs = mutableListOf<Pair<String, String>>()
        val typeAssertions = mutableListOf<Pair<String, String>>() // subject key -> type iri (object always iri here)

        val owlClassSubjects = mutableSetOf<String>()
        val rdfsClassSubjects = mutableSetOf<String>()
        val objectPropSubjects = mutableSetOf<String>()
        val datatypePropSubjects = mutableSetOf<String>()
        val annotationPropSubjects = mutableSetOf<String>()
        val ontologySubjects = mutableSetOf<String>()
        val individualSubjects = mutableSetOf<String>()
        val restrictionSubjects = mutableSetOf<String>()

        val annotationCounts = mutableMapOf<String, Long>()

        /// class IRI -> anonymous class-expression node attached via rdfs:subClassOf / owl:equivalentClass
        val classExpressionLinks = mutableListOf<Pair<String, String>>()

        /// restriction node key -> owl:onProperty IRIs
        val onPropertyOf = mutableMapOf<String, MutableSet<String>>()

        /// restriction node key -> class fillers (owl:someValuesFrom / owl:allValuesFrom / owl:onClass IRIs)
        val restrictionFillers = mutableMapOf<String, MutableSet<String>>()

        /// class-expression node key -> heads of its owl:intersectionOf / owl:unionOf lists; list cells (blank operands only)
        val operandLists = mutableMapOf<String, MutableList<String>>()
        val listFirst = mutableMapOf<String, String>()
        val listRest = mutableMapOf<String, String>()
        val rangePairs = mutableListOf<Pair<String, String>>()

        val importsList = mutableListOf<String>()
        val skos = SkosScratch()

        /// Track annotation-like triples on IRIs for later annotation richness
        fun bumpAnnotation(subjIri: String) {
            annotationCounts[subjIri] = annotationCounts.getOrDefault(subjIri, 0L) + 1L
        }

        for (t in graph.getTriplesSequence()) {
            tripleCount++
            val p = t.predicate.value
            distinctPredicates.add(p)

            val sk = subjectKey(t.subject)
            distinctSubjects.add(sk)

            when (t.subject) {
                is BlankNode -> blankNodeSubjects++
                else -> Unit
            }

            when (val o = t.obj) {
                is Iri -> {
                    distinctObjects.add(o.value)
                    iriObjects++
                }
                is Literal -> {
                    literalObjects++
                    distinctObjects.add(literalKey(o))
                }
                is BlankNode, is TripleTerm -> objectKey(o)?.let(distinctObjects::add)
                else -> Unit // Var, etc.
            }

            val subIri = (t.subject as? Iri)?.value
            val objIri = (t.obj as? Iri)?.value

            if (t.predicate == RDF.type && objIri != null) {
                distinctClassesUsed.add(objIri)
                val subKey = sk
                typeAssertions.add(subKey to objIri)
                if (subIri != null && !excluded(config, subIri)) {
                    when (objIri) {
                        OWL.Class.value -> owlClassSubjects.add(subIri)
                        RDFS.Class.value -> rdfsClassSubjects.add(subIri)
                        OWL.ObjectProperty.value -> objectPropSubjects.add(subIri)
                        OWL.DatatypeProperty.value -> datatypePropSubjects.add(subIri)
                        OWL.AnnotationProperty.value -> annotationPropSubjects.add(subIri)
                        OWL.Ontology.value -> ontologySubjects.add(subIri)
                        OWL.NamedIndividual.value -> individualSubjects.add(subIri)
                        SKOS.Concept.value -> skos.concepts.add(subIri)
                        SKOS.ConceptScheme.value -> skos.schemes.add(subIri)
                        SKOS.Collection.value -> skos.collectionCount++
                        SKOS.OrderedCollection.value -> skos.orderedCollectionCount++
                    }
                }
                if (objIri == OWL.Restriction.value) restrictionSubjects.add(sk)
            }

            if (t.predicate == RDFS.subClassOf && subIri != null && objIri != null) {
                if (objIri != owlThing) {
                    subclassPairs.add(subIri to objIri)
                }
            }

            if ((t.predicate == RDFS.subClassOf || t.predicate == OWL.equivalentClass) && subIri != null) {
                val o = t.obj
                if (o is BlankNode) classExpressionLinks.add(subIri to o.toString())
            }

            if (t.obj is BlankNode && t.subject is BlankNode) {
                when (p) {
                    OWL_INTERSECTION_OF, OWL_UNION_OF -> operandLists.getOrPut(sk) { mutableListOf() }.add(t.obj.toString())
                    RDF_FIRST -> listFirst[sk] = t.obj.toString()
                    RDF_REST -> listRest[sk] = t.obj.toString()
                }
            }

            if (t.predicate == OWL.onProperty && objIri != null) {
                onPropertyOf.getOrPut(sk) { mutableSetOf() }.add(objIri)
            }

            if ((p == OWL_SOME_VALUES_FROM || p == OWL_ALL_VALUES_FROM || p == OWL_ON_CLASS) && objIri != null) {
                restrictionFillers.getOrPut(sk) { mutableSetOf() }.add(objIri)
            }

            if (t.predicate == RDFS.domain && subIri != null && objIri != null) {
                domainPairs.add(subIri to objIri)
            }

            if (t.predicate == RDFS.range && subIri != null && objIri != null) {
                rangePairs.add(subIri to objIri)
            }

            if (subIri != null &&
                (t.predicate == RDFS.label || t.predicate == RDFS.comment || t.predicate == SKOS.definition)
            ) {
                bumpAnnotation(subIri)
            }

            if (t.predicate == OWL.imports && subIri != null && objIri != null) {
                importsList.add(objIri)
            }

            /// SKOS structural / mapping (concept-dependent coverage metrics resolved in SkosCalculators)
            if (subIri != null && objIri != null) {
                when (t.predicate) {
                    SKOS.broader -> {
                        skos.broaderEdges++
                        skos.parentToNarrowers.getOrPut(objIri) { mutableSetOf() }.add(subIri)
                    }
                    SKOS.narrower -> {
                        skos.narrowerEdges++
                        skos.parentToNarrowers.getOrPut(subIri) { mutableSetOf() }.add(objIri)
                    }
                    SKOS.related -> {
                        skos.relatedEdges++
                        skos.relatedPairs.add(subIri to objIri)
                    }
                    SKOS.broaderTransitive -> skos.broaderTransitiveEdges++
                    SKOS.narrowerTransitive -> skos.narrowerTransitiveEdges++
                    SKOS.exactMatch -> skos.exactMatchEdges++
                    SKOS.closeMatch -> skos.closeMatchEdges++
                    SKOS.broaderMatch -> skos.broadMatchEdges++
                    SKOS.narrowerMatch -> skos.narrowMatchEdges++
                    SKOS.relatedMatch -> skos.relatedMatchEdges++
                    else -> Unit
                }
            }
        }

        val namedClassCandidates = LinkedHashSet<String>()
        namedClassCandidates.addAll(owlClassSubjects)
        namedClassCandidates.addAll(rdfsClassSubjects)
        for ((child, parent) in subclassPairs) {
            if (!excluded(config, child) && child != owlThing && child != owlNothing) namedClassCandidates.add(child)
            if (!excluded(config, parent) && parent != owlThing && parent != owlNothing) namedClassCandidates.add(parent)
        }
        val namedClasses =
            namedClassCandidates
                .filter { !excluded(config, it) && it != owlThing && it != owlNothing }
                .toSet()

        val objectProperties = objectPropSubjects.filter { !excluded(config, it) }.toSet()
        val datatypeProperties = datatypePropSubjects.filter { !excluded(config, it) }.toSet()
        val annotationProperties = annotationPropSubjects.filter { !excluded(config, it) }.toSet()
        val allProperties = objectProperties + datatypeProperties + annotationProperties

        val subChildren = mutableMapOf<String, MutableSet<String>>()
        val superMap = mutableMapOf<String, MutableSet<String>>()
        for ((child, parent) in subclassPairs) {
            if (child !in namedClasses || parent !in namedClasses) continue
            if (parent == owlThing) continue
            subChildren.getOrPut(parent) { mutableSetOf() }.add(child)
            superMap.getOrPut(child) { mutableSetOf() }.add(parent)
        }
        val subClassChildrenOf = subChildren.mapValues { it.value.toSet() }
        val superClassesOf = superMap.mapValues { it.value.toSet() }

        val cycleParticipants = CycleDetector.cycleParticipants(namedClasses, superClassesOf)

        val roots =
            namedClasses
                .filter { r ->
                    r !in cycleParticipants &&
                        superClassesOf[r].orEmpty().none { it in namedClasses && it !in cycleParticipants }
                }
                .toSet()

        val leaves =
            namedClasses
                .filter { l ->
                    l !in cycleParticipants &&
                        subClassChildrenOf[l].orEmpty().none { it in namedClasses && it !in cycleParticipants }
                }
                .toSet()

        /// P_C: declared object/datatype properties used by a named class via rdfs:domain or an owl:Restriction
        /// (attached directly, or as an operand of an owl:intersectionOf / owl:unionOf class expression).
        val usableProperties = objectProperties + datatypeProperties
        val propertiesOfClass = mutableMapOf<String, MutableSet<String>>()
        for ((prop, dom) in domainPairs) {
            if (dom !in namedClasses || prop !in usableProperties) continue
            propertiesOfClass.getOrPut(dom) { mutableSetOf() }.add(prop)
        }
        /// A restriction may sit directly on the class or inside owl:intersectionOf / owl:unionOf lists (defined classes).
        val restrictionsOfClass = mutableListOf<Pair<String, String>>()
        for ((cls, node) in classExpressionLinks) {
            if (cls !in namedClasses) continue
            for (expr in expressionNodes(node, operandLists, listFirst, listRest)) {
                if (expr in onPropertyOf) restrictionsOfClass.add(cls to expr)
            }
        }
        for ((cls, node) in restrictionsOfClass) {
            for (prop in onPropertyOf[node].orEmpty()) {
                if (prop in usableProperties) propertiesOfClass.getOrPut(cls) { mutableSetOf() }.add(prop)
            }
        }

        /// CBOOnto related classes: Sup_C ∪ Assoc_C, where Assoc_C holds the named-class ranges of properties whose
        /// rdfs:domain is C and the named-class fillers of restrictions (on usable properties) attached to C.
        val rangesOf = rangePairs.groupBy({ it.first }, { it.second })
        val couplingsOf = mutableMapOf<String, MutableSet<String>>()
        for ((cls, parents) in superClassesOf) couplingsOf.getOrPut(cls) { mutableSetOf() }.addAll(parents)
        for ((prop, dom) in domainPairs) {
            if (dom !in namedClasses || prop !in usableProperties) continue
            for (range in rangesOf[prop].orEmpty()) {
                if (range in namedClasses && range != dom) couplingsOf.getOrPut(dom) { mutableSetOf() }.add(range)
            }
        }
        for ((cls, node) in restrictionsOfClass) {
            if (onPropertyOf[node].orEmpty().none { it in usableProperties }) continue
            for (filler in restrictionFillers[node].orEmpty()) {
                if (filler in namedClasses && filler != cls) couplingsOf.getOrPut(cls) { mutableSetOf() }.add(filler)
            }
        }

        val dtDomAssertions =
            domainPairs.distinct().count { (prop, dom) -> prop in datatypeProperties && dom in namedClasses }.toLong()

        var annOnClasses = 0L
        for (c in namedClasses) {
            annOnClasses += annotationCounts[c] ?: 0L
        }

        val classesWithInstances = mutableSetOf<String>()
        val instanceAssertions = mutableSetOf<Pair<String, String>>()
        for ((subj, typ) in typeAssertions) {
            if (typ !in namedClasses) continue
            classesWithInstances.add(typ)
            instanceAssertions.add(subj to typ)
        }

        val hierarchy =
            computeHierarchyDp(
                namedClasses = namedClasses,
                cycleParticipants = cycleParticipants,
                superClassesOf = superClassesOf,
                subClassChildrenOf = subClassChildrenOf,
                leaves = leaves,
                maxCap = config.maxDepthCap,
            )

        val iq =
            IntermediateQuantities(
                namedClasses = namedClasses,
                objectProperties = objectProperties,
                datatypeProperties = datatypeProperties,
                annotationProperties = annotationProperties,
                allProperties = allProperties,
                subClassChildrenOf = subClassChildrenOf,
                superClassesOf = superClassesOf,
                leaves = leaves,
                roots = roots,
                cycleParticipants = cycleParticipants,
                propertiesOfClass = propertiesOfClass.mapValues { it.value.toSet() },
                datatypePropertyDomainAssertions = dtDomAssertions,
                annotationAssertionsOnClasses = annOnClasses,
                classesWithInstances = classesWithInstances,
                instanceAssertionCount = instanceAssertions.size.toLong(),
                subClassEdgeCount = superClassesOf.values.sumOf { it.size.toLong() },
                propertyUsageCount = propertiesOfClass.values.sumOf { it.size.toLong() },
                ditDepthOf = hierarchy.depthOf,
                // Double.toLong() saturates at Long.MAX_VALUE.
                pathsFromThingToLeaves = hierarchy.pathCount.toLong(),
                pathCount = hierarchy.pathCount,
                totalPathLength = hierarchy.totalPathLength,
                couplingsOf = couplingsOf.mapValues { it.value.toSet() },
                hierarchyTraversalSteps = hierarchy.steps,
            )

        val distinctImports = importsList.distinct()
        val versioned = distinctImports.count { VERSION_HINT.containsMatchIn(it) }.toLong()
        val importsMetrics =
            ImportsMetrics(
                importStatements = importsList.size.toLong(),
                importedIris = distinctImports.sorted(),
                versionedImports = versioned,
                unversionedImports = distinctImports.size.toLong() - versioned,
            )

        /// Ontology headers — second pass
        val headers = buildOntologyHeaders(graph, ontologySubjects)

        val graphMetrics =
            GraphMetricsSection(
                tripleCount = tripleCount,
                distinctSubjectCount = distinctSubjects.size.toLong(),
                distinctPredicateCount = distinctPredicates.size.toLong(),
                distinctObjectCount = distinctObjects.size.toLong(),
                blankNodeSubjectCount = blankNodeSubjects,
                literalObjectCount = literalObjects,
                iriObjectCount = iriObjects,
                distinctClassesUsed = distinctClassesUsed.size.toLong(),
            )

        val owlEntityCounts =
            OwlEntityCounts(
                owlClasses = owlClassSubjects.count { !excluded(config, it) }.toLong(),
                rdfsClasses = rdfsClassSubjects.count { !excluded(config, it) }.toLong(),
                owlObjectProperties = objectPropSubjects.count { !excluded(config, it) }.toLong(),
                owlDatatypeProperties = datatypePropSubjects.count { !excluded(config, it) }.toLong(),
                owlAnnotationProperties = annotationPropSubjects.count { !excluded(config, it) }.toLong(),
                owlOntologies = ontologySubjects.size.toLong(),
                owlNamedIndividuals = individualSubjects.count { !excluded(config, it) }.toLong(),
                owlRestrictions = restrictionSubjects.size.toLong(),
                totalNamedClasses = namedClasses.size.toLong(),
                totalProperties = allProperties.size.toLong(),
            )

        return ScanBundle(
            graphMetrics = graphMetrics,
            owlEntityCounts = owlEntityCounts,
            intermediate = iq,
            skosScratch = skos,
            imports = importsMetrics,
            ontologyHeaders = headers,
        )
    }

    private fun buildOntologyHeaders(graph: RdfGraph, ontologyIris: Set<String>): List<OntologyHeader> {
        if (ontologyIris.isEmpty()) return emptyList()
        val kinds =
            ontologyIris.associateWith {
                mutableSetOf<String>()
            }
            .toMutableMap()

        for (t in graph.getTriplesSequence()) {
            val s = (t.subject as? Iri)?.value ?: continue
            if (s !in ontologyIris) continue
            val set = kinds[s]!!
            when (t.predicate) {
                RDFS.label -> set.add("label")
                RDFS.comment -> set.add("comment")
                OWL.versionIRI -> set.add("version")
                DCTERMS.creator -> set.add("creator")
                DCTERMS.license -> set.add("license")
                else -> Unit
            }
        }

        return ontologyIris.sorted().map { iri ->
            val ks = kinds[iri].orEmpty()
            OntologyHeader(
                ontologyIri = iri,
                hasLabel = "label" in ks,
                hasComment = "comment" in ks,
                hasVersionIri = "version" in ks,
                hasCreator = "creator" in ks,
                hasLicense = "license" in ks,
            )
        }
    }

    private class HierarchyDp(
        val depthOf: Map<String, Int>,
        val pathCount: Double,
        val totalPathLength: Double,
        val steps: Long,
    )

    /**
     * One iterative pass in topological (Kahn) order over the acyclic part of the named hierarchy;
     * no recursion, so arbitrarily deep chains do not consume the call stack.
     *
     * Paths start at owl:Thing (depth 0), per OQuaRE: every root — including an isolated class with neither
     * superclasses nor subclasses — is a direct child of owl:Thing.
     *
     * - `depth(c)` = 1 for roots, else `max(depth(parent) + 1)`, capped at [maxCap] (the cap bounds the
     *   reported value only, never the traversal). Cycle participants get depth 0 and are excluded.
     * - `paths(c)` = 1 for roots, else `sum(paths(parent))` — memoized, so a lattice with 2^n Thing-to-leaf
     *   paths costs O(V + E) instead of enumerating paths.
     * - `length(c)` = total edge length of all Thing-to-c paths = 1 for roots, else
     *   `sum(length(parent) + paths(parent))`.
     *
     * Counts are doubles: they stay exact up to 2^53 and only overflow to +Infinity beyond ~1e308.
     */
    private fun computeHierarchyDp(
        namedClasses: Set<String>,
        cycleParticipants: Set<String>,
        superClassesOf: Map<String, Set<String>>,
        subClassChildrenOf: Map<String, Set<String>>,
        leaves: Set<String>,
        maxCap: Int,
    ): HierarchyDp {
        fun acyclic(c: String) = c in namedClasses && c !in cycleParticipants

        val remainingParents = HashMap<String, Int>()
        val queue = ArrayDeque<String>()
        val depth = HashMap<String, Int>()
        val paths = HashMap<String, Double>()
        val lengths = HashMap<String, Double>()
        for (c in namedClasses) {
            if (!acyclic(c)) continue
            val parents = superClassesOf[c].orEmpty().count { acyclic(it) }
            remainingParents[c] = parents
            if (parents == 0) {
                queue.addLast(c)
                depth[c] = min(1, maxCap)
                paths[c] = 1.0
                lengths[c] = 1.0
            }
        }
        var steps = 0L
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            steps++
            val d = depth.getValue(c)
            val p = paths.getValue(c)
            val len = lengths.getValue(c)
            for (child in subClassChildrenOf[c].orEmpty()) {
                if (!acyclic(child)) continue
                steps++
                depth[child] = maxOf(depth[child] ?: 0, min(d + 1, maxCap))
                paths[child] = (paths[child] ?: 0.0) + p
                lengths[child] = (lengths[child] ?: 0.0) + len + p
                val left = remainingParents.getValue(child) - 1
                remainingParents[child] = left
                if (left == 0) queue.addLast(child)
            }
        }

        val depthOf = HashMap<String, Int>()
        for (c in namedClasses) depthOf[c] = if (c in cycleParticipants) 0 else depth[c] ?: 0
        var pathCount = 0.0
        var totalLength = 0.0
        for (leaf in leaves) {
            pathCount += paths[leaf] ?: 0.0
            totalLength += lengths[leaf] ?: 0.0
        }
        return HierarchyDp(depthOf, pathCount, totalLength, steps)
    }
}
