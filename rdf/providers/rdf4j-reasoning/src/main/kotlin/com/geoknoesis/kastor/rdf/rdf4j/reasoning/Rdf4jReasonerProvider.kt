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
import org.eclipse.rdf4j.repository.RepositoryConnection
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
 * - [ReasonerConfig.timeout] is a wall-clock budget for the whole call. The inferencer computes the closure inside
 *   its `commit()`, which cannot be interrupted, so the load and commit run on a daemon worker thread that the caller
 *   waits for only until the deadline; an inference that misses it is abandoned, finishes in the background, shuts
 *   its store down itself and only then returns its concurrency permit. At most `max(2, availableProcessors)` inferences may run at once: a call that cannot start
 *   one before its deadline fails with a clear "too many RDFS inferences in progress" error instead of piling up
 *   background work (the same pattern as the Jena and HermiT reasoners). After the commit the budget is checked for
 *   every statement read from the closure. A timed-out call fails with [IllegalStateException].
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
    /** Commits the loaded data, which makes the inferencer compute the closure; replaceable in tests. */
    private val commit: (RepositoryConnection) -> Unit,
    /** Limits concurrently running (including abandoned) inferences; replaceable in tests. */
    private val inferences: java.util.concurrent.Semaphore,
) : RdfReasoner {

    constructor(config: ReasonerConfig) : this(config, System::nanoTime)

    internal constructor(config: ReasonerConfig, clock: () -> Long) : this(config, clock, { it.commit() }, INFERENCES)

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
        fun remainingNanos(): Long = deadline - clock()
        fun check() {
            check(remainingNanos() > 0 && !Thread.currentThread().isInterrupted) { TIMEOUT_MESSAGE }
        }
    }

    /** Thrown when an inference is abandoned at the deadline; the worker then owns (and shuts down) the repository. */
    private class InferenceAbandoned : IllegalStateException(TIMEOUT_MESSAGE)

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
        var ownsRepository = true
        try {
            try {
                commitWithinDeadline(repository, model, budget)
            } catch (abandoned: InferenceAbandoned) {
                ownsRepository = false // the inference worker shuts the repository down when it finishes
                throw IllegalStateException(TIMEOUT_MESSAGE, abandoned)
            }
            budget.check()
            repository.connection.use { connection ->
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
            if (ownsRepository) repository.shutDown()
        }
    }

    /**
     * Loads [model] and commits it on a daemon worker (the inferencer computes the closure inside `commit()`, which
     * cannot be interrupted), waiting at most until the budget's deadline. On timeout the inference is abandoned: it
     * keeps its [inferences] permit until it finishes and then shuts [repository] down itself.
     */
    private fun commitWithinDeadline(repository: SailRepository, model: Model, budget: Budget) {
        val acquired = try {
            inferences.tryAcquire(budget.remainingNanos().coerceAtLeast(0), java.util.concurrent.TimeUnit.NANOSECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException(TIMEOUT_MESSAGE, e)
        }
        check(acquired) {
            "RDF4J reasoning timed out: too many RDFS inferences in progress (at most $MAX_IN_FLIGHT_INFERENCES, " +
                "including abandoned ones that are still finishing); retry later or raise ReasonerConfig.timeout"
        }
        val state = java.util.concurrent.atomic.AtomicInteger(RUNNING)
        val outcome = java.util.concurrent.CompletableFuture<Unit>()
        val worker = Thread({
            try {
                repository.connection.use { connection ->
                    connection.begin()
                    connection.add(model)
                    commit(connection)
                }
                outcome.complete(Unit)
            } catch (t: Throwable) {
                outcome.completeExceptionally(t)
            } finally {
                try {
                    if (!state.compareAndSet(RUNNING, FINISHED)) {
                        // Abandoned by a timed-out caller: this worker owns the repository now.
                        try { repository.shutDown() } catch (_: Exception) { }
                    }
                } finally {
                    // Only after cleanup: the permit bounds the work (and memory) still held by inferences.
                    inferences.release()
                }
            }
        }, INFERENCE_THREAD)
        worker.isDaemon = true
        worker.start()
        try {
            // Waits in slices so that the deadline follows the (injectable) budget clock.
            while (true) {
                val remaining = budget.remainingNanos()
                if (remaining <= 0) throw java.util.concurrent.TimeoutException()
                try {
                    outcome.get(minOf(remaining, WAIT_SLICE_NANOS), java.util.concurrent.TimeUnit.NANOSECONDS)
                    return
                } catch (_: java.util.concurrent.TimeoutException) {
                    // re-check the budget
                }
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            abandonOrFail(state, cause = e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            abandonOrFail(state, cause = e)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * Hands the repository to the still-running worker ([InferenceAbandoned]). If the worker finished in the meantime
     * it did not see the abandonment, so the caller keeps ownership and simply reports the timeout.
     */
    private fun abandonOrFail(state: java.util.concurrent.atomic.AtomicInteger, cause: Exception): Nothing {
        if (state.compareAndSet(RUNNING, ABANDONED)) throw InferenceAbandoned().also { it.initCause(cause) }
        throw IllegalStateException(TIMEOUT_MESSAGE, cause)
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
        const val TIMEOUT_MESSAGE = "RDF4J reasoning timed out or was cancelled"

        /** Name of the daemon threads that run the inferencer's (uninterruptible) commit. */
        const val INFERENCE_THREAD = "kastor-rdf4j-inference"

        /** Upper bound on inferences running at once, abandoned ones included. */
        val MAX_IN_FLIGHT_INFERENCES: Int = maxOf(2, Runtime.getRuntime().availableProcessors())

        /** Shared by every [Rdf4jReasoner] of this class loader. */
        val INFERENCES = java.util.concurrent.Semaphore(MAX_IN_FLIGHT_INFERENCES)

        /** Longest single wait for the worker before the budget is re-checked. */
        val WAIT_SLICE_NANOS: Long = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20)

        const val RUNNING = 0
        const val FINISHED = 1
        const val ABANDONED = 2

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
