package com.geoknoesis.kastor.rdf.hermit

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.reasoning.*
import org.semanticweb.HermiT.ReasonerFactory
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat
import org.semanticweb.owlapi.io.StringDocumentSource
import org.semanticweb.owlapi.model.IRI
import org.semanticweb.owlapi.model.OWLOntology
import org.semanticweb.owlapi.reasoner.OWLReasoner
import org.semanticweb.owlapi.util.InferredOntologyGenerator
import java.time.Duration

/** HermiT reasoning with explicit engine disposal and bounded serialization/materialization. */
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
        fun checkBudget() {
            check(!Thread.currentThread().isInterrupted && System.nanoTime() - start < config.timeout.toNanos()) { "HermiT reasoning timed out or was cancelled" }
        }
        // Conservative admission estimate; an in-process engine cannot offer a hard per-request JVM heap quota.
        require(graph.size().toLong() <= config.maxMemoryUsage / 512) { "Input exceeds HermiT memory admission budget" }
        val manager = OWLManager.createOWLOntologyManager()
        var reasoner: OWLReasoner? = null
        try {
            val turtle = graph.serialize(RdfFormat.TURTLE)
            require(turtle.length.toLong() * 2 <= config.maxMemoryUsage / 2) { "Serialized ontology exceeds memory budget" }
            val ontology = manager.loadOntologyFromOntologyDocument(StringDocumentSource(turtle, IRI.create("urn:kastor:hermit-input")))
            checkBudget()
            val options = org.semanticweb.HermiT.Configuration().apply { individualTaskTimeout = config.timeout.toMillis() }
            val engine = ReasonerFactory().createReasoner(ontology, options)
            reasoner = engine
            val consistent = engine.isConsistent
            checkBudget()
            val classification = if (consistent && classify) classification(engine, ontology, ::checkBudget) else null
            val inferred = if (consistent && materialize) {
                val target = manager.createOntology()
                val listener = org.semanticweb.owlapi.model.OWLOntologyChangeListener {
                    checkBudget()
                    require(target.axiomCount.toLong() <= config.materializationThreshold) { "Inferred axioms exceed materializationThreshold" }
                }
                manager.addOntologyChangeListener(listener)
                try {
                    InferredOntologyGenerator(engine).fillOntology(manager.owlDataFactory, target)
                    val bytes = java.io.ByteArrayOutputStream()
                    val output = object : java.io.OutputStream() {
                        var count = 0L
                        private fun account(n: Int) {
                            checkBudget(); count += n
                            require(count <= config.maxMemoryUsage / 4) { "Inferred serialization exceeds memory budget" }
                        }
                        override fun write(b: Int) { account(1); bytes.write(b) }
                        override fun write(b: ByteArray, offset: Int, length: Int) { account(length); bytes.write(b, offset, length) }
                    }
                    manager.saveOntology(target, RDFXMLDocumentFormat(), output)
                    val original = graph.getTriples().toHashSet()
                    Rdf.openTripleStream(bytes.toByteArray().inputStream(), RdfFormat.RDF_XML).use { stream ->
                        val result = mutableListOf<RdfTriple>()
                        for (triple in stream) {
                            checkBudget()
                            if (triple !in original) {
                                require(result.size.toLong() < config.materializationThreshold) { "Inferred triples exceed materializationThreshold" }
                                result.add(triple)
                            }
                        }
                        result
                    }
                } finally { manager.removeOntologyChangeListener(listener) }
            } else emptyList()
            checkBudget()
            val elapsed = Duration.ofNanos(System.nanoTime() - start)
            return ReasoningResult(graph, inferred, classification,
                ConsistencyResult(consistent, if (consistent) emptyList() else listOf(Inconsistency(
                    InconsistencyType.CLASS_CONFLICT, "HermiT reports an inconsistent ontology", emptyList(), Severity.ERROR)), emptyList()),
                elapsed, ReasoningStatistics(graph.size(), inferred.size, ontology.classesInSignature.size,
                    ontology.objectPropertiesInSignature.size + ontology.dataPropertiesInSignature.size,
                    mapOf("hermit" to inferred.size), Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }, elapsed))
        } finally {
            try { reasoner?.dispose() } finally { manager.ontologies.toList().forEach { manager.removeOntology(it) } }
        }
    }

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

}
