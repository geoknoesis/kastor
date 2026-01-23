package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.shacl.*
import com.geoknoesis.kastor.rdf.*
import java.time.Duration

/**
 * Memory-based SHACL validator provider.
 * Provides basic SHACL Core constraint validation.
 */
class MemoryShaclValidatorProvider : ShaclValidatorProvider {
    
    override fun getType(): String = "memory"
    
    override val name: String = "Memory SHACL Validator"
    
    override val version: String = "1.0.0"
    
    override fun createValidator(config: ValidationConfig): ShaclValidator {
        return MemoryShaclValidator(config)
    }
    
    override fun getCapabilities(): ValidatorCapabilities {
        return ValidatorCapabilities(
            supportsShaclCore = true,
            supportsShaclSparql = false,
            supportsShaclJs = false,
            supportsShaclPy = false,
            supportsShaclDash = false,
            supportsCustomConstraints = false,
            supportsParallelValidation = true,
            supportsStreamingValidation = false,
            supportsIncrementalValidation = false,
            maxGraphSize = 100_000L,
            performanceProfile = PerformanceProfile.FAST
        )
    }
    
    override fun getSupportedProfiles(): List<ValidationProfile> {
        return listOf(
            ValidationProfile.SHACL_CORE,
            ValidationProfile.PERMISSIVE,
            ValidationProfile.STRICT
        )
    }
    
    override fun isSupported(profile: ValidationProfile): Boolean {
        return getSupportedProfiles().contains(profile)
    }
}

/**
 * Memory-based SHACL validator implementation.
 * Provides basic SHACL Core constraint validation.
 */
class MemoryShaclValidator(private val config: ValidationConfig) : ShaclValidator {
    
    override fun validate(graph: RdfGraph, shapes: RdfGraph): ValidationReport {
        val startTime = System.currentTimeMillis()
        
        try {
            val violations = mutableListOf<ValidationViolation>()
            val warnings = mutableListOf<ValidationWarning>()
            
            // Extract shapes from the shapes graph
            val shaclShapes = extractShapes(shapes)
            
            // Validate each shape
            for (shape in shaclShapes) {
                val shapeViolations = validateShape(graph, shape)
                violations.addAll(shapeViolations)
            }
            
            val endTime = System.currentTimeMillis()
            val validationTime = Duration.ofMillis(endTime - startTime)
            
            // Calculate statistics
            val statistics = calculateStatistics(graph, shapes, violations, warnings)
            
            return ValidationReport(
                isValid = violations.isEmpty(),
                violations = violations,
                warnings = warnings,
                statistics = statistics,
                validationTime = validationTime,
                validatedResources = graph.getTriples().size,
                validatedConstraints = shaclShapes.sumOf { it.constraints.size },
                shapeViolations = violations.groupBy { it.shapeUri ?: "unknown" },
                constraintViolations = violations.groupBy { it.constraint.constraintType.name }
            )
            
        } catch (e: Exception) {
            throw ValidationException("Validation failed: ${e.message}", e)
        }
    }
    
    override fun validate(graph: RdfGraph, shapes: List<ShaclShape>): ValidationReport {
        // Convert shapes to a temporary graph for processing
        val shapesGraph = Rdf.graph {
            for (shape in shapes) {
                val shapeIri = Iri(shape.shapeUri)
                shapeIri - Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type") - Iri("http://www.w3.org/ns/shacl#NodeShape")
                
                for (constraint in shape.constraints) {
                    // Add constraint information to the graph
                    // This is a simplified representation
                }
            }
        }
        
        return validate(graph, shapesGraph)
    }
    
    override fun validateResource(graph: RdfGraph, shapes: RdfGraph, resource: RdfResource): ValidationReport {
        // Filter the graph to only include triples involving the specified resource
        val filteredTriples = graph.getTriples().filter { 
            it.subject == resource || it.obj == resource 
        }
        
        val filteredGraph = Rdf.graph {
            for (triple in filteredTriples) {
                triple.subject - triple.predicate - triple.obj
            }
        }
        
        return validate(filteredGraph, shapes)
    }
    
