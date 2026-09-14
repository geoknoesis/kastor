package com.geoknoesis.kastor.rdf.hermit

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.reasoning.*
import org.semanticweb.HermiT.ReasonerFactory
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.io.StringDocumentSource
import org.semanticweb.owlapi.model.*
import org.semanticweb.owlapi.model.IRI
import org.semanticweb.owlapi.reasoner.OWLReasoner
import org.semanticweb.owlapi.util.*
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HermiT (OWL 2 DL) reasoning with explicit engine disposal and bounded serialization/materialization.
 *
 * **Timeout:** [ReasonerConfig.timeout] is a wall-clock budget for the whole call, enforced at every stage:
 * - serialization and OWL API loading run on a daemon loader thread that the caller waits for only until the
 *   deadline (OWL API loading cannot be interrupted; an abandoned load finishes in the background and is discarded);
 * - once the deadline passes, a watchdog calls [OWLReasoner.interrupt] **repeatedly** until the reasoner call
 *   returns, because HermiT clears a pending interrupt whenever it starts a new internal task;
 * - every call into the reasoner, including the many per-entity calls made inside OWL API inferred-axiom
 *   generators, first checks the remaining budget, and materialization checks it per axiom.
 *
 * HermiT's own per-task timeout is fixed when the engine is created, so it is set to the budget remaining at
 * that point; the checks above bound every later task. A timed-out call fails with [IllegalStateException].
 *
 * **Inferred triples** are computed at the axiom level: each OWL API inferred-axiom generator runs in
 * turn, axioms already asserted in the input ontology are discarded, and the remaining axioms over named
 * entities are mapped to their RDF triples. This avoids reporting relabelled blank nodes, ontology
 * headers or declaration triples as "inferred". [ReasonerConfig.materializationThreshold] is enforced
 * for every added triple.
 */
