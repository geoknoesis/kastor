package com.geoknoesis.kastor.rdf.rdf4j.reasoning

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.rdf4j.rdf4jStatementOf
import com.geoknoesis.kastor.rdf.rdf4j.rdfTermFromRdf4j
import com.geoknoesis.kastor.rdf.rdf4j.rdfTripleFromRdf4j
import com.geoknoesis.kastor.rdf.reasoning.*
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.datatypes.XMLDatatypeUtil
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.model.vocabulary.RDFS
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.inferencer.fc.SchemaCachingRDFSInferencer
import org.eclipse.rdf4j.sail.memory.MemoryStore

/**
 * Eclipse RDF4J reasoning: RDFS forward chaining via [SchemaCachingRDFSInferencer].
 *
 * RDF4J ships no OWL reasoner, so only [ReasonerType.RDFS] is supported; every other type is rejected
 * (use the HermiT provider for OWL 2 DL, or the Jena provider for rule-based OWL).
 */
class Rdf4jReasonerProvider : RdfReasonerProvider {

    override fun getType(): String = "rdf4j"

    override val name: String = "Eclipse RDF4J Reasoner"

    override val version: String =
        SailRepository::class.java.`package`?.implementationVersion ?: "5.x"

    /** Preferred over the memory fallback, below Jena (whose RDFS reasoner also validates datatypes). */
    override fun priority(): Int = 40

    override fun createReasoner(config: ReasonerConfig): RdfReasoner {
        require(isSupported(config.reasonerType)) {
            "RDF4J reasoner only supports ReasonerType.RDFS, got ${config.reasonerType}"
        }
        return Rdf4jReasoner(config)
    }

    override fun getCapabilities(): ReasonerCapabilities {
        return ReasonerCapabilities(
            supportedTypes = setOf(ReasonerType.RDFS),
            supportsIncrementalReasoning = false,
            supportsCustomRules = false,
            supportsExplanation = false,
            supportsConsistencyChecking = true,
            supportsClassification = true,
            typicalPerformance = PerformanceProfile.FAST
        )
    }

    override fun getSupportedTypes(): List<ReasonerType> = listOf(ReasonerType.RDFS)

    override fun isSupported(type: ReasonerType): Boolean = type == ReasonerType.RDFS
}

/**
 * RDFS reasoner materialising the closure with RDF4J's [SchemaCachingRDFSInferencer].
 *
 * Consistency: RDFS has no negation, so the only inconsistency is D-unsatisfiability — a literal whose
 * lexical form is invalid for a built-in XSD datatype (checked with RDF4J's [XMLDatatypeUtil]).
 */
class Rdf4jReasoner(private val config: ReasonerConfig) : RdfReasoner {

    init {
        require(config.reasonerType == ReasonerType.RDFS) {
            "RDF4J reasoner only supports ReasonerType.RDFS, got ${config.reasonerType}"
        }
    }

    override fun reason(graph: RdfGraph): ReasoningResult {
        val startTime = System.currentTimeMillis()
        val rdf4jModel = convertToRdf4jModel(graph)
        val infModel = runRdfsInference(rdf4jModel)
        val inferredTriples = infModel.filter { it !in rdf4jModel }.map(::rdfTripleFromRdf4j)
        val consistencyResult = checkConsistency(rdf4jModel)
        val classificationResult = if (config.includeAxioms) performClassification(infModel) else null
        val reasoningTime = java.time.Duration.ofMillis(System.currentTimeMillis() - startTime)

        val statistics = ReasoningStatistics(
            totalTriples = rdf4jModel.size,
            inferredTriples = inferredTriples.size,
            classesProcessed = infModel.filter(null, RDF.TYPE, RDFS.CLASS).size,
            propertiesProcessed = infModel.filter(null, RDF.TYPE, RDF.PROPERTY).size,
            rulesApplied = mapOf("rdf4j" to inferredTriples.size),
            memoryUsage = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() },
            cpuTime = reasoningTime
        )

