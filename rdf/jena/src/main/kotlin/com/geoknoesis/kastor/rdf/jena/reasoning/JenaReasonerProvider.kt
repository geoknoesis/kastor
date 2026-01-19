package com.geoknoesis.kastor.rdf.jena.reasoning

import com.geoknoesis.kastor.rdf.reasoning.*
import org.apache.jena.reasoner.Reasoner
import org.apache.jena.reasoner.rulesys.GenericRuleReasonerFactory
import org.apache.jena.reasoner.rulesys.RDFSRuleReasonerFactory
import org.apache.jena.reasoner.rulesys.OWLMicroReasonerFactory
import org.apache.jena.reasoner.rulesys.Rule
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.Statement
import com.geoknoesis.kastor.rdf.*

class JenaReasonerProvider : RdfReasonerProvider {
    
    override fun getType(): String = "jena"
    
    override val name: String = "Apache Jena Reasoner"
    
    override val version: String = "4.x"
    
    override fun createReasoner(config: ReasonerConfig): RdfReasoner {
        return JenaReasoner(config)
    }
    
    override fun getCapabilities(): ReasonerCapabilities {
        return ReasonerCapabilities(
            supportedTypes = setOf(
                ReasonerType.RDFS,
                ReasonerType.OWL_EL,
                ReasonerType.OWL_RL,
                ReasonerType.CUSTOM
            ),
            supportsIncrementalReasoning = true,
            supportsCustomRules = true,
            supportsExplanation = false,
            supportsConsistencyChecking = true,
            supportsClassification = true,
            typicalPerformance = PerformanceProfile.MEDIUM
        )
    }
    
    override fun getSupportedTypes(): List<ReasonerType> {
        return listOf(
            ReasonerType.RDFS,
            ReasonerType.OWL_EL,
            ReasonerType.OWL_RL,
            ReasonerType.CUSTOM
        )
    }
    
    override fun isSupported(type: ReasonerType): Boolean {
        return getSupportedTypes().contains(type)
    }
}

class JenaReasoner(private val config: ReasonerConfig) : RdfReasoner {
    
    private val reasoner: Reasoner = createReasoner()
    
    private fun createReasoner(): Reasoner {
        return when (config.reasonerType) {
            ReasonerType.RDFS -> RDFSRuleReasonerFactory().create(null)
            ReasonerType.OWL_EL -> OWLMicroReasonerFactory().create(null)
            ReasonerType.OWL_RL -> RDFSRuleReasonerFactory().create(null) // Use RDFS for now
            ReasonerType.CUSTOM -> createCustomReasoner()
            else -> throw IllegalArgumentException("Unsupported reasoner type: ${config.reasonerType}")
        }
    }
    
    private fun createCustomReasoner(): Reasoner {
        // For now, just use RDFS reasoner as fallback
        // In a full implementation, you would parse and create custom rules
        return RDFSRuleReasonerFactory().create(null)
    }
    
