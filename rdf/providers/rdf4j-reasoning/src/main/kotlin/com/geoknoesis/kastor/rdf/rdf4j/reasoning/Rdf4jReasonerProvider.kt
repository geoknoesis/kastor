package com.geoknoesis.kastor.rdf.rdf4j.reasoning

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.rdf4j.rdf4jStatementOf
import com.geoknoesis.kastor.rdf.rdf4j.rdfTermFromRdf4j
import com.geoknoesis.kastor.rdf.rdf4j.rdfTriplesFromRdf4j
import com.geoknoesis.kastor.rdf.reasoning.*
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Statement
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
 *
 * **Configuration:**
 * - [ReasonerConfig.enabledRules]: the inferencer always applies the complete RDFS rule set and cannot select
 *   rules, so a configuration that does not enable all four RDFS rule groups is rejected (use the Jena or memory
 *   reasoner for a subset). Non-RDFS rules in the set are ignored, as by every RDFS reasoner.
 * - [ReasonerConfig.timeout] is a wall-clock budget checked before and after the inferencer runs and for every
 *   statement read from the closure. The inferencer's own commit (which computes the closure) cannot be interrupted,
 *   so the timeout only takes effect once it returns; a timed-out call fails with [IllegalStateException].
 * - [ReasonerConfig.materializationThreshold] bounds the number of inferred triples ([IllegalArgumentException]).
 *   It is checked incrementally while the closure is read back from the inferencer, so an oversized closure fails
 *   before it is copied. The closure computed inside the inferencer's commit cannot be bounded by count.
 *
 * **Axiomatic triples:** the inferencer also materialises RDF/RDFS axioms about the vocabulary itself (e.g.
 * `rdf:type rdfs:range rdfs:Class`). For parity with the memory reasoner these are dropped unless
 * `ReasonerConfig.parameters["includeAxiomaticTriples"] == true`. Exactly the axioms are dropped: the statements the
 * inferencer produces for an **empty** store (computed once). Real inferences about vocabulary terms, such as
 * `rdfs:seeAlso rdfs:subPropertyOf ex:link` derived from the data, are kept.
 */
class Rdf4jReasoner internal constructor(
    private val config: ReasonerConfig,
    /** Monotonic clock in nanoseconds; replaceable in tests. */
    private val clock: () -> Long,
) : RdfReasoner {

    constructor(config: ReasonerConfig) : this(config, System::nanoTime)

    init {
        require(config.reasonerType == ReasonerType.RDFS) {
            "RDF4J reasoner only supports ReasonerType.RDFS, got ${config.reasonerType}"
        }
        require(config.timeout.toNanos() > 0) { "ReasonerConfig.timeout must be positive" }
        require(config.materializationThreshold > 0) { "ReasonerConfig.materializationThreshold must be positive" }
        require(config.enabledRules.containsAll(RDFS_RULES)) {
            "RDF4J's SchemaCachingRDFSInferencer applies the complete RDFS rule set and cannot select rules; " +
                "enabledRules must include $RDFS_RULES (got ${config.enabledRules}). Use the Jena or memory reasoner for a rule subset."
        }
    }

    private val includeAxiomatic = config.parameters["includeAxiomaticTriples"] == true

    /** Wall-clock budget of one call; [check] fails with [IllegalStateException] once it is exhausted. */
    private class Budget(timeout: java.time.Duration, private val clock: () -> Long) {
        private val deadline = clock() + timeout.toNanos()
        fun check() {
            check(clock() - deadline < 0 && !Thread.currentThread().isInterrupted) { "RDF4J reasoning timed out or was cancelled" }
        }
    }

    override fun reason(graph: RdfGraph): ReasoningResult {
        val startTime = System.currentTimeMillis()
        val budget = Budget(config.timeout, clock)
        val rdf4jModel = convertToRdf4jModel(graph)
        val infModel = runRdfsInference(rdf4jModel, budget, thresholdAgainst = rdf4jModel)
        val inferredTriples = inferredTriples(rdf4jModel, infModel, budget)
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
        val budget = Budget(config.timeout, clock)
        val model = convertToRdf4jModel(graph)
        return inferredTriples(model, runRdfsInference(model, budget, thresholdAgainst = model), budget)
    }

    override fun classify(graph: RdfGraph): ClassificationResult =
        performClassification(runRdfsInference(convertToRdf4jModel(graph), Budget(config.timeout, clock), thresholdAgainst = null))

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

    /** True when [statement] is reported as inferred: not asserted and (by default) not one of the RDFS axioms. */
    private fun isInferred(statement: Statement, asserted: Model): Boolean =
        statement !in asserted && (includeAxiomatic || statement !in AXIOMS)

    /** Closure statements that are neither asserted nor (by default) axiomatic, bounded by the threshold. */
    private fun inferredTriples(asserted: Model, closure: Model, budget: Budget): List<RdfTriple> {
        val result = ArrayList<RdfTriple>()
        for (statement in closure) {
            budget.check()
            if (!isInferred(statement, asserted)) continue
            result.addAll(rdfTriplesFromRdf4j(statement))
            require(result.size.toLong() <= config.materializationThreshold) {
                "Inferred triples exceed materializationThreshold (${config.materializationThreshold})"
            }
        }
        return result
    }

    /**
     * Materialize RDFS entailments by loading the data into a forward-chaining
     * [SchemaCachingRDFSInferencer]-backed store and reading back the closure
     * (base + inferred statements). With [thresholdAgainst], the inferred statements are counted while reading
     * the closure back, so an oversized closure fails before it is copied.
     */
    private fun runRdfsInference(model: Model, budget: Budget, thresholdAgainst: Model?): Model {
        budget.check()
        val repository = SailRepository(SchemaCachingRDFSInferencer(MemoryStore()))
        repository.init()
        try {
            repository.connection.use { connection ->
                connection.begin()
                connection.add(model)
                connection.commit()
                budget.check()
                val closure = LinkedHashModel()
                var inferred = 0L
                connection.getStatements(null, null, null, true).use { statements ->
                    statements.forEach { statement ->
                        budget.check()
                        if (thresholdAgainst != null && isInferred(statement, thresholdAgainst)) {
                            inferred++
                            require(inferred <= config.materializationThreshold) {
                                "Inferred triples exceed materializationThreshold (${config.materializationThreshold})"
                            }
                        }
                        closure.add(statement)
                    }
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

    private companion object {
        val RDFS_RULES = setOf(ReasoningRule.RDFS_SUBCLASS, ReasoningRule.RDFS_SUBPROPERTY, ReasoningRule.RDFS_DOMAIN, ReasoningRule.RDFS_RANGE)

        /** The statements the inferencer produces for an empty store: the RDF/RDFS axioms. */
        val AXIOMS: Set<Statement> by lazy {
            val repository = SailRepository(SchemaCachingRDFSInferencer(MemoryStore()))
            repository.init()
            try {
                repository.connection.use { connection ->
                    connection.getStatements(null, null, null, true).use { statements -> statements.toHashSet() }
                }
            } finally {
                repository.shutDown()
            }
        }
    }
}
