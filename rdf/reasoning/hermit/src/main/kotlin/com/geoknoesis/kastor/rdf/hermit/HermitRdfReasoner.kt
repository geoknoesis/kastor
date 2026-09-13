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
 * **Timeout:** [ReasonerConfig.timeout] is a wall-clock budget for the whole call. A watchdog calls
 * [OWLReasoner.interrupt] when the deadline passes, so long-running consistency checks, classification
 * and materialization stop instead of running on; the call then fails with [IllegalStateException].
 *
 * **Inferred triples** are computed at the axiom level: each OWL API inferred-axiom generator runs in
 * turn, axioms already asserted in the input ontology are discarded, and the remaining axioms over named
 * entities are mapped to their RDF triples. This avoids reporting relabelled blank nodes, ontology
 * headers or declaration triples as "inferred". [ReasonerConfig.materializationThreshold] is enforced
 * after every generator.
 */
class HermitRdfReasoner(private val config: ReasonerConfig) : RdfReasoner {
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
        // Conservative admission estimate; an in-process engine cannot offer a hard per-request JVM heap quota.
        require(graph.size().toLong() <= config.maxMemoryUsage / 512) { "Input exceeds HermiT memory admission budget" }
        val manager = OWLManager.createOWLOntologyManager()
        var reasoner: OWLReasoner? = null
        var watchdog: ScheduledExecutorService? = null
        try {
            val turtle = graph.serialize(RdfFormat.TURTLE)
            require(turtle.length.toLong() * 2 <= config.maxMemoryUsage / 2) { "Serialized ontology exceeds memory budget" }
            val ontology = manager.loadOntologyFromOntologyDocument(StringDocumentSource(turtle, IRI.create("urn:kastor:hermit-input")))
            checkBudget()
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
            val options = org.semanticweb.HermiT.Configuration().apply { individualTaskTimeout = remainingMillis }
            val engine = ReasonerFactory().createReasoner(ontology, options)
            reasoner = engine
            watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "kastor-hermit-watchdog").apply { isDaemon = true } }
            watchdog.schedule({
                timedOut.set(true)
                engine.interrupt()
            }, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.MILLISECONDS)

            return try {
                val consistent = engine.isConsistent
                checkBudget()
                val classification = if (consistent && classify) classification(engine, ontology, ::checkBudget) else null
                val inferred = if (consistent && materialize) materialize(engine, ontology, manager.owlDataFactory, graph, ::checkBudget) else emptyList()
                checkBudget()
                val elapsed = Duration.ofNanos(System.nanoTime() - start)
                ReasoningResult(graph, inferred, classification,
                    ConsistencyResult(consistent, if (consistent) emptyList() else listOf(Inconsistency(
                        InconsistencyType.CLASS_CONFLICT, "HermiT reports an inconsistent ontology", emptyList(), Severity.ERROR)), emptyList()),
                    elapsed, ReasoningStatistics(graph.size(), inferred.size, ontology.classesInSignature.size,
                        ontology.objectPropertiesInSignature.size + ontology.dataPropertiesInSignature.size,
                        mapOf("hermit" to inferred.size), Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }, elapsed))
            } catch (e: RuntimeException) {
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
                if (ontology.containsAxiomIgnoreAnnotations(axiom, true)) continue
                for (triple in triplesOf(axiom)) {
                    if (triple in asserted || !result.add(triple)) continue
                    require(result.size.toLong() <= config.materializationThreshold) { "Inferred triples exceed materializationThreshold" }
                }
            }
        }
        return result.toList()
    }

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
                val term: Literal = if (literal.hasLang()) LangString(literal.literal, literal.lang)
                    else TypedLiteral(literal.literal, Iri(literal.datatype.iri.toString()))
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
        const val TIMEOUT_MESSAGE = "HermiT reasoning timed out or was cancelled"
        const val OWL = "http://www.w3.org/2002/07/owl#"
        val RDF_TYPE = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
        val SUB_CLASS_OF = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
        val SUB_PROPERTY_OF = Iri("http://www.w3.org/2000/01/rdf-schema#subPropertyOf")
        val EQUIVALENT_CLASS = Iri(OWL + "equivalentClass")
        val EQUIVALENT_PROPERTY = Iri(OWL + "equivalentProperty")
        val INVERSE_OF = Iri(OWL + "inverseOf")
    }
}
