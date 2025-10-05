package com.example.ontomapper.processor

import com.example.ontomapper.processor.model.ClassModel
import com.example.ontomapper.processor.model.PropertyModel
import com.example.ontomapper.processor.model.PropertyType
import com.example.ontomapper.processor.codegen.WrapperGenerator
import com.example.ontomapper.processor.utils.QNameResolver
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import java.io.OutputStream

/**
 * KSP processor for generating RDF-backed domain object wrappers.
 * Analyzes domain interfaces and generates wrapper implementations.
 */
class OntoMapperProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {

    private val wrapperGenerator = WrapperGenerator(logger)
    private val processedClasses = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.info("OntoMapper processor starting...")
        val symbols = resolver.getSymbolsWithAnnotation("com.example.ontomapper.annotations.RdfClass")
        
        logger.info("Found ${symbols.iterator().asSequence().count()} symbols with RdfClass annotation")
        
        if (!symbols.iterator().hasNext()) {
            logger.info("No symbols found, returning empty list")
            return emptyList()
        }

        // Collect prefix mappings from all symbols
        val prefixMappings = collectPrefixMappings(resolver)
        logger.info("Collected ${prefixMappings.size} prefix mappings")

        val classModels = mutableListOf<ClassModel>()
        
        symbols.forEach { symbol ->
            if (symbol !is KSClassDeclaration) {
                logger.error("RdfClass annotation can only be applied to classes", symbol)
                return@forEach
            }
            
            val classModel = analyzeClass(symbol, prefixMappings)
            if (classModel != null) {
                classModels.add(classModel)
            }
        }
        
        // Generate wrapper classes
        classModels.forEach { classModel ->
            generateWrapper(classModel)
        }
        