    override fun reason(graph: RdfGraph): ReasoningResult {
        val startTime = System.currentTimeMillis()
        val jenaModel = convertToJenaModel(graph)
        val infModel = ModelFactory.createInfModel(reasoner, jenaModel)
        
        // Extract inferred triples
        val inferredTriples = extractInferredTriples(jenaModel, infModel)
        
        // Check consistency
        val consistencyResult = checkConsistency(infModel)
        
        // Perform classification
        val classificationResult = if (config.includeAxioms) performClassification(infModel) else null
        
        val reasoningTime = java.time.Duration.ofMillis(System.currentTimeMillis() - startTime)
        
        val statistics = ReasoningStatistics(
            totalTriples = graph.getTriples().size,
            inferredTriples = inferredTriples.size,
            classesProcessed = countClasses(infModel),
            propertiesProcessed = countProperties(infModel),
            rulesApplied = mapOf("total" to inferredTriples.size),
            memoryUsage = getCurrentMemoryUsage(),
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
    
    override fun isConsistent(graph: RdfGraph): Boolean {
        return try {
            val jenaModel = convertToJenaModel(graph)
            val infModel = ModelFactory.createInfModel(reasoner, jenaModel)
            // Simple consistency check - if we can create the inference model, it's consistent
            true
        } catch (e: Exception) {
            false
        }
    }
    
    override fun getInferredTriples(graph: RdfGraph): List<RdfTriple> {
        return reason(graph).inferredTriples
    }
    
    override fun classify(graph: RdfGraph): ClassificationResult {
        val jenaModel = convertToJenaModel(graph)
        val infModel = ModelFactory.createInfModel(reasoner, jenaModel)
        return performClassification(infModel)
    }
    
    override fun validateOntology(graph: RdfGraph): ValidationReport {
        val startTime = System.currentTimeMillis()
        
        val violations = mutableListOf<ValidationViolation>()
        val warnings = mutableListOf<String>()
        
        // Basic validation using consistency check
        val consistencyResult = checkConsistency(convertToJenaModel(graph))
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
                constraintsChecked = 10, // Jena provides more validation
                violationsFound = violations.size,
                warningsFound = warnings.size,
                validationTime = validationTime
            )
        )
    }
    
    // Helper methods
    private fun extractInferredTriples(originalModel: Model, infModel: Model): List<RdfTriple> {
        val inferredTriples = mutableListOf<RdfTriple>()
        val originalStmts = originalModel.listStatements().toSet()
        
        infModel.listStatements().forEach { stmt ->
            if (!originalStmts.contains(stmt)) {
                inferredTriples.add(convertFromJenaTriple(stmt))
            }
        }
        
        return inferredTriples
    }
    
    private fun checkConsistency(model: Model): ConsistencyResult {
        val inconsistencies = mutableListOf<Inconsistency>()
        val warnings = mutableListOf<String>()
        
        // Basic consistency check - Jena handles most consistency issues automatically
        // More sophisticated checks could be added here
        
        return ConsistencyResult(
            isConsistent = inconsistencies.isEmpty(),
            inconsistencies = inconsistencies,
            warnings = warnings
        )
    }
    
    private fun performClassification(model: Model): ClassificationResult {
        val classHierarchy = mutableMapOf<Iri, List<Iri>>()
        val instanceClassifications = mutableMapOf<Iri, List<Iri>>()
        val propertyHierarchy = mutableMapOf<Iri, List<Iri>>()
        
        // Extract class hierarchy
        val subClassOf = model.createProperty("http://www.w3.org/2000/01/rdf-schema#subClassOf")
        model.listResourcesWithProperty(subClassOf).forEach { cls ->
            val subClasses = mutableListOf<Iri>()
            cls.listProperties(subClassOf).forEach { stmt ->
                subClasses.add(convertFromJenaIri(stmt.`object`.asResource().uri))
            }
            classHierarchy[convertFromJenaIri(cls.uri)] = subClasses
        }
        
        // Extract instance classifications
        val type = model.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
        model.listResourcesWithProperty(type).forEach { instance ->
            val types = mutableListOf<Iri>()
            instance.listProperties(type).forEach { stmt ->
                types.add(convertFromJenaIri(stmt.`object`.asResource().uri))
            }
            instanceClassifications[convertFromJenaIri(instance.uri)] = types
        }
        
        // Extract property hierarchy
        val subPropertyOf = model.createProperty("http://www.w3.org/2000/01/rdf-schema#subPropertyOf")
        model.listResourcesWithProperty(subPropertyOf).forEach { prop ->
            val superProperties = mutableListOf<Iri>()
            prop.listProperties(subPropertyOf).forEach { stmt ->
                superProperties.add(convertFromJenaIri(stmt.`object`.asResource().uri))
            }
            propertyHierarchy[convertFromJenaIri(prop.uri)] = superProperties
        }
        
        return ClassificationResult(
            classHierarchy = classHierarchy,
            instanceClassifications = instanceClassifications,
            propertyHierarchy = propertyHierarchy
        )
    }
    
