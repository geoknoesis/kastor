package com.geoknoesis.kastor.gen.processor.internal.core

import com.geoknoesis.kastor.gen.processor.api.exceptions.ProcessingException
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import java.io.File

/**
 * KSP processor for generating domain interfaces, wrappers, and instance DSL from SHACL / JSON-LD,
 * driven by `@Rdf` on classes or files.
 */
public class OntologyProcessor(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
  private val options: Map<String, String>,
) : SymbolProcessor {

  private val annotationParser = AnnotationParser(logger)
  private val fileReader = OntologyFileReader(
    logger,
    options[RESOURCES_OPTION].orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.map(::File),
  )
  private val coordinator = GenerationCoordinator(logger, codeGenerator)

  override fun process(resolver: Resolver): List<KSAnnotated> {
    logger.info("Ontology processor starting…")
    val processed = mutableListOf<KSAnnotated>()
    val seenOntology = mutableSetOf<String>()
    val seenDsl = mutableSetOf<String>()

    val symbols = resolver.getSymbolsWithAnnotation(AnnotationParser.RDF_ANNOTATION)
    symbols.forEach { symbol ->
      val defaultPkg = when (symbol) {
        is KSClassDeclaration -> symbol.packageName.asString()
        is KSFile -> symbol.packageName.asString()
        else -> return@forEach
      }

      symbol.annotations.filter { it.shortName.asString() == "Rdf" }.forEach { ann ->
        annotationParser.parseInstanceDslFromRdf(ann, defaultPkg)?.let { request ->
          val key = "${request.targetPackage}|${request.dslName}|${request.shaclPath}"
          if (key in seenDsl) return@let
          seenDsl.add(key)
          try {
            val model = fileReader.loadOntologyModel(
              request.shaclPath,
              request.contextPath?.takeIf { it.isNotBlank() },
              near = sourceFileOf(symbol),
            )
            coordinator.generateInstanceDsl(
              model = model,
              dslName = request.dslName,
              packageName = request.targetPackage,
              sources = listOfNotNull((symbol as? KSDeclaration)?.containingFile ?: (symbol as? KSFile)),
            )
            processed.add(symbol)
          } catch (e: Exception) {
            logger.error("Error processing @Rdf instance DSL: ${e.message}", symbol)
            logger.exception(e)
            throw ProcessingException(
              message = "Failed to process @Rdf(generateDsl = true)",
              annotationName = "Rdf",
              cause = e,
            )
          }
        }

        annotationParser.parseOntologyFromRdf(ann, defaultPkg)?.let { request ->
          val key = "${request.targetPackage}|${request.shaclPath}|${request.contextPath}"
          if (key in seenOntology) return@let
          seenOntology.add(key)
          try {
            val model = fileReader.loadOntologyModel(
              request.shaclPath,
              request.contextPath.takeIf { it.isNotBlank() },
              near = sourceFileOf(symbol),
            )
            coordinator.generateFromOntology(
              model = model,
              packageName = request.targetPackage,
              sources = listOfNotNull((symbol as? KSDeclaration)?.containingFile ?: (symbol as? KSFile)),
              generateInterfaces = request.generateInterfaces,
              generateWrappers = request.generateWrappers,
              validationMode = request.validationMode,
              validationAnnotations = request.validationAnnotations,
              externalValidatorClass = request.externalValidatorClass,
              generateDataClass = request.generateDataClass,
              dataClassSuffix = request.dataClassSuffix,
              dataClassImplementsInterface = request.dataClassImplementsInterface,
              nestedMode = request.nestedMode,
              generateWriteSupport = request.generateWriteSupport,
            )
            processed.add(symbol)
          } catch (e: Exception) {
            logger.error("Error processing @Rdf ontology generation: ${e.message}", symbol)
            logger.exception(e)
            throw ProcessingException(
              message = "Failed to process @Rdf ontology generation",
              annotationName = "Rdf",
              cause = e,
            )
          }
        }
      }
    }

    if (processed.isEmpty()) {
      logger.info("No @Rdf ontology/DSL annotations found")
      return emptyList()
    }

    return processed.filterNot { it.validate() }.toList()
  }

  private fun sourceFileOf(symbol: KSAnnotated): File? =
    ((symbol as? KSDeclaration)?.containingFile ?: (symbol as? KSFile))?.filePath?.let(::File)

  public companion object {
    /** KSP option: extra directories (path-separator separated) to resolve `@Rdf(shacl/context)` paths against. */
    public const val RESOURCES_OPTION: String = "kastor.gen.resources"
  }
}

public class OntologyProcessorProvider : SymbolProcessorProvider {
  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
    OntologyProcessor(
      codeGenerator = environment.codeGenerator,
      logger = environment.logger,
      options = environment.options,
    )
}
