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
 * | [ReasonerType.RDFS] | `RDFSRuleReasoner` (full RDFS rule set) |
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
        listOf(ReasonerType.RDFS, ReasonerType.OWL_RL, ReasonerType.CUSTOM)

    override fun isSupported(type: ReasonerType): Boolean {
        return getSupportedTypes().contains(type)
    }
}

/**
 * [RdfReasoner] backed by a Jena [Reasoner].
 *
 * For [ReasonerType.CUSTOM], each [CustomRule] becomes the Jena rule `[name: pattern -> conclusion]`,
 * so `pattern` and `conclusion` use Jena rule syntax, e.g. `(?a <http://ex/p> ?b)`.
 */
class JenaReasoner(private val config: ReasonerConfig) : RdfReasoner {

    private val reasoner: Reasoner = when (config.reasonerType) {
        ReasonerType.RDFS -> JenaReasonerRegistry.getRDFSReasoner()
        ReasonerType.OWL_RL -> JenaReasonerRegistry.getOWLReasoner()
        ReasonerType.CUSTOM -> createCustomReasoner()
        else -> throw IllegalArgumentException(
            "Jena reasoner does not support ${config.reasonerType}; supported types: RDFS, OWL_RL, CUSTOM",
        )
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

    private fun <T> withInference(graph: RdfGraph, block: (Model, InfModel) -> T): T {
        val base = JenaBridge.copyToJenaModel(graph)
        val inf = ModelFactory.createInfModel(reasoner, base)
        try {
            return block(base, inf)
        } finally {
            inf.close()
            base.close()
        }
    }

    override fun reason(graph: RdfGraph): ReasoningResult {
        val startTime = System.currentTimeMillis()
        return withInference(graph) { base, infModel ->
            val consistencyResult = consistencyOf(infModel.validate())
            val inferredTriples = extractInferredTriples(base, infModel)
            val classificationResult = if (config.includeAxioms) performClassification(infModel) else null
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

    override fun isConsistent(graph: RdfGraph): Boolean = withInference(graph) { _, inf -> inf.validate().isValid }

    override fun getInferredTriples(graph: RdfGraph): List<RdfTriple> =
        withInference(graph) { base, inf -> extractInferredTriples(base, inf) }

    override fun classify(graph: RdfGraph): ClassificationResult = withInference(graph) { _, inf -> performClassification(inf) }

    override fun validateOntology(graph: RdfGraph): ValidationReport {
        val startTime = System.currentTimeMillis()
        val consistency = withInference(graph) { _, inf -> consistencyOf(inf.validate()) }
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

    /** Statements in the inference closure that are not asserted and are representable as RDF 1.2 triples. */
    private fun extractInferredTriples(base: Model, infModel: Model): List<RdfTriple> {
        val asserted = base.graph
        val iterator = infModel.graph.find()
        try {
            return iterator.asSequence()
                .filter { (it.subject.isURI || it.subject.isBlank) && it.predicate.isURI && !asserted.contains(it) }
                .map { triple ->
                    RdfTriple(
                        rdfTermFromJena(infModel.asRDFNode(triple.subject)) as RdfResource,
                        Iri(triple.predicate.uri),
                        rdfTermFromJena(infModel.asRDFNode(triple.`object`)),
                    )
                }
                .toList()
        } finally {
            iterator.close()
        }
    }

    private fun performClassification(model: Model): ClassificationResult {
        fun hierarchy(predicate: String): Map<Iri, List<Iri>> {
            val result = linkedMapOf<Iri, MutableList<Iri>>()
            val iterator = model.graph.find(Node.ANY, org.apache.jena.graph.NodeFactory.createURI(predicate), Node.ANY)
            try {
                iterator.forEachRemaining { t ->
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
    }
}
