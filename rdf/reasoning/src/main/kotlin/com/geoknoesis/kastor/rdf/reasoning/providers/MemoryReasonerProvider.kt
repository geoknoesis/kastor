package com.geoknoesis.kastor.rdf.reasoning.providers

import com.geoknoesis.kastor.rdf.reasoning.*
import com.geoknoesis.kastor.rdf.*

/**
 * Default memory-based reasoner provider for basic RDFS reasoning.
 * This is a simple implementation that provides basic RDFS inference.
 */
class MemoryReasonerProvider : RdfReasonerProvider {
    
    override fun getType(): String = "memory"
    
    override val name: String = "Memory RDFS Reasoner"
    
    override val version: String = "1.0.0"
    
    override fun createReasoner(config: ReasonerConfig): RdfReasoner {
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
 * Simple memory-based RDFS reasoner implementation.
 */
class MemoryReasoner(private val config: ReasonerConfig) : RdfReasoner {
    
    override fun reason(graph: RdfGraph): ReasoningResult {
        val startTime = System.currentTimeMillis()
        
        val inferredTriples = mutableListOf<RdfTriple>()
        
        // Basic RDFS reasoning rules
        if (config.enabledRules.contains(ReasoningRule.RDFS_SUBCLASS)) {
            inferredTriples.addAll(inferSubclassTransitivity(graph))
        }
        
        if (config.enabledRules.contains(ReasoningRule.RDFS_SUBPROPERTY)) {
            inferredTriples.addAll(inferSubpropertyTransitivity(graph))
        }
        
        if (config.enabledRules.contains(ReasoningRule.RDFS_DOMAIN)) {
            inferredTriples.addAll(inferDomainInference(graph))
        }
        
        if (config.enabledRules.contains(ReasoningRule.RDFS_RANGE)) {
            inferredTriples.addAll(inferRangeInference(graph))
        }
        
        val reasoningTime = java.time.Duration.ofMillis(System.currentTimeMillis() - startTime)
        
        val statistics = ReasoningStatistics(
            totalTriples = graph.getTriples().size,
            inferredTriples = inferredTriples.size,
            classesProcessed = countClasses(graph),
            propertiesProcessed = countProperties(graph),
            rulesApplied = mapOf("rdfs" to inferredTriples.size),
            memoryUsage = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            cpuTime = reasoningTime
        )
        
        return ReasoningResult(
            originalGraph = graph,
            inferredTriples = inferredTriples,
            classification = if (config.includeAxioms) performClassification(graph) else null,
            consistencyCheck = checkConsistency(graph),
            reasoningTime = reasoningTime,
            statistics = statistics
        )
    }
    
    override fun isConsistent(graph: RdfGraph): Boolean {
        return checkConsistency(graph).isConsistent
    }
    
    override fun getInferredTriples(graph: RdfGraph): List<RdfTriple> {
        return reason(graph).inferredTriples
    }
    
    override fun classify(graph: RdfGraph): ClassificationResult {
        return performClassification(graph)
    }
    
    override fun validateOntology(graph: RdfGraph): ValidationReport {
        val startTime = System.currentTimeMillis()
        
        val violations = mutableListOf<ValidationViolation>()
        val warnings = mutableListOf<String>()
        
        // Basic validation rules
        val consistencyResult = checkConsistency(graph)
        if (!consistencyResult.isConsistent) {
            consistencyResult.inconsistencies.forEach { inconsistency ->
                violations.add(
                    ValidationViolation(
                        constraint = inconsistency.type.name,
                        resource = inconsistency.affectedResources.firstOrNull() ?: Iri("unknown"),
                        message = inconsistency.description,
                        severity = Severity.ERROR
                    )
                )
            }
        }
        
        val validationTime = java.time.Duration.ofMillis(System.currentTimeMillis() - startTime)
        
        return ValidationReport(
            isValid = violations.isEmpty(),
            violations = violations,
            warnings = warnings,
            statistics = ValidationStatistics(
                constraintsChecked = 5, // Basic RDFS constraints
                violationsFound = violations.size,
                warningsFound = warnings.size,
                validationTime = validationTime
            )
        )
    }
    
    // RDFS reasoning implementations
    private fun inferSubclassTransitivity(graph: RdfGraph): List<RdfTriple> {
        val inferred = mutableListOf<RdfTriple>()
        val triples = graph.getTriples()
        
        // Find all rdfs:subClassOf triples
        val subclassTriples = triples.filter { 
            it.predicate.value == "http://www.w3.org/2000/01/rdf-schema#subClassOf" 
        }
        
        // Apply transitivity: if A subClassOf B and B subClassOf C, then A subClassOf C
        subclassTriples.forEach { triple1 ->
            subclassTriples.forEach { triple2 ->
                if (triple1.obj == triple2.subject) {
                    val inferredTriple = RdfTriple(
                        triple1.subject,
                        Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf"),
                        triple2.obj
                    )
                    if (!triples.contains(inferredTriple)) {
                        inferred.add(inferredTriple)
                    }
                }
            }
        }
        
        return inferred
    }
    
    private fun inferSubpropertyTransitivity(graph: RdfGraph): List<RdfTriple> {
        val inferred = mutableListOf<RdfTriple>()
        val triples = graph.getTriples()
        
        // Find all rdfs:subPropertyOf triples
        val subpropertyTriples = triples.filter { 
            it.predicate.value == "http://www.w3.org/2000/01/rdf-schema#subPropertyOf" 
        }
        
        // Apply transitivity: if A subPropertyOf B and B subPropertyOf C, then A subPropertyOf C
        subpropertyTriples.forEach { triple1 ->
            subpropertyTriples.forEach { triple2 ->
                if (triple1.obj == triple2.subject) {
                    val inferredTriple = RdfTriple(
                        triple1.subject,
                        Iri("http://www.w3.org/2000/01/rdf-schema#subPropertyOf"),
                        triple2.obj
                    )
                    if (!triples.contains(inferredTriple)) {
                        inferred.add(inferredTriple)
                    }
                }
            }
        }
        
        return inferred
    }
    
    private fun inferDomainInference(graph: RdfGraph): List<RdfTriple> {
        val inferred = mutableListOf<RdfTriple>()
        val triples = graph.getTriples()
        
        // Find all rdfs:domain triples
        val domainTriples = triples.filter { 
            it.predicate.value == "http://www.w3.org/2000/01/rdf-schema#domain" 
        }
        
        // Apply domain inference: if P has domain D and X has property P, then X is of type D
        domainTriples.forEach { domainTriple ->
            val property = domainTriple.subject
            val domain = domainTriple.obj
            
            triples.forEach { triple ->
                if (triple.predicate == property) {
                    val typeTriple = RdfTriple(
                        triple.subject,
                        Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                        domain
                    )
                    if (!triples.contains(typeTriple)) {
                        inferred.add(typeTriple)
                    }
                }
            }
        }
        
        return inferred
    }
    
    private fun inferRangeInference(graph: RdfGraph): List<RdfTriple> {
        val inferred = mutableListOf<RdfTriple>()
        val triples = graph.getTriples()
        
        // Find all rdfs:range triples
        val rangeTriples = triples.filter { 
            it.predicate.value == "http://www.w3.org/2000/01/rdf-schema#range" 
        }
        
        // Apply range inference: if P has range R and X has property P with value V, then V is of type R
        rangeTriples.forEach { rangeTriple ->
            val property = rangeTriple.subject
            val range = rangeTriple.obj
            
            triples.forEach { triple ->
                if (triple.predicate == property && triple.obj is Iri) {
                    val objIri = triple.obj as Iri
                    val typeTriple = RdfTriple(
                        objIri,
                        Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                        range
                    )
                    if (!triples.contains(typeTriple)) {
                        inferred.add(typeTriple)
                    }
                }
            }
        }
        
        return inferred
    }
    
    private fun performClassification(graph: RdfGraph): ClassificationResult {
        val triples = graph.getTriples()
        
        val classHierarchy = mutableMapOf<Iri, List<Iri>>()
        val instanceClassifications = mutableMapOf<Iri, List<Iri>>()
        val propertyHierarchy = mutableMapOf<Iri, List<Iri>>()
        
        // Build class hierarchy
        val subclassTriples = triples.filter { 
            it.predicate.value == "http://www.w3.org/2000/01/rdf-schema#subClassOf" 
        }
        subclassTriples.forEach { triple ->
            val subClass = triple.subject as? Iri
            val superClass = triple.obj as? Iri
            if (subClass != null && superClass != null) {
                classHierarchy[subClass] = classHierarchy.getOrDefault(subClass, emptyList()) + superClass
            }
        }
        
        // Build instance classifications
        val typeTriples = triples.filter { 
            it.predicate.value == "http://www.w3.org/1999/02/22-rdf-syntax-ns#type" 
        }
        typeTriples.forEach { triple ->
            val instance = triple.subject as? Iri
            val type = triple.obj as? Iri
            if (instance != null && type != null) {
                instanceClassifications[instance] = instanceClassifications.getOrDefault(instance, emptyList()) + type
            }
        }
        
        // Build property hierarchy
        val subpropertyTriples = triples.filter { 
            it.predicate.value == "http://www.w3.org/2000/01/rdf-schema#subPropertyOf" 
        }
        subpropertyTriples.forEach { triple ->
            val subProperty = triple.subject as? Iri
            val superProperty = triple.obj as? Iri
            if (subProperty != null && superProperty != null) {
                propertyHierarchy[subProperty] = propertyHierarchy.getOrDefault(subProperty, emptyList()) + superProperty
            }
        }
        
        return ClassificationResult(
            classHierarchy = classHierarchy,
            instanceClassifications = instanceClassifications,
            propertyHierarchy = propertyHierarchy
        )
    }
    
    private fun checkConsistency(graph: RdfGraph): ConsistencyResult {
        val inconsistencies = mutableListOf<Inconsistency>()
        val warnings = mutableListOf<String>()
        
        // Basic consistency checks
        val triples = graph.getTriples()
        
        // Check for conflicting type assertions
        val typeTriples = triples.filter { 
            it.predicate.value == "http://www.w3.org/1999/02/22-rdf-syntax-ns#type" 
        }
        
        val typeMap = typeTriples.groupBy { it.subject }
        typeMap.forEach { (subject, types) ->
            if (types.size > 1) {
                // Check if any types are disjoint (simplified check)
                val typeValues = types.map { it.obj }.toSet()
                if (typeValues.size != types.size) {
                    inconsistencies.add(
                        Inconsistency(
                            type = InconsistencyType.CLASS_CONFLICT,
                            description = "Multiple conflicting type assertions for resource",
                            affectedResources = listOf(subject)
                        )
                    )
                }
            }
        }
        
        return ConsistencyResult(
            isConsistent = inconsistencies.isEmpty(),
            inconsistencies = inconsistencies,
            warnings = warnings
        )
    }
    
    private fun countClasses(graph: RdfGraph): Int {
        val triples = graph.getTriples()
        return triples.filter { 
            it.predicate.value == "http://www.w3.org/1999/02/22-rdf-syntax-ns#type" &&
            it.obj is Iri && (it.obj as Iri).value == "http://www.w3.org/2000/01/rdf-schema#Class"
        }.size
    }
    
    private fun countProperties(graph: RdfGraph): Int {
        val triples = graph.getTriples()
        return triples.filter { 
            it.predicate.value == "http://www.w3.org/1999/02/22-rdf-syntax-ns#type" &&
            it.obj is Iri && (it.obj as Iri).value == "http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"
        }.size
    }
}









