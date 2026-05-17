package com.geoknoesis.kastor.rdf.hermit

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.serialize
import com.geoknoesis.kastor.rdf.reasoning.ClassificationResult
import com.geoknoesis.kastor.rdf.reasoning.ConsistencyResult
import com.geoknoesis.kastor.rdf.reasoning.Inconsistency
import com.geoknoesis.kastor.rdf.reasoning.InconsistencyType
import com.geoknoesis.kastor.rdf.reasoning.ReasonerConfig
import com.geoknoesis.kastor.rdf.reasoning.ReasoningResult
import com.geoknoesis.kastor.rdf.reasoning.ReasoningStatistics
import com.geoknoesis.kastor.rdf.reasoning.RdfReasoner
import com.geoknoesis.kastor.rdf.reasoning.Severity
import com.geoknoesis.kastor.rdf.reasoning.ValidationReport
import com.geoknoesis.kastor.rdf.reasoning.ValidationStatistics
import com.geoknoesis.kastor.rdf.reasoning.ValidationViolation
import org.semanticweb.HermiT.ReasonerFactory
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat
import org.semanticweb.owlapi.io.StringDocumentSource
import org.semanticweb.owlapi.io.StringDocumentTarget
import org.semanticweb.owlapi.model.IRI
import org.semanticweb.owlapi.reasoner.InferenceType
import org.semanticweb.owlapi.reasoner.OWLReasoner
import org.semanticweb.owlapi.util.InferredOntologyGenerator
import java.time.Duration

/**
 * HermiT-backed [RdfReasoner]: load RDF as an **OWL 2** ontology (DL where axioms fall in DL),
 * run HermiT, export inferred axioms as RDF/XML, diff against input.
 */