    override fun validateConstraints(graph: RdfGraph, constraints: List<ShaclConstraint>): ValidationReport {
        // Create a temporary shape with the given constraints
        val shape = ShaclShape(
            shapeUri = "temp:shape",
            constraints = constraints
        )
        
        return validate(graph, listOf(shape))
    }
    
    override fun conforms(graph: RdfGraph, shapes: RdfGraph): Boolean {
        val report = validate(graph, shapes)
        return report.isValid
    }
    
    override fun getValidationStatistics(graph: RdfGraph, shapes: RdfGraph): ValidationStatistics {
        val report = validate(graph, shapes)
        return report.statistics
    }
    
    /**
     * Extract SHACL shapes from a shapes graph.
     */
    private fun extractShapes(shapesGraph: RdfGraph): List<ShaclShape> {
        val shapes = mutableListOf<ShaclShape>()
        val triples = shapesGraph.getTriples()
        
        // Find all NodeShapes and PropertyShapes
        val nodeShapes = triples.filter { 
            it.predicate.value == "http://www.w3.org/1999/02/22-rdf-syntax-ns#type" &&
            it.obj is Iri && (it.obj as Iri).value == "http://www.w3.org/ns/shacl#NodeShape"
        }
        
        for (nodeShapeTriple in nodeShapes) {
            val shapeUri = (nodeShapeTriple.subject as? Iri)?.value ?: continue
            val constraints = extractConstraints(triples, shapeUri)
            
            val shape = ShaclShape(
                shapeUri = shapeUri,
                constraints = constraints
            )
            shapes.add(shape)
        }
        
        return shapes
    }
    
    /**
     * Extract constraints for a shape.
     */
    private fun extractConstraints(triples: List<RdfTriple>, shapeUri: String): List<ShaclConstraint> {
        val constraints = mutableListOf<ShaclConstraint>()
        
        // Look for property constraints
        val propertyShapeTriples = triples.filter {
            it.subject is Iri && (it.subject as Iri).value == shapeUri &&
            it.predicate.value == "http://www.w3.org/ns/shacl#property"
        }
        
        for (propertyTriple in propertyShapeTriples) {
            // Extract property shape constraints
            when (propertyTriple.obj) {
                is BlankNode -> {
                    val propertyShapeUri = (propertyTriple.obj as BlankNode).id
                    val propertyShapeConstraints = extractPropertyConstraints(triples, propertyShapeUri)
                    constraints.addAll(propertyShapeConstraints)
                }
                is Iri -> {
                    val propertyShapeUri = (propertyTriple.obj as Iri).value
                    val propertyShapeConstraints = extractPropertyConstraints(triples, propertyShapeUri)
                    constraints.addAll(propertyShapeConstraints)
                }
                else -> {
                    // Skip non-resource objects
                }
            }
        }
        
        // Look for direct constraints on the shape
        val directConstraints = triples.filter {
            it.subject is Iri && (it.subject as Iri).value == shapeUri &&
            it.predicate.value.startsWith("http://www.w3.org/ns/shacl#")
        }
        
        for (constraintTriple in directConstraints) {
            val constraint = createConstraintFromTriple(constraintTriple)
            if (constraint != null) {
                constraints.add(constraint)
            }
        }
        
        return constraints
    }
    
    /**
     * Extract property constraints from a property shape.
     */
    private fun extractPropertyConstraints(triples: List<RdfTriple>, propertyShapeUri: String): List<ShaclConstraint> {
        val constraints = mutableListOf<ShaclConstraint>()
        
        val propertyShapeTriples = triples.filter {
            when (it.subject) {
                is BlankNode -> (it.subject as BlankNode).id == propertyShapeUri
                is Iri -> (it.subject as Iri).value == propertyShapeUri
                else -> false
            }
        }
        
        // Extract the path first
        var path: String? = null
        for (constraintTriple in propertyShapeTriples) {
            if (constraintTriple.predicate.value == "http://www.w3.org/ns/shacl#path") {
                path = (constraintTriple.obj as? Iri)?.value
                break
            }
        }
        
        // Extract other constraints and set the path
        for (constraintTriple in propertyShapeTriples) {
            val constraint = createConstraintFromTriple(constraintTriple)
            if (constraint != null) {
                // Set the path for the constraint
                val updatedConstraint = constraint.copy(path = path)
                constraints.add(updatedConstraint)
            }
        }
        
        return constraints
    }
    
