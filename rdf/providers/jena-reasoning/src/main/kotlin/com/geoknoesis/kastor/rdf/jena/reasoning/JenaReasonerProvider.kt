package com.geoknoesis.kastor.rdf.jena.reasoning

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.JenaBridge
import com.geoknoesis.kastor.rdf.jena.rdfTermFromJena
import com.geoknoesis.kastor.rdf.reasoning.*
import org.apache.jena.graph.Node
import org.apache.jena.rdf.model.InfModel
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.reasoner.Reasoner
import org.apache.jena.reasoner.ReasonerRegistry as JenaReasonerRegistry
import org.apache.jena.reasoner.ValidityReport
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner
import org.apache.jena.reasoner.rulesys.Rule

/**
 * Apache Jena rule reasoners.
 *
 * | [ReasonerType] | Jena engine |
 * |---|---|
 * | [ReasonerType.RDFS] | `RDFSRuleReasoner` (full RDFS rule set), or a rule reasoner over the selected RDFS rules |
 * | [ReasonerType.OWL_MICRO] | Jena's OWL Micro rule reasoner — fast, incomplete OWL fragment (RDFS plus property axioms, equality and simple class expressions) |
 * | [ReasonerType.OWL_RL] | Jena's OWL forward/backward rule reasoner — a rule-based OWL fragment close to, but not a complete implementation of, OWL 2 RL |
 * | [ReasonerType.CUSTOM] | `GenericRuleReasoner` over [ReasonerConfig.customRules] |
 *
 * Every other type (notably OWL_EL, OWL_QL, OWL_DL) is rejected: Jena has no reasoner for those profiles.
 * Consistency is decided by Jena's `InfModel.validate()`.
 */
class JenaReasonerProvider : RdfReasonerProvider {

    override fun getType(): String = "jena"

    override val name: String = "Apache Jena Reasoner"

    override val version: String = org.apache.jena.Jena.VERSION

    /** Preferred over the dependency-free memory reasoner. */
    override fun priority(): Int = 50

    override fun createReasoner(config: ReasonerConfig): RdfReasoner {
        require(isSupported(config.reasonerType)) {
            "Jena reasoner does not support ${config.reasonerType}; supported types: ${getSupportedTypes()}"
        }
        return JenaReasoner(config)
    }

    override fun getCapabilities(): ReasonerCapabilities {
        return ReasonerCapabilities(
            supportedTypes = getSupportedTypes().toSet(),
            // Each call builds a fresh inference model; there is no incremental maintenance API.
            supportsIncrementalReasoning = false,
            supportsCustomRules = true,
            supportsExplanation = false,
            supportsConsistencyChecking = true,
            supportsClassification = true,
            typicalPerformance = PerformanceProfile.MEDIUM
        )
    }

    override fun getSupportedTypes(): List<ReasonerType> =
        listOf(ReasonerType.RDFS, ReasonerType.OWL_MICRO, ReasonerType.OWL_RL, ReasonerType.CUSTOM)

    override fun isSupported(type: ReasonerType): Boolean {
        return getSupportedTypes().contains(type)
    }
}