        return ReasoningResult(
            originalGraph = graph,
            inferredTriples = inferredTriples,
            classification = classificationResult,
            consistencyCheck = consistencyResult,
            reasoningTime = reasoningTime,
            statistics = statistics
        )
    }

    override fun isConsistent(graph: RdfGraph): Boolean = checkConsistency(convertToRdf4jModel(graph)).isConsistent

    override fun getInferredTriples(graph: RdfGraph): List<RdfTriple> {
        val model = convertToRdf4jModel(graph)
        return runRdfsInference(model).filter { it !in model }.map(::rdfTripleFromRdf4j)
    }

    override fun classify(graph: RdfGraph): ClassificationResult =
        performClassification(runRdfsInference(convertToRdf4jModel(graph)))

    override fun validateOntology(graph: RdfGraph): ValidationReport {
        val startTime = System.currentTimeMillis()
        val consistencyResult = checkConsistency(convertToRdf4jModel(graph))
        val violations = consistencyResult.inconsistencies.map { inconsistency ->
            ValidationViolation(
                constraint = inconsistency.type.name,
                resource = inconsistency.affectedResources.firstOrNull() ?: Iri("urn:kastor:unknown"),
                message = inconsistency.description,
                severity = inconsistency.severity
            )
        }
        return ValidationReport(
            isValid = violations.isEmpty(),
            violations = violations,
            warnings = consistencyResult.warnings,
            statistics = ValidationStatistics(
                constraintsChecked = 1,
                violationsFound = violations.size,
                warningsFound = consistencyResult.warnings.size,
                validationTime = java.time.Duration.ofMillis(System.currentTimeMillis() - startTime)
            )
        )
    }

    /**
     * Materialize RDFS entailments by loading the data into a forward-chaining
     * [SchemaCachingRDFSInferencer]-backed store and reading back the closure
     * (base + inferred statements).
     */
    private fun runRdfsInference(model: Model): Model {
        val repository = SailRepository(SchemaCachingRDFSInferencer(MemoryStore()))
        repository.init()
        try {
            repository.connection.use { connection ->
                connection.begin()
                connection.add(model)
                connection.commit()
                val closure = LinkedHashModel()
                connection.getStatements(null, null, null, true).use { statements ->
                    statements.forEach { closure.add(it) }
                }
                return closure
            }
        } finally {
            repository.shutDown()
        }
    }

    private fun checkConsistency(model: Model): ConsistencyResult {
        val inconsistencies = model.mapNotNull { statement ->
            val literal = statement.`object` as? org.eclipse.rdf4j.model.Literal ?: return@mapNotNull null
            if (literal.language.isPresent) return@mapNotNull null
            val datatype = literal.datatype
            if (!XMLDatatypeUtil.isBuiltInDatatype(datatype) || XMLDatatypeUtil.isValidValue(literal.label, datatype)) {
                return@mapNotNull null
            }
            Inconsistency(
                type = InconsistencyType.DOMAIN_RANGE_VIOLATION,
                description = "Ill-typed literal \"${literal.label}\" for datatype ${datatype.stringValue()}",
                affectedResources = listOf(rdfTermFromRdf4j(statement.subject), rdfTermFromRdf4j(literal)),
            )
        }
        return ConsistencyResult(isConsistent = inconsistencies.isEmpty(), inconsistencies = inconsistencies, warnings = emptyList())
    }

    private fun performClassification(model: Model): ClassificationResult {
        fun hierarchy(predicate: IRI): Map<Iri, List<Iri>> {
            val result = linkedMapOf<Iri, MutableList<Iri>>()
            model.filter(null, predicate, null).forEach { statement ->
                val subject = statement.subject as? IRI ?: return@forEach
                val obj = statement.`object` as? IRI ?: return@forEach
                result.getOrPut(Iri(subject.stringValue())) { mutableListOf() }.add(Iri(obj.stringValue()))
            }
            return result
        }
        return ClassificationResult(
            classHierarchy = hierarchy(RDFS.SUBCLASSOF),
            instanceClassifications = hierarchy(RDF.TYPE),
            propertyHierarchy = hierarchy(RDFS.SUBPROPERTYOF),
        )
    }

    private fun convertToRdf4jModel(graph: RdfGraph): Model =
        LinkedHashModel().also { model -> graph.getTriples().forEach { model.add(rdf4jStatementOf(it)) } }
}