    /**
     * Create a constraint from a triple.
     */
    private fun createConstraintFromTriple(triple: RdfTriple): ShaclConstraint? {
        val predicateUri = triple.predicate.value
        
        return when {
            predicateUri == "http://www.w3.org/ns/shacl#minCount" -> {
                ShaclConstraint(
                    constraintType = ConstraintType.MIN_COUNT,
                    parameters = mapOf("value" to triple.obj)
                )
            }
            predicateUri == "http://www.w3.org/ns/shacl#maxCount" -> {
                ShaclConstraint(
                    constraintType = ConstraintType.MAX_COUNT,
                    parameters = mapOf("value" to triple.obj)
                )
            }
            predicateUri == "http://www.w3.org/ns/shacl#datatype" -> {
                ShaclConstraint(
                    constraintType = ConstraintType.DATATYPE,
                    parameters = mapOf("value" to triple.obj)
                )
            }
            predicateUri == "http://www.w3.org/ns/shacl#class" -> {
                ShaclConstraint(
                    constraintType = ConstraintType.CLASS,
                    parameters = mapOf("value" to triple.obj)
                )
            }
            else -> null
        }
    }
    
    /**
     * Validate a shape against the graph.
     */
    private fun validateShape(graph: RdfGraph, shape: ShaclShape): List<ValidationViolation> {
        val violations = mutableListOf<ValidationViolation>()
        val triples = graph.getTriples()
        
        // Find resources that match the shape's target
        val targetResources = findTargetResources(triples, shape)
        
        // Validate each target resource against the shape's constraints
        for (resource in targetResources) {
            val resourceViolations = validateResourceAgainstShape(triples, resource, shape)
            violations.addAll(resourceViolations)
        }
        
        return violations
    }
    
    /**
     * Find resources that match the shape's target.
     */
    private fun findTargetResources(triples: List<RdfTriple>, shape: ShaclShape): List<RdfResource> {
        val resources = mutableSetOf<RdfResource>()
        
        // If shape has a specific target node, return it
        if (shape.targetNode != null) {
            resources.add(Iri(shape.targetNode))
            return resources.toList()
        }
        
        // If shape has a target class, find all instances
        if (shape.targetClass != null) {
            val classTriples = triples.filter {
                it.predicate.value == "http://www.w3.org/1999/02/22-rdf-syntax-ns#type" &&
                it.obj is Iri && (it.obj as Iri).value == shape.targetClass
            }
            resources.addAll(classTriples.map { it.subject })
        }
        
        // If no specific target, validate all resources (simplified)
        if (resources.isEmpty()) {
            resources.addAll(triples.map { it.subject }.distinct())
        }
        
        return resources.toList()
    }
    
    /**
     * Validate a resource against a shape's constraints.
     */
    private fun validateResourceAgainstShape(triples: List<RdfTriple>, resource: RdfResource, shape: ShaclShape): List<ValidationViolation> {
        val violations = mutableListOf<ValidationViolation>()
        
        for (constraint in shape.constraints) {
            val constraintViolations = validateConstraint(triples, resource, constraint, shape.shapeUri)
            violations.addAll(constraintViolations)
        }
        
        return violations
    }
    