class HermitRdfReasoner(
    private val config: ReasonerConfig,
) : RdfReasoner {

    override fun reason(graph: RdfGraph): ReasoningResult {
        val start = System.nanoTime()
        if (graph.getTriples().isEmpty()) {
            return emptyResult(graph, start)
        }

        val turtle = graph.serialize(RdfFormat.TURTLE)
        val manager = OWLManager.createOWLOntologyManager()
        val ontology =
            manager.loadOntologyFromOntologyDocument(
                StringDocumentSource(turtle, IRI.create("urn:kastor:hermit-input")),
            )

        val factory = ReasonerFactory()
        val reasoner = factory.createReasoner(ontology)
        val consistent = reasoner.isConsistent
        if (!consistent) {
            val elapsed = durationSince(start)
            return ReasoningResult(
                originalGraph = graph,
                inferredTriples = emptyList(),
                classification = null,
                consistencyCheck =
                    ConsistencyResult(
                        isConsistent = false,
                        inconsistencies =
                            listOf(
                                Inconsistency(
                                    type = InconsistencyType.CLASS_CONFLICT,
                                    description = "HermiT reports the ontology is inconsistent.",
                                    affectedResources = emptyList(),
                                    severity = Severity.ERROR,
                                ),
                            ),
                        warnings = emptyList(),
                    ),
                reasoningTime = elapsed,
                statistics =
                    ReasoningStatistics(
                        totalTriples = graph.getTriples().size,
                        inferredTriples = 0,
                        classesProcessed = 0,
                        propertiesProcessed = 0,
                        rulesApplied = emptyMap(),
                        memoryUsage = usedMemory(),
                        cpuTime = elapsed,
                    ),
            )
        }

        prepareForMaterialization(reasoner)

        val inferredOnt = manager.createOntology()
        val generator = InferredOntologyGenerator(reasoner)
        generator.fillOntology(manager.owlDataFactory, inferredOnt)

        val target = StringDocumentTarget()
        manager.saveOntology(inferredOnt, RDFXMLDocumentFormat(), target)
        val inferredXml = target.toString()

        val inferredGraph =
            try {
                Rdf.parse(inferredXml, RdfFormat.RDF_XML)
            } catch (e: Exception) {
                throw IllegalStateException("Failed to parse HermiT inferred ontology as RDF/XML: ${e.message}", e)
            }

        val originalSet = graph.getTriples().toSet()
        val inferredTriples =
            inferredGraph
                .getTriples()
                .filter { it !in originalSet }
                .let { list ->
                    val max = config.materializationThreshold
                    require(list.size <= max) {
                        "Inferred triples (${list.size}) exceed materializationThreshold ($max); increase ReasonerConfig.materializationThreshold or narrow the graph."
                    }
                    list
                }

        val elapsed = durationSince(start)
        return ReasoningResult(
            originalGraph = graph,
            inferredTriples = inferredTriples,
            classification = if (config.includeAxioms) emptyClassification() else null,
            consistencyCheck =
                ConsistencyResult(
                    isConsistent = true,
                    inconsistencies = emptyList(),
                    warnings = emptyList(),
                ),
            reasoningTime = elapsed,
            statistics =
                ReasoningStatistics(
                    totalTriples = graph.getTriples().size,
                    inferredTriples = inferredTriples.size,
                    classesProcessed = 0,
                    propertiesProcessed = 0,
                    rulesApplied = mapOf("hermit" to inferredTriples.size),
                    memoryUsage = usedMemory(),
                    cpuTime = elapsed,
                ),
        )
    }

    private fun emptyClassification(): ClassificationResult =
        ClassificationResult(
            classHierarchy = emptyMap(),
            instanceClassifications = emptyMap(),
            propertyHierarchy = emptyMap(),
        )

    private fun emptyResult(graph: RdfGraph, start: Long): ReasoningResult {
        val elapsed = durationSince(start)
        return ReasoningResult(
            originalGraph = graph,
            inferredTriples = emptyList(),
            classification = null,
            consistencyCheck =
                ConsistencyResult(isConsistent = true, inconsistencies = emptyList(), warnings = emptyList()),
            reasoningTime = elapsed,
            statistics =
                ReasoningStatistics(
                    totalTriples = graph.getTriples().size,
                    inferredTriples = 0,
                    classesProcessed = 0,
                    propertiesProcessed = 0,
                    rulesApplied = emptyMap(),
                    memoryUsage = usedMemory(),
                    cpuTime = elapsed,
                ),
        )
    }

    override fun isConsistent(graph: RdfGraph): Boolean {
        val r = reason(graph)
        return r.consistencyCheck.isConsistent
    }

    override fun getInferredTriples(graph: RdfGraph): List<RdfTriple> = reason(graph).inferredTriples

    override fun classify(graph: RdfGraph): ClassificationResult =
        reason(graph).classification ?: emptyClassification()

    override fun validateOntology(graph: RdfGraph): ValidationReport {
        val r = reason(graph)
        val violations = mutableListOf<ValidationViolation>()
        if (!r.consistencyCheck.isConsistent) {
            for (inc in r.consistencyCheck.inconsistencies) {
                violations.add(
                    ValidationViolation(
                        constraint = inc.type.name,
                        resource = inc.affectedResources.firstOrNull() ?: Iri("urn:kastor:unknown"),
                        message = inc.description,
                        severity = Severity.ERROR,
                    ),
                )
            }
        }
        return ValidationReport(
            isValid = violations.isEmpty(),
            violations = violations,
            warnings = r.consistencyCheck.warnings,
            statistics =
                ValidationStatistics(
                    constraintsChecked = 1,
                    violationsFound = violations.size,
                    warningsFound = r.consistencyCheck.warnings.size,
                    validationTime = r.reasoningTime,
                ),
        )
    }

    private fun durationSince(startNanos: Long): Duration =
        Duration.ofNanos(System.nanoTime() - startNanos)

    private fun usedMemory(): Long =
        Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    /**
     * Precompute standard DL inference types so [InferredOntologyGenerator] runs against warm caches.
     * Unsupported types are ignored by the reasoner (OWL API contract).
     */
    private fun prepareForMaterialization(reasoner: OWLReasoner) {
        reasoner.precomputeInferences(
            InferenceType.DISJOINT_CLASSES,
            InferenceType.CLASS_HIERARCHY,
            InferenceType.OBJECT_PROPERTY_HIERARCHY,
            InferenceType.DATA_PROPERTY_HIERARCHY,
            InferenceType.CLASS_ASSERTIONS,
            InferenceType.OBJECT_PROPERTY_ASSERTIONS,
            InferenceType.DATA_PROPERTY_ASSERTIONS,
            InferenceType.SAME_INDIVIDUAL,
            InferenceType.DIFFERENT_INDIVIDUALS,
        )
    }
}
