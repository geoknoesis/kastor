package com.geoknoesis.kastor.rdf.reasoning.providers

import com.geoknoesis.kastor.rdf.reasoning.*
import com.geoknoesis.kastor.rdf.*

/**
 * Default memory-based reasoner provider for RDFS reasoning.
 *
 * It is a dependency-free fallback: when a backend-specific RDFS reasoner (Jena, RDF4J) is on the
 * classpath the registry prefers that one (see [priority]).
 */
class MemoryReasonerProvider : RdfReasonerProvider {

    override fun getType(): String = "memory"

    override val name: String = "Memory RDFS Reasoner"

    override val version: String = "1.0.0"

    /** Lowest priority: only used when no backend-specific reasoner supports the requested type. */
    override fun priority(): Int = -100

    override fun createReasoner(config: ReasonerConfig): RdfReasoner {
        require(isSupported(config.reasonerType)) {
            "MemoryReasonerProvider only supports ReasonerType.RDFS, got ${config.reasonerType}"
        }
        return MemoryReasoner(config)
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

    override fun getSupportedTypes(): List<ReasonerType> {
        return listOf(ReasonerType.RDFS)
    }

    override fun isSupported(type: ReasonerType): Boolean {
        return type == ReasonerType.RDFS
    }
}

/**
 * In-memory RDFS reasoner computing the fixpoint of the RDFS entailment rules
 * [rdfs2](https://www.w3.org/TR/rdf11-mt/#patterns-of-rdfs-entailment-informative) (domain),
 * rdfs3 (range), rdfs5 (subPropertyOf transitivity), rdfs7 (property inheritance), rdfs9 (type
 * propagation along subClassOf) and rdfs11 (subClassOf transitivity).
 *
 * [ReasoningRule.RDFS_SUBCLASS] enables rdfs9 + rdfs11, [ReasoningRule.RDFS_SUBPROPERTY] rdfs5 + rdfs7,
 * [ReasoningRule.RDFS_DOMAIN] rdfs2 and [ReasoningRule.RDFS_RANGE] rdfs3. Axiomatic triples are not generated.
 *
 * Consistency: plain RDFS has no negation, so the only detectable inconsistency is an ill-typed literal of
 * a recognised XSD datatype (D-unsatisfiability); that is what [isConsistent] reports.
 */
class MemoryReasoner(private val config: ReasonerConfig) : RdfReasoner {

    override fun reason(graph: RdfGraph): ReasoningResult {
        val startTime = System.currentTimeMillis()
        val asserted = graph.getTriples()
        val closure = closure(asserted)
        val assertedSet = asserted.toHashSet()
        val inferredTriples = closure.filterNot { it in assertedSet }

        val reasoningTime = java.time.Duration.ofMillis(System.currentTimeMillis() - startTime)

        val statistics = ReasoningStatistics(
            totalTriples = asserted.size,
            inferredTriples = inferredTriples.size,
            classesProcessed = countType(closure, RDFS_CLASS),
            propertiesProcessed = countType(closure, RDF_PROPERTY),
            rulesApplied = mapOf("rdfs" to inferredTriples.size),
            memoryUsage = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            cpuTime = reasoningTime
        )

        return ReasoningResult(
            originalGraph = graph,
            inferredTriples = inferredTriples,
            classification = if (config.includeAxioms) performClassification(closure) else null,
            consistencyCheck = checkConsistency(asserted),
            reasoningTime = reasoningTime,
            statistics = statistics
        )
    }

    override fun isConsistent(graph: RdfGraph): Boolean {
        return checkConsistency(graph.getTriples()).isConsistent
    }

    override fun getInferredTriples(graph: RdfGraph): List<RdfTriple> {
        return reason(graph).inferredTriples
    }

    override fun classify(graph: RdfGraph): ClassificationResult {
        return performClassification(closure(graph.getTriples()))
    }

