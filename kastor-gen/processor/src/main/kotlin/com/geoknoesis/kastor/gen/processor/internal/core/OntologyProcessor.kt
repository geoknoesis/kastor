package com.geoknoesis.kastor.gen.processor.internal.core

import com.geoknoesis.kastor.gen.processor.api.exceptions.InvalidConfigurationException
import com.geoknoesis.kastor.gen.processor.api.exceptions.ProcessingException
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import java.io.File

/**
 * KSP processor for generating domain interfaces, wrappers, and instance DSL from SHACL / JSON-LD,
 * driven by `@Rdf` on classes or files.
 *
 * Each request (package + ontology files) is generated at most once per compilation, across all KSP rounds: a
 * symbol presented again in a later round (for example because it references types generated in the first
 * round) is not generated twice. Only symbols this processor could not handle and that do not validate are
 * deferred to the next round.
 *
 * KSP does not observe the SHACL / JSON-LD files themselves. Unless [RESOURCES_TRACKED_OPTION] is `true` (set it
 * after declaring the files as inputs of the KSP task), a warning names every ontology file read, pointing to the
 * `com.geoknoesis.kastor.gen` Gradle plugin, which tracks them as task inputs.
 */
public class OntologyProcessor internal constructor(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
  private val options: Map<String, String>,
  private val isValid: (KSAnnotated) -> Boolean,
) : SymbolProcessor {

  public constructor(codeGenerator: CodeGenerator, logger: KSPLogger, options: Map<String, String>) :
    this(codeGenerator, logger, options, { it.validate() })

  private val annotationParser = AnnotationParser(logger)
  private val coordinator = GenerationCoordinator(logger, codeGenerator)
  // Requests already generated, by the output they would produce (package + source). A repeat of an identical request
  // is skipped; a repeat that asks for different options would silently lose them, so it is reported instead.
  private val generatedOntologies = mutableMapOf<String, Any>()
  private val generatedDsls = mutableMapOf<String, Any>()
  private val warnedFiles = mutableSetOf<File>()
  private val resourcesTracked = options[RESOURCES_TRACKED_OPTION]?.trim()?.toBoolean() == true
  private var reader: OntologyFileReader? = null

  private fun fileReader(): OntologyFileReader =
    reader ?: OntologyFileReader(logger, resourceRoots()).also { reader = it }

  /**
   * [RESOURCES_OPTION] entries: absolute paths as-is; relative ones against [PROJECT_DIR_OPTION] when set.
   * Without a project directory a relative entry would silently depend on the compiler daemon's working
   * directory, so it must exist there or generation fails.
   */
  private fun resourceRoots(): List<File> {
    val projectDir = options[PROJECT_DIR_OPTION]?.takeIf { it.isNotBlank() }?.let(::File)
    return options[RESOURCES_OPTION].orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.map { entry ->
      val file = File(entry)
      when {
        file.isAbsolute -> file
        projectDir != null -> File(projectDir, entry)
        file.absoluteFile.isDirectory -> file.absoluteFile
        else -> {
          val reason = "relative entry '$entry' of $RESOURCES_OPTION does not exist relative to the compiler working " +
            "directory ${File("").absolutePath}; use an absolute path or set the KSP option $PROJECT_DIR_OPTION " +
            "(e.g. ksp { arg(\"$PROJECT_DIR_OPTION\", projectDir.absolutePath) })"
          logger.error(reason)
          throw InvalidConfigurationException(config = RESOURCES_OPTION, reason = reason)
        }
      }
    }
  }

  override fun process(resolver: Resolver): List<KSAnnotated> {
    logger.info("Ontology processor starting…")
    val deferred = mutableListOf<KSAnnotated>()

    resolver.getSymbolsWithAnnotation(AnnotationParser.RDF_ANNOTATION).forEach { symbol ->
      val defaultPkg = when (symbol) {
        is KSClassDeclaration -> symbol.packageName.asString()
        is KSFile -> symbol.packageName.asString()
        else -> return@forEach
      }
      var handled = false

      symbol.annotations.filter { it.isKastorRdf() }.forEach { ann ->
        annotationParser.parseInstanceDslFromRdf(ann, defaultPkg)?.let { request ->
          handled = true
          val key = "${request.targetPackage}|${request.dslName}|${request.shaclPath}"
          if (!firstOfKind(generatedDsls, key, request, symbol)) return@let
          try {
            val reader = fileReader()
            val model = reader.loadOntologyModel(
              request.shaclPath,
              request.contextPath?.takeIf { it.isNotBlank() },
              near = sourceFileOf(symbol),
            )
            // The ontology named by `ontologyPath` is read with the shapes: a file that is missing or is not an
            // ontology is reported here, and its named classes (anonymous class expressions are skipped) are logged.
            request.ontologyPath?.let { reader.loadOntologyClasses(it, near = sourceFileOf(symbol)) }
            warnUntracked(reader)
            coordinator.generateInstanceDsl(
              model = model,
              dslName = request.dslName,
              packageName = request.targetPackage,
              sources = listOfNotNull((symbol as? KSDeclaration)?.containingFile ?: (symbol as? KSFile)),
            )
          } catch (e: InvalidConfigurationException) {
            throw e
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
          handled = true
          val key = "${request.targetPackage}|${request.shaclPath}"
          if (!firstOfKind(generatedOntologies, key, request, symbol)) return@let
          try {
            val reader = fileReader()
            val model = reader.loadOntologyModel(
              request.shaclPath,
              request.contextPath.takeIf { it.isNotBlank() },
              near = sourceFileOf(symbol),
            )
            warnUntracked(reader)
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
          } catch (e: InvalidConfigurationException) {
            throw e
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

      // Generation needs only the annotation's constant arguments, so handled symbols are never deferred (a file
      // that references the generated types would otherwise stay invalid forever). Other symbols are deferred
      // while they do not validate.
      if (!handled && !isValid(symbol)) deferred += symbol
    }

    return deferred
  }

  /**
   * True when [request] is the first for [key]. A later request equal to the first is a harmless repeat; a different
   * one (another context file or option) cannot be generated into the same package and is reported as an error.
   */
  private fun firstOfKind(seen: MutableMap<String, Any>, key: String, request: Any, symbol: KSAnnotated): Boolean {
    val first = seen.putIfAbsent(key, request) ?: return true
    if (first != request) {
      logger.error(
        "kastor-gen: a second @Rdf annotation for '$key' differs from the first and was ignored: $request " +
          "(first: $first). Annotate the same shapes once, or use a different packageName.",
        symbol,
      )
    }
    return false
  }

  private fun warnUntracked(reader: OntologyFileReader) {
    if (resourcesTracked) return
    reader.resolvedFiles.filter { warnedFiles.add(it) }.forEach { file ->
      logger.warn(
        "kastor-gen: KSP does not track changes to the ontology file ${file.path}; after editing it the generated " +
          "code stays stale until an annotated source changes or the build is cleaned. Prefer the " +
          "com.geoknoesis.kastor.gen Gradle plugin, which tracks ontology files as task inputs, or declare the file " +
          "as an input of the KSP task (tasks.named(\"kspKotlin\") { inputs.files(...) }) and set the KSP option " +
          "$RESOURCES_TRACKED_OPTION=true."
      )
    }
  }

  private fun sourceFileOf(symbol: KSAnnotated): File? =
    ((symbol as? KSDeclaration)?.containingFile ?: (symbol as? KSFile))?.filePath?.let(::File)

  public companion object {
    /**
     * KSP option: extra directories (path-separator separated) to resolve `@Rdf(shacl/context)` paths against.
     * Relative entries are resolved against [PROJECT_DIR_OPTION]; without it they must exist relative to the
     * compiler's working directory (which is the Gradle daemon's, not the project's), otherwise generation fails.
     */
    public const val RESOURCES_OPTION: String = "kastor.gen.resources"

    /** KSP option: the project directory that relative [RESOURCES_OPTION] entries are resolved against. */
    public const val PROJECT_DIR_OPTION: String = "kastor.gen.projectDir"

    /**
     * KSP option: `true` when the ontology files are declared as inputs of the KSP task, so edits to them
     * re-run KSP; silences the staleness warning.
     */
    public const val RESOURCES_TRACKED_OPTION: String = "kastor.gen.resources.tracked"
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