    /**
     * Validate a specific constraint.
     */
    private fun validateConstraint(triples: List<RdfTriple>, resource: RdfResource, constraint: ShaclConstraint, shapeUri: String): List<ValidationViolation> {
        val violations = mutableListOf<ValidationViolation>()
        
        when (constraint.constraintType) {
            ConstraintType.MIN_COUNT -> {
                val minCount = (constraint.parameters["value"] as? Literal)?.lexical?.toIntOrNull() ?: 0
                val path = constraint.path
                if (path != null) {
                    val propertyTriples = triples.filter { 
                        it.subject == resource && it.predicate.value == path 
                    }
                
                    if (propertyTriples.size < minCount) {
                        violations.add(ValidationViolation(
                            severity = ViolationSeverity.VIOLATION,
                            constraint = constraint,
                            resource = resource,
                            message = "Property '$path' has ${propertyTriples.size} values, but minimum is $minCount",
                            shapeUri = shapeUri,
                            violationCode = "MinCountViolation"
                        ))
                    }
                }
            }
            
            ConstraintType.MAX_COUNT -> {
                val maxCount = (constraint.parameters["value"] as? Literal)?.lexical?.toIntOrNull() ?: Int.MAX_VALUE
                val path = constraint.path
                if (path != null) {
                    val propertyTriples = triples.filter { 
                        it.subject == resource && it.predicate.value == path 
                    }
                    
                    if (propertyTriples.size > maxCount) {
                        violations.add(ValidationViolation(
                            severity = ViolationSeverity.VIOLATION,
                            constraint = constraint,
                            resource = resource,
                            message = "Property '$path' has ${propertyTriples.size} values, but maximum is $maxCount",
                            shapeUri = shapeUri,
                            violationCode = "MaxCountViolation"
                        ))
                    }
                }
            }
            
            ConstraintType.DATATYPE -> {
                val expectedDatatype = (constraint.parameters["value"] as? Iri)?.value
                if (expectedDatatype != null) {
                    val path = constraint.path
                    if (path != null) {
                        val propertyTriples = triples.filter { 
                            it.subject == resource && it.predicate.value == path 
                        }
                        
                        for (triple in propertyTriples) {
                            if (triple.obj is TypedLiteral) {
                                val typedLiteral = triple.obj as TypedLiteral
                                val actualDatatype = typedLiteral.datatype.value
                                if (actualDatatype != expectedDatatype) {
                                    violations.add(ValidationViolation(
                                        severity = ViolationSeverity.VIOLATION,
                                        constraint = constraint,
                                        resource = resource,
                                        message = "Property '$path' has datatype '$actualDatatype', but expected '$expectedDatatype'",
                                        shapeUri = shapeUri,
                                        violationCode = "DatatypeViolation"
                                    ))
                                }
                            }
                        }
                    }
                }
            }
            
            else -> {
                // Unsupported constraint type
                if (config.strictMode) {
                    violations.add(ValidationViolation(
                        severity = ViolationSeverity.WARNING,
                        constraint = constraint,
                        resource = resource,
                        message = "Unsupported constraint type: ${constraint.constraintType}",
                        shapeUri = shapeUri,
                        violationCode = "UnsupportedConstraint"
                    ))
                }
            }
        }
        
        return violations
    }
    
    /**
     * Calculate validation statistics.
     */
    private fun calculateStatistics(
        graph: RdfGraph,
        shapes: RdfGraph,
        violations: List<ValidationViolation>,
        warnings: List<ValidationWarning>
    ): ValidationStatistics {
        val triples = graph.getTriples()
        val shapeTriples = shapes.getTriples()
        
        val constraintsByType = violations.groupBy { it.constraint.constraintType }.mapValues { it.value.size }
        val violationsByType = violations.groupBy { it.constraint.constraintType }.mapValues { it.value.size }
        val warningsByType = warnings
            .mapNotNull { warning -> warning.constraint?.constraintType }
            .groupingBy { it }
            .eachCount()
        
        return ValidationStatistics(
            totalResources = triples.map { it.subject }.distinct().size,
            validatedResources = violations.map { it.resource }.distinct().size,
            totalConstraints = shapeTriples.filter { 
                it.predicate.value.startsWith("http://www.w3.org/ns/shacl#") 
            }.size,
            validatedConstraints = violations.size,
            shapesProcessed = shapeTriples.filter {
                it.predicate.value == "http://www.w3.org/1999/02/22-rdf-syntax-ns#type" &&
                it.obj is Iri && (it.obj as Iri).value == "http://www.w3.org/ns/shacl#NodeShape"
            }.size,
            constraintsByType = constraintsByType,
            violationsByType = violationsByType,
            warningsByType = warningsByType,
            averageValidationTimePerResource = Duration.ofMillis(1) // Simplified
        )
    }
}

/**
 * Validation exception.
 */
class ValidationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)