    override fun validateOntology(graph: RdfGraph): ValidationReport {
        val startTime = System.currentTimeMillis()

        val violations = mutableListOf<ValidationViolation>()
        val consistencyResult = checkConsistency(graph.getTriples())
        consistencyResult.inconsistencies.forEach { inconsistency ->
            violations.add(
                ValidationViolation(
                    constraint = inconsistency.type.name,
                    resource = inconsistency.affectedResources.firstOrNull() ?: Iri("urn:kastor:unknown"),
                    message = inconsistency.description,
                    severity = inconsistency.severity
                )
            )
        }

        val validationTime = java.time.Duration.ofMillis(System.currentTimeMillis() - startTime)

        return ValidationReport(
            isValid = violations.isEmpty(),
            violations = violations,
            warnings = consistencyResult.warnings,
            statistics = ValidationStatistics(
                constraintsChecked = 1,
                violationsFound = violations.size,
                warningsFound = consistencyResult.warnings.size,
                validationTime = validationTime
            )
        )
    }

    /**
     * Computes asserted ∪ entailed triples (fixpoint of the enabled RDFS rules), in insertion order.
     *
     * [ReasonerConfig.timeout] is checked while the fixpoint is computed ([IllegalStateException] when exhausted)
     * and [ReasonerConfig.materializationThreshold] bounds the number of entailed triples ([IllegalArgumentException]).
     */
    internal fun closure(asserted: Collection<RdfTriple>): Set<RdfTriple> {
        val deadline = System.nanoTime() + config.timeout.toNanos()
        var steps = 0L
        fun checkBudget() {
            check(System.nanoTime() - deadline < 0 && !Thread.currentThread().isInterrupted) { "Memory RDFS reasoning timed out or was cancelled" }
        }
        val rules = config.enabledRules
        val subClass = ReasoningRule.RDFS_SUBCLASS in rules
        val subProperty = ReasoningRule.RDFS_SUBPROPERTY in rules
        val domain = ReasoningRule.RDFS_DOMAIN in rules
        val range = ReasoningRule.RDFS_RANGE in rules
        val all = LinkedHashSet(asserted)
        val assertedCount = all.size
        checkBudget()
        while (true) {
            val superClasses = index(all, SUB_CLASS_OF)
            val superProperties = index(all, SUB_PROPERTY_OF)
            val domains = index(all, DOMAIN)
            val ranges = index(all, RANGE)
            val added = LinkedHashSet<RdfTriple>()
            fun emit(triple: RdfTriple) { if (triple !in all) added.add(triple) }
            for (t in all) {
                if ((++steps and 1023L) == 0L) checkBudget()
                val obj = t.obj
                when (t.predicate) {
                    SUB_CLASS_OF -> if (subClass && obj is RdfResource) {
                        superClasses[obj]?.forEach { emit(RdfTriple(t.subject, SUB_CLASS_OF, it)) } // rdfs11
                    }
                    SUB_PROPERTY_OF -> if (subProperty && obj is RdfResource) {
                        superProperties[obj]?.forEach { emit(RdfTriple(t.subject, SUB_PROPERTY_OF, it)) } // rdfs5
                    }
                    TYPE -> if (subClass && obj is RdfResource) {
                        superClasses[obj]?.forEach { emit(RdfTriple(t.subject, TYPE, it)) } // rdfs9
                    }
                }
                if (subProperty) {
                    superProperties[t.predicate]?.forEach { q -> if (q is Iri) emit(RdfTriple(t.subject, q, obj)) } // rdfs7
                }
                if (domain) {
                    domains[t.predicate]?.forEach { c -> emit(RdfTriple(t.subject, TYPE, c)) } // rdfs2
                }
                if (range && obj is RdfResource) {
                    ranges[t.predicate]?.forEach { c -> emit(RdfTriple(obj, TYPE, c)) } // rdfs3
                }
            }
            if (added.isEmpty()) return all
            all.addAll(added)
            require(all.size - assertedCount <= config.materializationThreshold) {
                "Inferred triples exceed materializationThreshold (${config.materializationThreshold})"
            }
            checkBudget()
        }
    }

    private fun index(triples: Collection<RdfTriple>, predicate: Iri): Map<RdfTerm, List<RdfTerm>> =
        triples.asSequence().filter { it.predicate == predicate }.groupBy({ it.subject }, { it.obj })