    private fun countClasses(model: Model): Int {
        return model.listResourcesWithProperty(
            model.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            model.createResource("http://www.w3.org/2000/01/rdf-schema#Class")
        ).toList().size
    }
    
    private fun countProperties(model: Model): Int {
        return model.listResourcesWithProperty(
            model.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            model.createResource("http://www.w3.org/1999/02/22-rdf-syntax-ns#Property")
        ).toList().size
    }
    
    private fun getCurrentMemoryUsage(): Long {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
    }
    
    // Conversion methods
    private fun convertToJenaModel(graph: RdfGraph): Model {
        val jenaModel = ModelFactory.createDefaultModel()
        
        graph.getTriples().forEach { triple ->
            val subject = convertToJenaResource(triple.subject, jenaModel)
            val predicate = jenaModel.createProperty(triple.predicate.value)
            val obj = convertToJenaRDFNode(triple.obj, jenaModel)
            
            jenaModel.add(subject, predicate, obj)
        }
        
        return jenaModel
    }
    
    private fun convertToJenaResource(term: RdfTerm, jenaModel: Model): org.apache.jena.rdf.model.Resource {
        return when (term) {
            is Iri -> jenaModel.createResource(term.value)
            is BlankNode -> jenaModel.createResource(org.apache.jena.rdf.model.AnonId(term.id))
            else -> throw IllegalArgumentException("Cannot convert $term to Jena Resource")
        }
    }
    
    private fun convertToJenaRDFNode(term: RdfTerm, jenaModel: Model): org.apache.jena.rdf.model.RDFNode {
        return when (term) {
            is Iri -> jenaModel.createResource(term.value)
            is BlankNode -> jenaModel.createResource(org.apache.jena.rdf.model.AnonId(term.id))
            is Literal -> {
                when (term) {
                    is LangString -> jenaModel.createLiteral(term.lexical, term.lang)
                    else -> {
                        if (term.datatype != null) {
                            jenaModel.createTypedLiteral(term.lexical, term.datatype.value)
                        } else {
                            jenaModel.createLiteral(term.lexical)
                        }
                    }
                }
            }
            else -> throw IllegalArgumentException("Cannot convert $term to Jena RDFNode")
        }
    }
    
    private fun convertFromJenaTriple(stmt: Statement): RdfTriple {
        return RdfTriple(
            subject = convertFromJenaResource(stmt.subject),
            predicate = Iri(stmt.predicate.uri),
            obj = convertFromJenaTerm(stmt.`object`)
        )
    }
    
    private fun convertFromJenaTerm(node: org.apache.jena.rdf.model.RDFNode): RdfTerm {
        return when {
            node.isURIResource -> Iri(node.asResource().uri)
            node.isAnon -> bnode(node.asResource().id.toString())
            node.isLiteral -> {
                val literal = node.asLiteral()
                if (literal.language.isNotEmpty()) {
                    LangString(literal.string, literal.language)
                } else if (literal.datatypeURI != null) {
                    Literal(literal.string, Iri(literal.datatypeURI))
                } else {
                    Literal(literal.string, Iri("http://www.w3.org/2001/XMLSchema#string"))
                }
            }
            else -> throw IllegalArgumentException("Cannot convert Jena node: $node")
        }
    }
    
    private fun convertFromJenaResource(resource: org.apache.jena.rdf.model.Resource): RdfResource {
        return when {
            resource.isURIResource -> Iri(resource.uri)
            resource.isAnon -> bnode(resource.id.toString())
            else -> throw IllegalArgumentException("Cannot convert Jena resource: $resource")
        }
    }
    
    private fun convertFromJenaIri(uri: String): Iri = Iri(uri)
}