class HermitRdfReasoner internal constructor(
    private val config: ReasonerConfig,
    /** Creates the engine; replaceable in tests. */
    private val engineFactory: (OWLOntology, org.semanticweb.HermiT.Configuration) -> OWLReasoner,
) : RdfReasoner {

    constructor(config: ReasonerConfig) : this(config, { ontology, options -> ReasonerFactory().createReasoner(ontology, options) })

    init {
        require(!config.enableIncrementalReasoning && !config.cacheResults && !config.streamingMode) { "HermiT does not support incremental, cached, or streaming reasoning" }
        require(config.materializationThreshold > 0 && config.maxMemoryUsage > 0 && config.timeout.toMillis() > 0)
        require(config.customRules.isEmpty()) { "HermiT custom rules are unsupported" }
    }
    override fun reason(graph: RdfGraph): ReasoningResult = compute(graph, true, config.includeAxioms)
    override fun isConsistent(graph: RdfGraph): Boolean = compute(graph, false, false).consistencyCheck.isConsistent
    override fun getInferredTriples(graph: RdfGraph): List<RdfTriple> = reason(graph).inferredTriples
    override fun classify(graph: RdfGraph): ClassificationResult = compute(graph, false, true).classification
        ?: throw IllegalStateException("Cannot classify an inconsistent ontology")

    private fun compute(graph: RdfGraph, materialize: Boolean, classify: Boolean): ReasoningResult {
        val start = System.nanoTime()
        val deadline = start + config.timeout.toNanos()
        val timedOut = AtomicBoolean(false)
        fun checkBudget() {
            if (timedOut.get() || System.nanoTime() - deadline >= 0) timedOut.set(true)
            check(!timedOut.get() && !Thread.currentThread().isInterrupted) { TIMEOUT_MESSAGE }
        }
        fun remainingMillis() = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
        // Conservative admission estimate; an in-process engine cannot offer a hard per-request JVM heap quota.
        require(graph.size().toLong() <= config.maxMemoryUsage / 512) { "Input exceeds HermiT memory admission budget" }
        val manager = OWLManager.createOWLOntologyManager()
        var reasoner: OWLReasoner? = null
        var watchdog: ScheduledExecutorService? = null
        try {
            val ontology = loadWithinDeadline(graph, manager, deadline, timedOut)
            checkBudget()
            val options = org.semanticweb.HermiT.Configuration().apply { individualTaskTimeout = remainingMillis() }
            val engine = engineFactory(ontology, options)
            reasoner = engine
            checkBudget()
            watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "kastor-hermit-watchdog").apply { isDaemon = true } }
            // Keep interrupting until the call returns: HermiT resets a pending interrupt when a new task starts.
            watchdog.scheduleWithFixedDelay({
                timedOut.set(true)
                engine.interrupt()
            }, remainingMillis(), INTERRUPT_REPEAT_MILLIS, TimeUnit.MILLISECONDS)
            val budgeted = budgeted(engine, ::checkBudget)

            return try {
                val consistent = budgeted.isConsistent
                checkBudget()
                val classification = if (consistent && classify) classification(budgeted, ontology, ::checkBudget) else null
                val inferred = if (consistent && materialize) materialize(budgeted, ontology, manager.owlDataFactory, graph, ::checkBudget) else emptyList()
                checkBudget()
                val elapsed = Duration.ofNanos(System.nanoTime() - start)
                ReasoningResult(graph, inferred, classification,
                    ConsistencyResult(consistent, if (consistent) emptyList() else listOf(Inconsistency(
                        InconsistencyType.CLASS_CONFLICT, "HermiT reports an inconsistent ontology", emptyList(), Severity.ERROR)), emptyList()),
                    elapsed, ReasoningStatistics(graph.size(), inferred.size, ontology.classesInSignature.size,
                        ontology.objectPropertiesInSignature.size + ontology.dataPropertiesInSignature.size,
                        mapOf("hermit" to inferred.size), Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }, elapsed))
            } catch (e: RuntimeException) {
                if (e is IllegalStateException && e.message == TIMEOUT_MESSAGE) throw e
                // HermiT signals interrupt()/task timeouts with OWL API runtime exceptions.
                if (timedOut.get() || e is org.semanticweb.owlapi.reasoner.ReasonerInterruptedException ||
                    e is org.semanticweb.owlapi.reasoner.TimeOutException) {
                    throw IllegalStateException(TIMEOUT_MESSAGE, e)
                }
                throw e
            }
        } finally {
            try {
                watchdog?.shutdownNow()
            } finally {
                try { reasoner?.dispose() } finally { manager.ontologies.toList().forEach { manager.removeOntology(it) } }
            }
        }
    }

    private fun materialize(
        engine: OWLReasoner,
        ontology: OWLOntology,
        factory: OWLDataFactory,
        graph: RdfGraph,
        checkBudget: () -> Unit,
    ): List<RdfTriple> {
        val asserted = graph.getTriples().toHashSet()
        val result = LinkedHashSet<RdfTriple>()
        for (generator in generators()) {
            checkBudget()
            val axioms = generator.createAxioms(factory, engine)
            checkBudget()
            for (axiom in axioms) {
                checkBudget()
                if (ontology.containsAxiomIgnoreAnnotations(axiom, true)) continue
                for (triple in triplesOf(axiom)) {
                    if (triple in asserted || !result.add(triple)) continue
                    require(result.size.toLong() <= config.materializationThreshold) { "Inferred triples exceed materializationThreshold" }
                }
            }
        }
        return result.toList()
    }

    /**
     * Serializes [graph] (on the caller's thread, so an enclosing transaction's view is used) and loads it with
     * the OWL API on a daemon thread, waiting at most until [deadline]. OWL API loading is not interruptible:
     * a load that misses the deadline is abandoned, finishes in the background and is discarded.
     */
    private fun loadWithinDeadline(graph: RdfGraph, manager: OWLOntologyManager, deadline: Long, timedOut: AtomicBoolean): OWLOntology {
        val turtle = graph.serialize(RdfFormat.TURTLE)
        require(turtle.length.toLong() * 2 <= config.maxMemoryUsage / 2) { "Serialized ontology exceeds memory budget" }
        if (System.nanoTime() - deadline >= 0) {
            timedOut.set(true)
            throw IllegalStateException(TIMEOUT_MESSAGE)
        }
        val load = java.util.concurrent.CompletableFuture<OWLOntology>()
        val loader = Thread({
            try {
                load.complete(manager.loadOntologyFromOntologyDocument(StringDocumentSource(turtle, IRI.create("urn:kastor:hermit-input"))))
            } catch (t: Throwable) {
                load.completeExceptionally(t)
            }
        }, "kastor-hermit-loader").apply { isDaemon = true }
        loader.start()
        return try {
            load.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            timedOut.set(true)
            loader.interrupt()
            throw IllegalStateException(TIMEOUT_MESSAGE, e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            loader.interrupt()
            throw IllegalStateException(TIMEOUT_MESSAGE, e)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** [engine] behind a proxy that checks the budget before every call (except `interrupt`/`dispose`). */
    private fun budgeted(engine: OWLReasoner, checkBudget: () -> Unit): OWLReasoner =
        java.lang.reflect.Proxy.newProxyInstance(OWLReasoner::class.java.classLoader, arrayOf(OWLReasoner::class.java)) { _, method, args ->
            if (method.declaringClass == OWLReasoner::class.java && method.name != "interrupt" && method.name != "dispose") checkBudget()
            try {
                method.invoke(engine, *(args ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        } as OWLReasoner

    private fun generators(): List<InferredAxiomGenerator<out OWLAxiom>> = listOf(
        InferredClassAssertionAxiomGenerator(),
        InferredSubClassAxiomGenerator(),
        InferredEquivalentClassAxiomGenerator(),
        InferredSubObjectPropertyAxiomGenerator(),
        InferredEquivalentObjectPropertyAxiomGenerator(),
        InferredInverseObjectPropertiesAxiomGenerator(),
        InferredObjectPropertyCharacteristicAxiomGenerator(),
        InferredSubDataPropertyAxiomGenerator(),
        InferredEquivalentDataPropertiesAxiomGenerator(),
        InferredDataPropertyCharacteristicAxiomGenerator(),
        InferredPropertyAssertionGenerator(),
    )

    /** RDF triples for an inferred axiom over named entities; axioms involving anonymous expressions are skipped. */
    private fun triplesOf(axiom: OWLAxiom): List<RdfTriple> {
        fun iri(entity: OWLNamedObject) = Iri(entity.iri.toString())
        fun namedClass(ce: OWLClassExpression): OWLClass? = if (ce.isOWLClass) ce.asOWLClass() else null
        fun namedObjectProperty(pe: OWLPropertyExpression): OWLObjectProperty? =
            if (pe is OWLObjectProperty && !pe.isOWLTopObjectProperty) pe else null
        fun namedDataProperty(pe: OWLPropertyExpression): OWLDataProperty? =
            if (pe is OWLDataProperty && !pe.isOWLTopDataProperty) pe else null
        fun individual(i: OWLIndividual): Iri? = if (i.isNamed) Iri(i.asOWLNamedIndividual().iri.toString()) else null
        fun typed(subject: OWLNamedObject, type: String) = listOf(RdfTriple(iri(subject), RDF_TYPE, Iri(type)))

        return when (axiom) {
            is OWLClassAssertionAxiom -> {
                val cls = namedClass(axiom.classExpression)?.takeUnless { it.isOWLThing } ?: return emptyList()
                val ind = individual(axiom.individual) ?: return emptyList()
                listOf(RdfTriple(ind, RDF_TYPE, iri(cls)))
            }
            is OWLSubClassOfAxiom -> {
                val sub = namedClass(axiom.subClass)?.takeUnless { it.isOWLNothing } ?: return emptyList()
                val sup = namedClass(axiom.superClass)?.takeUnless { it.isOWLThing } ?: return emptyList()
                if (sub == sup) emptyList() else listOf(RdfTriple(iri(sub), SUB_CLASS_OF, iri(sup)))
            }
            is OWLEquivalentClassesAxiom -> pairs(axiom.namedClasses().toList().filterNot { it.isOWLThing || it.isOWLNothing }) { a, b ->
                RdfTriple(iri(a), EQUIVALENT_CLASS, iri(b))
            }
            is OWLSubObjectPropertyOfAxiom -> {
                val sub = namedObjectProperty(axiom.subProperty) ?: return emptyList()
                val sup = namedObjectProperty(axiom.superProperty) ?: return emptyList()
                if (sub == sup) emptyList() else listOf(RdfTriple(iri(sub), SUB_PROPERTY_OF, iri(sup)))
            }
            is OWLSubDataPropertyOfAxiom -> {
                val sub = namedDataProperty(axiom.subProperty) ?: return emptyList()
                val sup = namedDataProperty(axiom.superProperty) ?: return emptyList()
                if (sub == sup) emptyList() else listOf(RdfTriple(iri(sub), SUB_PROPERTY_OF, iri(sup)))
            }
            is OWLEquivalentObjectPropertiesAxiom -> pairs(axiom.properties().toList().mapNotNull(::namedObjectProperty)) { a, b ->
                RdfTriple(iri(a), EQUIVALENT_PROPERTY, iri(b))
            }
            is OWLEquivalentDataPropertiesAxiom -> pairs(axiom.properties().toList().mapNotNull(::namedDataProperty)) { a, b ->
                RdfTriple(iri(a), EQUIVALENT_PROPERTY, iri(b))
            }
            is OWLInverseObjectPropertiesAxiom -> {
                val first = namedObjectProperty(axiom.firstProperty) ?: return emptyList()
                val second = namedObjectProperty(axiom.secondProperty) ?: return emptyList()
                listOf(RdfTriple(iri(first), INVERSE_OF, iri(second)))
            }
            is OWLFunctionalObjectPropertyAxiom -> namedObjectProperty(axiom.property)?.let { typed(it, OWL + "FunctionalProperty") } ?: emptyList()
            is OWLInverseFunctionalObjectPropertyAxiom -> namedObjectProperty(axiom.property)?.let { typed(it, OWL + "InverseFunctionalProperty") } ?: emptyList()
            is OWLSymmetricObjectPropertyAxiom -> namedObjectProperty(axiom.property)?.let { typed(it, OWL + "SymmetricProperty") } ?: emptyList()
            is OWLAsymmetricObjectPropertyAxiom -> namedObjectProperty(axiom.property)?.let { typed(it, OWL + "AsymmetricProperty") } ?: emptyList()
            is OWLTransitiveObjectPropertyAxiom -> namedObjectProperty(axiom.property)?.let { typed(it, OWL + "TransitiveProperty") } ?: emptyList()
            is OWLReflexiveObjectPropertyAxiom -> namedObjectProperty(axiom.property)?.let { typed(it, OWL + "ReflexiveProperty") } ?: emptyList()
            is OWLIrreflexiveObjectPropertyAxiom -> namedObjectProperty(axiom.property)?.let { typed(it, OWL + "IrreflexiveProperty") } ?: emptyList()
            is OWLFunctionalDataPropertyAxiom -> namedDataProperty(axiom.property)?.let { typed(it, OWL + "FunctionalProperty") } ?: emptyList()
            is OWLObjectPropertyAssertionAxiom -> {
                val s = individual(axiom.subject) ?: return emptyList()
                val p = namedObjectProperty(axiom.property) ?: return emptyList()
                val o = individual(axiom.`object`) ?: return emptyList()
                listOf(RdfTriple(s, iri(p), o))
            }
            is OWLDataPropertyAssertionAxiom -> {
                val s = individual(axiom.subject) ?: return emptyList()
                val p = namedDataProperty(axiom.property) ?: return emptyList()
                val literal = axiom.`object`
                val term: Literal = try {
                    if (literal.hasLang()) LangString(literal.literal, literal.lang)
                    else TypedLiteral(literal.literal, Iri(literal.datatype.iri.toString()))
                } catch (e: IllegalArgumentException) {
                    // Foreign input (e.g. xml:lang="en_US") must not make the whole result unreadable.
                    LOG.warn("Skipping inferred data property value {} of {}: not a valid RDF literal ({})", literal, s, e.message)
                    return emptyList()
                }
                listOf(RdfTriple(s, iri(p), term))
            }
            else -> emptyList()
        }
    }

    private fun <T> pairs(items: List<T>, make: (T, T) -> RdfTriple): List<RdfTriple> =
        items.flatMap { a -> items.filter { it != a }.map { b -> make(a, b) } }

    private fun classification(engine: OWLReasoner, ontology: OWLOntology, checkBudget: () -> Unit): ClassificationResult {
        val classes = ontology.classesInSignature.associate { cls ->
            checkBudget()
            Iri(cls.iri.toString()) to engine.getSuperClasses(cls, false).flattened.filterNot { it.isOWLThing }.map { Iri(it.iri.toString()) }
        }
        val instances = ontology.individualsInSignature.associate { individual ->
            checkBudget()
            Iri(individual.iri.toString()) to engine.getTypes(individual, false).flattened.filterNot { it.isOWLThing }.map { Iri(it.iri.toString()) }
        }
        val properties = ontology.objectPropertiesInSignature.associate { property ->
            checkBudget()
            Iri(property.iri.toString()) to engine.getSuperObjectProperties(property, false).flattened
                .filter { !it.isAnonymous && !it.isOWLTopObjectProperty }.map { Iri(it.asOWLObjectProperty().iri.toString()) }
        } + ontology.dataPropertiesInSignature.associate { property ->
            checkBudget()
            Iri(property.iri.toString()) to engine.getSuperDataProperties(property, false).flattened.filterNot { it.isOWLTopDataProperty }.map { Iri(it.iri.toString()) }
        }
        return ClassificationResult(classes, instances, properties)
    }

    override fun validateOntology(graph: RdfGraph): ValidationReport {
        val r = compute(graph, false, false)
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

    private companion object {
        val LOG: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(HermitRdfReasoner::class.java)
        const val TIMEOUT_MESSAGE = "HermiT reasoning timed out or was cancelled"
        /** Interval at which the watchdog re-issues `interrupt()` after the deadline. */
        const val INTERRUPT_REPEAT_MILLIS = 25L
        const val OWL = "http://www.w3.org/2002/07/owl#"
        val RDF_TYPE = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
        val SUB_CLASS_OF = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
        val SUB_PROPERTY_OF = Iri("http://www.w3.org/2000/01/rdf-schema#subPropertyOf")
        val EQUIVALENT_CLASS = Iri(OWL + "equivalentClass")
        val EQUIVALENT_PROPERTY = Iri(OWL + "equivalentProperty")
        val INVERSE_OF = Iri(OWL + "inverseOf")
    }
}