/**
 * [RdfReasoner] backed by a Jena [Reasoner].
 *
 * For [ReasonerType.CUSTOM], each [CustomRule] becomes the Jena rule `[name: pattern -> conclusion]`,
 * so `pattern` and `conclusion` use Jena rule syntax, e.g. `(?a <http://ex/p> ?b)`.
 *
 * **Configuration** is honoured like the memory and HermiT reasoners:
 * - [ReasonerConfig.enabledRules] (RDFS): with all four RDFS rule groups the full Jena RDFS reasoner runs;
 *   a subset runs exactly the selected entailment rules (rdfs9/rdfs11 for [ReasoningRule.RDFS_SUBCLASS],
 *   rdfs5/rdfs7 for [ReasoningRule.RDFS_SUBPROPERTY], rdfs2 for [ReasoningRule.RDFS_DOMAIN], rdfs3 for
 *   [ReasoningRule.RDFS_RANGE]), the same rules as the memory reasoner. Jena's OWL reasoners cannot select
 *   rules, so OWL_MICRO / OWL_RL reject any rule set other than the type's default.
 * - [ReasonerConfig.timeout] is a wall-clock budget for the whole call. Jena's rule preparation (`prepare()`, which
 *   runs the forward/RETE rules over the data) and the first use of the prepared model (`validate()`, the first
 *   `hasNext()` of a lazily evaluated closure) cannot be interrupted, so both run on one daemon worker thread that
 *   the caller waits for only until the deadline; work that misses it is abandoned, finishes in the background,
 *   releases its models itself and only then returns its concurrency permit. At most [MAX_ABANDONED_PREPARATIONS] workers may run at once: a call that
 *   cannot start one before its deadline fails with a clear "too many rule preparations in progress" error instead
 *   of piling up background work. If the worker thread cannot be started, the permit is returned at once and the
 *   failure propagates. The budget is also checked for every inferred statement read. A
 *   timed-out call fails with [IllegalStateException].
 * - [ReasonerConfig.materializationThreshold] bounds the number of inferred triples ([IllegalArgumentException]).
 *   It is checked right after preparation against the forward deductions (so an oversized forward closure fails
 *   before being read) and then incrementally for every inferred triple. The forward closure built by preparation
 *   itself cannot be bounded by count; only the timeout limits it.
 *
 * **Axiomatic triples:** the full RDFS and OWL rule sets also entail triples that hold without any data (e.g.
 * `rdf:type rdfs:range rdfs:Class`). For parity with the memory and RDF4J reasoners these are not reported as
 * inferences unless `ReasonerConfig.parameters["includeAxiomaticTriples"] == true`. Dropped is exactly the closure of
 * the **empty** graph:
 * - the triples this reasoner entails from an empty graph (computed once per rule set), and
 * - the members of that closure which Jena materialises only once the data mentions the term they are about, as
 *   defined by [RdfsAxioms.isEntailedByEmptyGraph]: datatype axioms of the recognised datatypes (after
 *   `ex:age rdfs:range xsd:integer`: `xsd:integer a rdfs:Class`, `xsd:integer rdfs:subClassOf rdfs:Resource`, ...)
 *   and reflexive / typing statements about RDF and RDFS vocabulary terms (`rdfs:label rdfs:subPropertyOf
 *   rdfs:label`, `rdf:nil a rdf:List`, `rdf:_1 rdfs:subPropertyOf rdfs:member`), plus their OWL counterparts for the
 *   OWL rule sets.
 *
 * This applies to the rule sets that entail those axioms: the complete RDFS rule set and the OWL rule sets. A
 * **subset of the RDFS rule groups** and custom rules have no axioms, so nothing is dropped there: a triple they
 * derive from the data is reported even when the complete rule set would entail it from the empty graph.
 *
 * Every other inferred triple is reported, **also when all three of its terms are vocabulary terms**: for example
 * `owl:FunctionalProperty rdfs:subClassOf rdf:Property` derived from asserted `rdfs:subClassOf` statements, or
 * `owl:ObjectProperty a rdfs:Class`, follow from the data and are inferences.
 */