        return symbols.filterNot { it.validate() }.toList()
    }
    
    private fun collectPrefixMappings(resolver: Resolver): Map<String, String> {
        val prefixMappings = mutableMapOf<String, String>()
        
        // Look for PrefixMapping annotations
        val prefixMappingSymbols = resolver.getSymbolsWithAnnotation("com.example.ontomapper.annotations.PrefixMapping")
        
        prefixMappingSymbols.forEach { symbol ->
            val prefixMappingAnnotation = symbol.annotations.find { 
                it.shortName.asString() == "PrefixMapping" 
            }
            
            if (prefixMappingAnnotation != null) {
                val prefixesArgument = prefixMappingAnnotation.arguments.find { 
                    it.name?.asString() == "prefixes" 
                }
                
                if (prefixesArgument != null) {
                    // Extract prefix mappings from the annotation
                    val prefixesArray = prefixesArgument.value as? List<*>
                    prefixesArray?.forEach { prefixElement ->
                        if (prefixElement is KSType) {
                            // This is a Prefix annotation instance
                            // We need to extract the name and namespace values
                            // For now, we'll use a simplified approach
                            logger.info("Found Prefix annotation: ${prefixElement}")
                        }
                    }
                    logger.info("Found PrefixMapping annotation on ${symbol}")
                }
            }
        }
        
        return prefixMappings
    }

    private fun analyzeClass(classDecl: KSClassDeclaration, prefixMappings: Map<String, String>): ClassModel? {
        val qualifiedName = classDecl.qualifiedName?.asString()
            ?: return null
            
        if (qualifiedName in processedClasses) {
            return null
        }
        processedClasses.add(qualifiedName)
        
        val properties = mutableListOf<PropertyModel>()
        
        // Analyze properties (getters)
        classDecl.getAllProperties().forEach { property ->
            val propertyModel = analyzeProperty(property, prefixMappings)
            if (propertyModel != null) {
                properties.add(propertyModel)
            }
        }
        
        // Extract and resolve RdfClass IRI
        val rdfClassAnnotation = classDecl.annotations.find { 
            it.shortName.asString() == "RdfClass" 
        }
        
        val classIri = if (rdfClassAnnotation != null) {
            val iriRaw = rdfClassAnnotation.arguments
                .find { it.name?.asString() == "iri" }
                ?.value as? String
                ?: ""
                
            if (iriRaw.isNotEmpty() && QNameResolver.isQName(iriRaw)) {
                try {
                    QNameResolver.resolveQName(iriRaw, prefixMappings)
                } catch (e: IllegalArgumentException) {
                    logger.error("Failed to resolve QName '$iriRaw': ${e.message}", classDecl)
                    iriRaw
                }
            } else {
                iriRaw
            }
        } else {
            ""
        }

        return ClassModel(
            qualifiedName = qualifiedName,
            simpleName = classDecl.simpleName.asString(),
            packageName = classDecl.packageName.asString(),
            classIri = classIri,
            properties = properties
        )
    }
    
    private fun analyzeProperty(property: KSPropertyDeclaration, prefixMappings: Map<String, String>): PropertyModel? {
        logger.info("Analyzing property: ${property.simpleName.asString()}")
        logger.info("Property annotations: ${property.annotations.map { it.shortName.asString() }}")
        logger.info("Getter annotations: ${property.getter?.annotations?.map { it.shortName.asString() }}")
        
        // Look for RdfProperty annotation on the property itself or its getter
        val rdfPropertyAnnotation = property.annotations
            .find { it.shortName.asString() == "RdfProperty" }
            ?: property.getter?.annotations
                ?.find { it.shortName.asString() == "RdfProperty" }
            ?: return null
            
        val predicateIriRaw = rdfPropertyAnnotation.arguments
            .find { it.name?.asString() == "iri" }
            ?.value as? String
            ?: return null
            
        // Resolve QName to full IRI if needed
        val predicateIri = if (QNameResolver.isQName(predicateIriRaw)) {
            try {
                QNameResolver.resolveQName(predicateIriRaw, prefixMappings)
            } catch (e: IllegalArgumentException) {
                logger.error("Failed to resolve QName '$predicateIriRaw': ${e.message}", property)
                return null
            }
        } else {
            predicateIriRaw
        }
            
        val returnType = property.type.resolve()
        val kotlinType = when {
            returnType.declaration.qualifiedName?.asString() == "kotlin.String" -> "String"
            returnType.declaration.qualifiedName?.asString() == "kotlin.Int" -> "Int"
            returnType.declaration.qualifiedName?.asString() == "kotlin.Boolean" -> "Boolean"
            returnType.declaration.qualifiedName?.asString() == "kotlin.Double" -> "Double"
            returnType.declaration.qualifiedName?.asString() == "kotlin.collections.List" -> {
                val typeArg = returnType.arguments.firstOrNull()?.type?.resolve()
                val elementType = typeArg?.declaration?.qualifiedName?.asString()
                "List<$elementType>"
            }
            else -> returnType.declaration.qualifiedName?.asString() ?: "Any"
        }
        
        val propertyType = when {
            kotlinType == "List<String>" || kotlinType == "List<Int>" || kotlinType == "List<Double>" || kotlinType == "List<Boolean>" -> {
                logger.info("Property ${property.simpleName.asString()} classified as LITERAL with type $kotlinType")
                PropertyType.LITERAL
            }
            kotlinType.startsWith("List<") -> {
                logger.info("Property ${property.simpleName.asString()} classified as OBJECT_LIST with type $kotlinType")
                PropertyType.OBJECT_LIST
            }
            kotlinType == "String" -> {
                logger.info("Property ${property.simpleName.asString()} classified as LITERAL with type $kotlinType")
                PropertyType.LITERAL
            }
            kotlinType == "Int" || kotlinType == "Double" || kotlinType == "Boolean" -> {
                logger.info("Property ${property.simpleName.asString()} classified as LITERAL with type $kotlinType")
                PropertyType.LITERAL
            }
            else -> {
                logger.info("Property ${property.simpleName.asString()} classified as OBJECT with type $kotlinType")
                PropertyType.OBJECT
            }
        }
        
        return PropertyModel(
            name = property.simpleName.asString(),
            kotlinType = kotlinType,
            predicateIri = predicateIri,
            type = propertyType
        )
    }
    
    private fun generateWrapper(classModel: ClassModel) {
        val fileName = "${classModel.simpleName}Wrapper"
        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(false),
            packageName = classModel.packageName,
            fileName = fileName
        )
        
        val code = wrapperGenerator.generateWrapper(classModel)
        file.write(code.toByteArray())
        file.close()
    }
}

/**
 * Processor provider for KSP.
 */
class OntoMapperProcessorProvider : SymbolProcessorProvider {
    override fun create(
        environment: SymbolProcessorEnvironment
    ): SymbolProcessor {
        return OntoMapperProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
            options = environment.options
        )
    }
}