    private fun performClassification(triples: Collection<RdfTriple>): ClassificationResult {
        val classHierarchy = mutableMapOf<Iri, List<Iri>>()
        val instanceClassifications = mutableMapOf<Iri, List<Iri>>()
        val propertyHierarchy = mutableMapOf<Iri, List<Iri>>()

        triples.forEach { triple ->
            val subject = triple.subject as? Iri ?: return@forEach
            val obj = triple.obj as? Iri ?: return@forEach
            when (triple.predicate) {
                SUB_CLASS_OF -> classHierarchy[subject] = classHierarchy.getOrDefault(subject, emptyList()) + obj
                TYPE -> instanceClassifications[subject] = instanceClassifications.getOrDefault(subject, emptyList()) + obj
                SUB_PROPERTY_OF -> propertyHierarchy[subject] = propertyHierarchy.getOrDefault(subject, emptyList()) + obj
            }
        }

        return ClassificationResult(
            classHierarchy = classHierarchy,
            instanceClassifications = instanceClassifications,
            propertyHierarchy = propertyHierarchy
        )
    }

    private fun checkConsistency(triples: Collection<RdfTriple>): ConsistencyResult {
        val inconsistencies = triples.mapNotNull { triple ->
            val literal = triple.obj as? Literal ?: return@mapNotNull null
            if (literal is LangString || XsdLexicalForms.isWellTyped(literal.lexical, literal.datatype)) return@mapNotNull null
            Inconsistency(
                type = InconsistencyType.DOMAIN_RANGE_VIOLATION,
                description = "Ill-typed literal \"${literal.lexical}\" for datatype ${literal.datatype.value}",
                affectedResources = listOf(triple.subject, literal),
            )
        }
        return ConsistencyResult(
            isConsistent = inconsistencies.isEmpty(),
            inconsistencies = inconsistencies,
            warnings = emptyList()
        )
    }

    private fun countType(triples: Collection<RdfTriple>, type: Iri): Int =
        triples.count { it.predicate == TYPE && it.obj == type }

    private companion object {
        val TYPE = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
        val RDF_PROPERTY = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#Property")
        val RDFS_CLASS = Iri("http://www.w3.org/2000/01/rdf-schema#Class")
        val SUB_CLASS_OF = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
        val SUB_PROPERTY_OF = Iri("http://www.w3.org/2000/01/rdf-schema#subPropertyOf")
        val DOMAIN = Iri("http://www.w3.org/2000/01/rdf-schema#domain")
        val RANGE = Iri("http://www.w3.org/2000/01/rdf-schema#range")
    }
}

/** Lexical-space checks for the XSD datatypes the memory reasoner recognises. Unknown datatypes are accepted. */
internal object XsdLexicalForms {
    private const val XSD = "http://www.w3.org/2001/XMLSchema#"
    private val integer = Regex("[+-]?[0-9]+")
    private val decimal = Regex("[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)")
    private val floating = Regex("([+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?|[+-]?INF|NaN)")

    fun isWellTyped(lexical: String, datatype: Iri): Boolean = when (datatype.value) {
        "${XSD}boolean" -> lexical in setOf("true", "false", "1", "0")
        "${XSD}integer", "${XSD}long", "${XSD}int", "${XSD}short", "${XSD}byte",
        "${XSD}nonNegativeInteger", "${XSD}positiveInteger", "${XSD}nonPositiveInteger", "${XSD}negativeInteger" ->
            integer.matches(lexical) && inRange(lexical, datatype.value.removePrefix(XSD))
        "${XSD}decimal" -> decimal.matches(lexical)
        "${XSD}double", "${XSD}float" -> floating.matches(lexical)
        "${XSD}date" -> runCatching { java.time.format.DateTimeFormatter.ISO_DATE.parse(lexical) }.isSuccess
        "${XSD}dateTime" -> runCatching { java.time.format.DateTimeFormatter.ISO_DATE_TIME.parse(lexical) }.isSuccess
        else -> true
    }

    private fun inRange(lexical: String, type: String): Boolean {
        val value = lexical.toBigInteger()
        fun between(min: Long, max: Long) = value >= min.toBigInteger() && value <= max.toBigInteger()
        return when (type) {
            "long" -> between(Long.MIN_VALUE, Long.MAX_VALUE)
            "int" -> between(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            "short" -> between(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
            "byte" -> between(Byte.MIN_VALUE.toLong(), Byte.MAX_VALUE.toLong())
            "nonNegativeInteger" -> value.signum() >= 0
            "positiveInteger" -> value.signum() > 0
            "nonPositiveInteger" -> value.signum() <= 0
            "negativeInteger" -> value.signum() < 0
            else -> true
        }
    }
}