class JenaReasoner internal constructor(
    private val config: ReasonerConfig,
    /** Monotonic clock in nanoseconds; replaceable in tests. */
    private val clock: () -> Long,
    /** Runs Jena's rule preparation; replaceable in tests. */
    private val prepare: (InfModel) -> Unit,
    /** Limits concurrently running (including abandoned) preparations; replaceable in tests. */
    private val preparations: java.util.concurrent.Semaphore,
    /** Creates the preparation worker threads; replaceable in tests. */
    private val threads: java.util.concurrent.ThreadFactory = java.util.concurrent.ThreadFactory { task -> Thread(task, PREPARE_THREAD) },
    /** Runs on the worker after the preparation, before the prepared model is used (validated, read); for tests. */
    private val afterPrepare: (InfModel) -> Unit = {},
) : RdfReasoner {

    constructor(config: ReasonerConfig) : this(config, System::nanoTime, { it.prepare() }, PREPARATIONS)

    init {
        require(config.timeout.toNanos() > 0) { "ReasonerConfig.timeout must be positive" }
        require(config.materializationThreshold > 0) { "ReasonerConfig.materializationThreshold must be positive" }
    }

    private val includeAxiomatic = config.parameters["includeAxiomaticTriples"] == true

    private val reasoner: Reasoner = when (config.reasonerType) {
        ReasonerType.RDFS -> rdfsReasoner()
        ReasonerType.OWL_MICRO -> requireDefaultRules().let { JenaReasonerRegistry.getOWLMicroReasoner() }
        ReasonerType.OWL_RL -> requireDefaultRules().let { JenaReasonerRegistry.getOWLReasoner() }
        ReasonerType.CUSTOM -> createCustomReasoner()
        else -> throw IllegalArgumentException(
            "Jena reasoner does not support ${config.reasonerType}; supported types: RDFS, OWL_MICRO, OWL_RL, CUSTOM",
        )
    }

    private fun requireDefaultRules() {
        val defaults = ReasonerConfig(reasonerType = config.reasonerType).enabledRules
        require(config.enabledRules == defaults) {
            "Jena's ${config.reasonerType} reasoner applies its fixed rule set and cannot select rules; " +
                "enabledRules must be the default $defaults (got ${config.enabledRules}). Use ReasonerType.CUSTOM for a custom rule set."
        }
    }

    private fun rdfsReasoner(): Reasoner {
        val selected = config.enabledRules.intersect(RDFS_RULE_GROUPS.keys)
        if (selected == RDFS_RULE_GROUPS.keys) return JenaReasonerRegistry.getRDFSReasoner()
        val source = selected.flatMap { RDFS_RULE_GROUPS.getValue(it) }.joinToString("\n")
        return GenericRuleReasoner(Rule.parseRules(source)).apply { setMode(GenericRuleReasoner.FORWARD_RETE) }
    }

    private fun createCustomReasoner(): Reasoner {
        require(config.customRules.isNotEmpty()) { "ReasonerType.CUSTOM requires at least one ReasonerConfig.customRules entry" }
        val source = config.customRules.joinToString("\n") { rule -> "[${rule.name}: ${rule.pattern} -> ${rule.conclusion}]" }
        val rules = try {
            Rule.parseRules(source)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid Jena rule syntax in custom rules: ${e.message}", e)
        }
        return GenericRuleReasoner(rules)
    }

    /** Wall-clock budget of one call; [check] fails with [IllegalStateException] once it is exhausted. */
    private class Budget(timeout: java.time.Duration, private val clock: () -> Long) {
        private val deadline = clock() + timeout.toNanos()
        fun remainingNanos(): Long = deadline - clock()
        fun check() {
            check(remainingNanos() > 0 && !Thread.currentThread().isInterrupted) { TIMEOUT_MESSAGE }
        }
    }

    /** Thrown when a preparation is abandoned at the deadline; the worker then owns (and closes) the models. */
    private class PreparationAbandoned : IllegalStateException(TIMEOUT_MESSAGE)

    private fun <T> withInference(graph: RdfGraph, block: (Model, InfModel, Budget) -> T): T {
        val budget = Budget(config.timeout, clock)
        val base = JenaBridge.copyToJenaModel(graph)
        var inf: InfModel? = null
        var ownsModels = true
        try {
            budget.check()
            inf = ModelFactory.createInfModel(reasoner, base)
            // Preparation and everything after it run on the worker: validate(), the first hasNext() of a lazily
            // evaluated closure and the like cannot be interrupted either, and only the wait can be bounded.
            return runWithinDeadline(inf, base, budget) {
                prepare(inf)
                afterPrepare(inf)
                budget.check()
                checkForwardDeductions(inf, base)
                block(base, inf, budget)
            }
        } catch (abandoned: PreparationAbandoned) {
            ownsModels = false // the preparation worker closes both models when it finishes
            throw IllegalStateException(TIMEOUT_MESSAGE, abandoned)
        } finally {
            if (ownsModels) {
                try { inf?.close() } finally { base.close() }
            }
        }
    }

    /**
     * Runs [work] (the rule preparation and the use of the prepared model) on a daemon worker and waits at most until
     * the budget's deadline. On timeout the work is abandoned: it keeps its [preparations] permit until it finishes,
     * and then closes [inf] and [base] itself. [work] never returns the models, only plain results.
     */
    private fun <T> runWithinDeadline(inf: InfModel, base: Model, budget: Budget, work: () -> T): T {
        val acquired = try {
            preparations.tryAcquire(budget.remainingNanos().coerceAtLeast(0), java.util.concurrent.TimeUnit.NANOSECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException(TIMEOUT_MESSAGE, e)
        }
        check(acquired) {
            "Jena reasoning timed out: too many rule preparations in progress (at most $MAX_ABANDONED_PREPARATIONS, " +
                "including abandoned ones that are still finishing); retry later or raise ReasonerConfig.timeout"
        }
        val state = java.util.concurrent.atomic.AtomicInteger(RUNNING)
        val outcome = java.util.concurrent.CompletableFuture<T>()
        val body = Runnable {
            try {
                outcome.complete(work())
            } catch (t: Throwable) {
                outcome.completeExceptionally(t)
            } finally {
                try {
                    if (!state.compareAndSet(RUNNING, FINISHED)) {
                        // Abandoned by a timed-out caller: this worker owns the models now.
                        try { inf.close() } catch (_: Exception) { } finally { base.close() }
                    }
                } finally {
                    // Only after cleanup: the permit bounds the work (and memory) still held by preparations.
                    preparations.release()
                }
            }
        }
        try {
            val worker = threads.newThread(body)
            worker.isDaemon = true
            worker.start()
        } catch (t: Throwable) {
            // The worker never ran, so it will never return the permit; the caller still owns (and closes) the models.
            preparations.release()
            throw t
        }
        try {
            // Waits in slices so that the deadline follows the (injectable) budget clock.
            while (true) {
                val remaining = budget.remainingNanos()
                if (remaining <= 0) throw java.util.concurrent.TimeoutException()
                try {
                    return outcome.get(minOf(remaining, WAIT_SLICE_NANOS), java.util.concurrent.TimeUnit.NANOSECONDS)
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
     * Hands the models to the still-running worker ([PreparationAbandoned]). If the worker finished in the meantime
     * it did not see the abandonment, so the caller keeps ownership and simply reports the timeout.
     */
    private fun abandonOrFail(state: java.util.concurrent.atomic.AtomicInteger, cause: Exception): Nothing {
        if (state.compareAndSet(RUNNING, ABANDONED)) throw PreparationAbandoned().also { it.initCause(cause) }
        throw IllegalStateException(TIMEOUT_MESSAGE, cause)
    }

    /**
     * Fails fast when the forward deductions alone prove the inferred triples exceed the threshold: every deduction
     * that is neither asserted nor axiomatic is an inferred triple.
     */
    private fun checkForwardDeductions(inf: InfModel, base: Model) {
        val deductions = (inf.graph as? org.apache.jena.reasoner.InfGraph)?.deductionsGraph?.size()?.toLong() ?: return
        var lowerBound = deductions - base.size() - (if (includeAxiomatic) 0 else axiomatic.size)
        if (lowerBound > config.materializationThreshold && !includeAxiomatic) {
            // Lazily materialised axioms are not reported either; count them only when the bound is about to fail.
            val iterator = (inf.graph as org.apache.jena.reasoner.InfGraph).deductionsGraph.find()
            try {
                while (iterator.hasNext()) {
                    val triple = iterator.next()
                    if (isLazyAxiom(triple) && triple !in axiomatic) lowerBound--
                }
            } finally {
                iterator.close()
            }
        }
        require(lowerBound <= config.materializationThreshold) {
            "Inferred triples exceed materializationThreshold (${config.materializationThreshold}): " +
                "the forward closure alone adds at least $lowerBound triples"
        }
    }

    /** Triples this reasoner entails from an empty graph (the RDF/RDFS/OWL axioms of its rule set). */
    private val axiomatic: Set<org.apache.jena.graph.Triple> by lazy {
        AXIOMS.computeIfAbsent(axiomKey()) {
            val empty = ModelFactory.createDefaultModel()
            val closure = ModelFactory.createInfModel(reasoner, empty)
            try {
                val iterator = closure.graph.find()
                try { iterator.toSet() } finally { iterator.close() }
            } finally {
                closure.close()
                empty.close()
            }
        }
    }

    private fun axiomKey(): String = when (config.reasonerType) {
        ReasonerType.CUSTOM -> "CUSTOM:" + config.customRules.joinToString("\n") { "${it.name}|${it.pattern}|${it.conclusion}" }
        ReasonerType.RDFS -> "RDFS:" + config.enabledRules.intersect(RDFS_RULE_GROUPS.keys).map { it.name }.sorted()
        else -> config.reasonerType.name
    }

    override fun reason(graph: RdfGraph): ReasoningResult {
        val startTime = System.currentTimeMillis()
        return withInference(graph) { base, infModel, budget ->
            val consistencyResult = consistencyOf(infModel.validate())
            budget.check()
            val inferredTriples = extractInferredTriples(base, infModel, budget)
            val classificationResult = if (config.includeAxioms) performClassification(infModel, budget) else null
            val reasoningTime = java.time.Duration.ofMillis(System.currentTimeMillis() - startTime)
            ReasoningResult(
                originalGraph = graph,
                inferredTriples = inferredTriples,
                classification = classificationResult,
                consistencyCheck = consistencyResult,
                reasoningTime = reasoningTime,
                statistics = ReasoningStatistics(
                    totalTriples = Math.toIntExact(base.size()),
                    inferredTriples = inferredTriples.size,
                    classesProcessed = countTyped(infModel, RDFS_CLASS),
                    propertiesProcessed = countTyped(infModel, RDF_PROPERTY),
                    rulesApplied = mapOf("total" to inferredTriples.size),
                    memoryUsage = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() },
                    cpuTime = reasoningTime
                )
            )
        }
    }

    override fun isConsistent(graph: RdfGraph): Boolean = withInference(graph) { _, inf, _ -> inf.validate().isValid }

    override fun getInferredTriples(graph: RdfGraph): List<RdfTriple> =
        withInference(graph) { base, inf, budget -> extractInferredTriples(base, inf, budget) }

    override fun classify(graph: RdfGraph): ClassificationResult =
        withInference(graph) { _, inf, budget -> performClassification(inf, budget) }

    override fun validateOntology(graph: RdfGraph): ValidationReport {
        val startTime = System.currentTimeMillis()
        val consistency = withInference(graph) { _, inf, _ -> consistencyOf(inf.validate()) }
        val violations = consistency.inconsistencies.map { inconsistency ->
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
            warnings = consistency.warnings,
            statistics = ValidationStatistics(
                constraintsChecked = 1,
                violationsFound = violations.size,
                warningsFound = consistency.warnings.size,
                validationTime = java.time.Duration.ofMillis(System.currentTimeMillis() - startTime)
            )
        )
    }

    private fun consistencyOf(report: ValidityReport): ConsistencyResult {
        val inconsistencies = mutableListOf<Inconsistency>()
        val warnings = mutableListOf<String>()
        report.reports.forEachRemaining { r ->
            val text = listOfNotNull(r.type, r.description).joinToString(": ")
            if (r.isError) {
                inconsistencies.add(Inconsistency(inconsistencyType(text), text, affected(r.extension), Severity.ERROR))
            } else {
                warnings.add(text)
            }
        }
        return ConsistencyResult(isConsistent = report.isValid, inconsistencies = inconsistencies, warnings = warnings)
    }

    private fun inconsistencyType(text: String): InconsistencyType {
        val t = text.lowercase()
        return when {
            "disjoint" in t -> InconsistencyType.DISJOINTNESS_VIOLATION
            "functional" in t -> InconsistencyType.FUNCTIONAL_PROPERTY_VIOLATION
            "cardinality" in t || "maxcard" in t -> InconsistencyType.CARDINALITY_VIOLATION
            "range" in t || "domain" in t -> InconsistencyType.DOMAIN_RANGE_VIOLATION
            else -> InconsistencyType.CLASS_CONFLICT
        }
    }

    private fun affected(extension: Any?): List<RdfTerm> {
        val scratch = ModelFactory.createDefaultModel()
        return try {
            fun convert(node: Node): RdfTerm? = runCatching { rdfTermFromJena(scratch.asRDFNode(node)) }.getOrNull()
            when (extension) {
                is Node -> listOfNotNull(convert(extension))
                is org.apache.jena.graph.Triple -> listOfNotNull(convert(extension.subject), convert(extension.`object`))
                else -> emptyList()
            }
        } finally {
            scratch.close()
        }
    }

    /** Statements in the inference closure that are not asserted, not axiomatic, and representable as RDF 1.2 triples. */
    private fun extractInferredTriples(base: Model, infModel: Model, budget: Budget): List<RdfTriple> {
        val asserted = base.graph
        val result = ArrayList<RdfTriple>()
        val iterator = infModel.graph.find()
        try {
            while (iterator.hasNext()) {
                budget.check()
                val triple = iterator.next()
                if (!(triple.subject.isURI || triple.subject.isBlank) || !triple.predicate.isURI || asserted.contains(triple)) continue
                if (!includeAxiomatic && (triple in axiomatic || isLazyAxiom(triple))) continue
                result.add(
                    RdfTriple(
                        rdfTermFromJena(infModel.asRDFNode(triple.subject)) as RdfResource,
                        Iri(triple.predicate.uri),
                        rdfTermFromJena(infModel.asRDFNode(triple.`object`)),
                    ),
                )
                require(result.size.toLong() <= config.materializationThreshold) {
                    "Inferred triples exceed materializationThreshold (${config.materializationThreshold})"
                }
            }
        } finally {
            iterator.close()
        }
        return result
    }

    private fun performClassification(model: Model, budget: Budget): ClassificationResult {
        fun hierarchy(predicate: String): Map<Iri, List<Iri>> {
            val result = linkedMapOf<Iri, MutableList<Iri>>()
            val iterator = model.graph.find(Node.ANY, org.apache.jena.graph.NodeFactory.createURI(predicate), Node.ANY)
            try {
                while (iterator.hasNext()) {
                    budget.check()
                    val t = iterator.next()
                    if (t.subject.isURI && t.`object`.isURI) result.getOrPut(Iri(t.subject.uri)) { mutableListOf() }.add(Iri(t.`object`.uri))
                }
            } finally {
                iterator.close()
            }
            return result
        }
        return ClassificationResult(
            classHierarchy = hierarchy(SUB_CLASS_OF),
            instanceClassifications = hierarchy(RDF_TYPE),
            propertyHierarchy = hierarchy(SUB_PROPERTY_OF),
        )
    }

    /** OWL rule sets also derive the OWL counterparts of the lazily materialised RDFS axioms. */
    private val owlRules = config.reasonerType == ReasonerType.OWL_MICRO || config.reasonerType == ReasonerType.OWL_RL

    /**
     * Whether the rule set in use entails the RDFS axioms at all: Jena's complete RDFS reasoner and its OWL reasoners
     * do. A selection of RDFS rule groups (run as plain entailment rules, like the memory reasoner) and custom rules
     * have no axioms: every triple they derive follows from the data, also one that the complete rule set would
     * entail from the empty graph (e.g. `rdf:Bag rdfs:subClassOf rdfs:Resource` derived by rdfs11 from two asserted
     * statements).
     */
    private val entailsRdfsAxioms: Boolean = when (config.reasonerType) {
        ReasonerType.RDFS -> config.enabledRules.containsAll(RDFS_RULE_GROUPS.keys)
        ReasonerType.OWL_MICRO, ReasonerType.OWL_RL -> true
        else -> false
    }

    /**
     * True when [triple] belongs to the closure of the empty graph although Jena derives it only once the data
     * mentions its subject (see [RdfsAxioms.isEntailedByEmptyGraph]). Only rule sets that entail the RDFS axioms have
     * such triples (see [entailsRdfsAxioms]).
     */
    private fun isLazyAxiom(triple: org.apache.jena.graph.Triple): Boolean =
        entailsRdfsAxioms && triple.subject.isURI && triple.predicate.isURI && triple.`object`.isURI &&
            RdfsAxioms.isEntailedByEmptyGraph(triple.subject.uri, triple.predicate.uri, triple.`object`.uri, owlRules)

    private fun countTyped(model: Model, type: String): Int =
        model.listResourcesWithProperty(model.createProperty(RDF_TYPE), model.createResource(type)).toList().size

    private companion object {
        init {
            // Jena's reasoner registry must not be the first Jena class touched: without an explicit
            // JenaSystem.init() its static initialisation cycles through NodeFactory and fails.
            org.apache.jena.sys.JenaSystem.init()
        }

        const val RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
        const val RDF_PROPERTY = "http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"
        const val RDFS_CLASS = "http://www.w3.org/2000/01/rdf-schema#Class"
        const val SUB_CLASS_OF = "http://www.w3.org/2000/01/rdf-schema#subClassOf"
        const val SUB_PROPERTY_OF = "http://www.w3.org/2000/01/rdf-schema#subPropertyOf"

        const val TIMEOUT_MESSAGE = "Jena reasoning timed out or was cancelled"

        /** Longest single wait for a preparation before the budget is re-checked. */
        val WAIT_SLICE_NANOS: Long = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20)

        /** Name of the daemon threads that run Jena's (uninterruptible) rule preparation. */
        const val PREPARE_THREAD = "kastor-jena-prepare"

        /** Upper bound on rule preparations running at once, abandoned ones included. */
        val MAX_ABANDONED_PREPARATIONS: Int = maxOf(2, Runtime.getRuntime().availableProcessors())

        /** Shared by every [JenaReasoner] of this class loader. */
        val PREPARATIONS = java.util.concurrent.Semaphore(MAX_ABANDONED_PREPARATIONS)

        const val RUNNING = 0
        const val FINISHED = 1
        const val ABANDONED = 2

        /** Axioms (closure of the empty graph) per rule set, computed once. */
        val AXIOMS = java.util.concurrent.ConcurrentHashMap<String, Set<org.apache.jena.graph.Triple>>()

        /** RDFS entailment rules (RDF 1.1 Semantics) per rule group, identical to the memory reasoner. */
        val RDFS_RULE_GROUPS: Map<ReasoningRule, List<String>> = mapOf(
            ReasoningRule.RDFS_SUBCLASS to listOf(
                "[rdfs9: (?x rdf:type ?a), (?a rdfs:subClassOf ?b) -> (?x rdf:type ?b)]",
                "[rdfs11: (?a rdfs:subClassOf ?b), (?b rdfs:subClassOf ?c) -> (?a rdfs:subClassOf ?c)]",
            ),
            ReasoningRule.RDFS_SUBPROPERTY to listOf(
                "[rdfs5: (?a rdfs:subPropertyOf ?b), (?b rdfs:subPropertyOf ?c) -> (?a rdfs:subPropertyOf ?c)]",
                "[rdfs7: (?x ?a ?y), (?a rdfs:subPropertyOf ?b) -> (?x ?b ?y)]",
            ),
            ReasoningRule.RDFS_DOMAIN to listOf(
                "[rdfs2: (?x ?p ?y), (?p rdfs:domain ?c) -> (?x rdf:type ?c)]",
            ),
            ReasoningRule.RDFS_RANGE to listOf(
                "[rdfs3: (?x ?p ?y), (?p rdfs:range ?c), notLiteral(?y) -> (?y rdf:type ?c)]",
            ),
        )
    }
}
