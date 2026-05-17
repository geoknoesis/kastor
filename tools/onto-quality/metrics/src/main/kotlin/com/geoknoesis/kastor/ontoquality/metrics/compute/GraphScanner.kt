package com.geoknoesis.kastor.ontoquality.metrics.compute

import com.geoknoesis.kastor.ontoquality.metrics.GraphMetricsSection
import com.geoknoesis.kastor.ontoquality.metrics.ImportsMetrics
import com.geoknoesis.kastor.ontoquality.metrics.MetricsConfig
import com.geoknoesis.kastor.ontoquality.metrics.OntologyHeader
import com.geoknoesis.kastor.ontoquality.metrics.OwlEntityCounts
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
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
)

internal object GraphScanner {
    private val VERSION_HINT = Regex("\\d+\\.\\d+")

    private fun subjectKey(s: RdfResource): String =
        when (s) {
            is Iri -> s.value
            is BlankNode -> "_:${s.id}"
        }

    private fun excluded(cfg: MetricsConfig, iri: String): Boolean = cfg.excludedNamespaces.any { iri.startsWith(it) }

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
        var nonSubclassIriEdges = 0L
        var subclassEdgeCount = 0L

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
                    distinctObjects.add("\"${o.lexical}\"")
                }
                is BlankNode -> distinctObjects.add("_:${o.id}")
                else -> Unit // TripleTerm, Var, etc.
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
                        OWL.Restriction.value -> restrictionSubjects.add(subjectKey(t.subject))
                        SKOS.Concept.value -> skos.concepts.add(subIri)
                        SKOS.ConceptScheme.value -> skos.schemes.add(subIri)
                        SKOS.Collection.value -> skos.collectionCount++
                        SKOS.OrderedCollection.value -> skos.orderedCollectionCount++
                    }
                }
            }

            if (t.predicate == RDFS.subClassOf && subIri != null && objIri != null) {
                if (objIri != owlThing) {
                    subclassPairs.add(subIri to objIri)
                    subclassEdgeCount++
                }
            }

            if (subIri != null && objIri != null && t.predicate != RDFS.subClassOf) {
                nonSubclassIriEdges++
            }

            if (t.predicate == RDFS.domain && subIri != null && objIri != null) {
                domainPairs.add(subIri to objIri)
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
                    SKOS.related -> skos.relatedEdges++
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

        val successors = mutableMapOf<String, MutableSet<String>>()
        for ((ch, pars) in superClassesOf) {
            for (pa in pars) {
                successors.getOrPut(ch) { mutableSetOf() }.add(pa)
            }
        }
        val cycleParticipants = CycleDetector.cycleParticipants(namedClasses, successors)

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

        val propertiesByDomain = mutableMapOf<String, MutableSet<String>>()
        for ((prop, dom) in domainPairs) {
            if (dom !in namedClasses) continue
            if (prop !in allProperties) continue
            propertiesByDomain.getOrPut(dom) { mutableSetOf() }.add(prop)
        }

        var dtDomAssertions = 0L
        for ((prop, _) in domainPairs) {
            if (prop in datatypeProperties) dtDomAssertions++
        }

        var annOnClasses = 0L
        for (c in namedClasses) {
            annOnClasses += annotationCounts[c] ?: 0L
        }

        val classesWithInstances = mutableSetOf<String>()
        for ((subj, typ) in typeAssertions) {
            if (typ !in namedClasses) continue
            /// instance if subject is distinct from type (allows punning); counts class used as object of type
            classesWithInstances.add(typ)
        }

        val (ditDepthOf, _) =
            computeDitDepths(
                namedClasses = namedClasses,
                cycleParticipants = cycleParticipants,
                superClassesOf = superClassesOf,
                owlThing = owlThing,
                maxCap = config.maxDepthCap,
            )

        val filteredChildren =
            subClassChildrenOf
                .mapValues { (_, ch) ->
                    ch.filter { it in namedClasses && it !in cycleParticipants }.toSet()
                }
                .filterKeys { it !in cycleParticipants }

        var paths = 0L
        for (r in roots) {
            paths += countPaths(r, filteredChildren, namedClasses, cycleParticipants)
        }

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
                propertiesByDomain = propertiesByDomain.mapValues { it.value.toSet() },
                datatypePropertyDomainAssertions = dtDomAssertions,
                annotationAssertionsOnClasses = annOnClasses,
                classesWithInstances = classesWithInstances,
                subClassEdgeCount = subclassEdgeCount,
                nonSubClassEdgeCount = nonSubclassIriEdges,
                ditDepthOf = ditDepthOf,
                pathsFromThingToLeaves = paths,
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

    private fun computeDitDepths(
        namedClasses: Set<String>,
        cycleParticipants: Set<String>,
        superClassesOf: Map<String, Set<String>>,
        owlThing: String,
        maxCap: Int,
    ): Pair<Map<String, Int>, Boolean> {
        val memo = mutableMapOf<String, Int>()
        val visiting = mutableSetOf<String>()
        var capHit = false

        fun depthOf(c: String): Int {
            if (c in cycleParticipants) return 0
            memo[c]?.let { return it }
            if (c in visiting) return 0
            visiting.add(c)
            val parents =
                superClassesOf[c].orEmpty().filter {
                    it in namedClasses && it != owlThing && it !in cycleParticipants
                }
            val raw =
                if (parents.isEmpty()) {
                    0
                } else {
                    parents.maxOf { depthOf(it) + 1 }
                }
            visiting.remove(c)
            if (raw >= maxCap) capHit = true
            val d = min(raw, maxCap)
            memo[c] = d
            return d
        }

        for (c in namedClasses) depthOf(c)
        return memo to capHit
    }

    private fun countPaths(
        node: String,
        filteredChildren: Map<String, Set<String>>,
        namedClasses: Set<String>,
        cycleParticipants: Set<String>,
    ): Long {
        if (node in cycleParticipants) return 0L
        val children =
            filteredChildren[node].orEmpty().filter {
                it in namedClasses && it !in cycleParticipants
            }
        if (children.isEmpty()) return 1L
        return children.sumOf { countPaths(it, filteredChildren, namedClasses, cycleParticipants) }
    }
}
