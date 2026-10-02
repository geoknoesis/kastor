package com.geoknoesis.kastor.gen.processor.internal.core

import com.geoknoesis.kastor.gen.annotations.Rdf

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.annotations.RDF_ANNOTATION_FQN
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.*

/**
 * Parses `@Rdf` annotations and extracts generation requests.
 */
public class AnnotationParser(private val logger: KSPLogger) {

  public data class OntologyGenerationRequest(
    val shaclPath: String,
    val contextPath: String,
    val targetPackage: String,
    val generateInterfaces: Boolean,
    val generateWrappers: Boolean,
    val validationMode: ValidationMode,
    val validationAnnotations: ValidationAnnotations,
    val externalValidatorClass: String?,
    val generateDataClass: Boolean = false,
    val dataClassSuffix: String = "Record",
    val dataClassImplementsInterface: Boolean = false,
    val nestedMode: NestedMode = NestedMode.INTERFACE,
    val generateWriteSupport: Boolean = false,
  )

  public data class InstanceDslGenerationRequest(
    val ontologyPath: String?,
    val shaclPath: String,
    val contextPath: String?,
    val dslName: String,
    val targetPackage: String,
  )

  internal fun parseOntologyAnnotation(annotation: KSAnnotation, packageName: String): OntologyGenerationRequest? =
    parseOntologyFromRdf(annotation, packageName)

  internal fun parseInstanceDslAnnotation(annotation: KSAnnotation, packageName: String): InstanceDslGenerationRequest? =
    parseInstanceDslFromRdf(annotation, packageName)

  /** Ontology-driven interfaces/wrappers when [Rdf.shacl] is set and generation toggles allow it. */
  public fun parseOntologyFromRdf(annotation: KSAnnotation, defaultPackage: String): OntologyGenerationRequest? {
    if (annotation.shortName.asString() != "Rdf") return null
    val generateDsl = getAnnotationValue(annotation, "generateDsl") as? Boolean ?: false
    if (generateDsl) return null

    val shaclPath = nonBlank(getAnnotationValue(annotation, "shacl") as? String) ?: return null
    val iri = nonBlank(getAnnotationValue(annotation, "iri") as? String)
    if (!iri.isNullOrBlank()) return null

    val generateInterfaces = getAnnotationValue(annotation, "generateInterfaces") as? Boolean ?: true
    val generateWrappers = getAnnotationValue(annotation, "generateWrappers") as? Boolean ?: true
    val generateDataClass = getAnnotationValue(annotation, "generateDataClass") as? Boolean ?: false
    if (!generateInterfaces && !generateWrappers && !generateDataClass) return null

    val contextRaw = getAnnotationValue(annotation, "context") as? String ?: ""
    val targetPackage = nonBlank(getAnnotationValue(annotation, "packageName") as? String) ?: defaultPackage
    val validationMode = parseValidationMode(getAnnotationValue(annotation, "validationMode"))
    val validationAnnotations = parseValidationAnnotations(getAnnotationValue(annotation, "validationAnnotations"))
    val externalValidatorClass = getAnnotationValue(annotation, "externalValidatorClass") as? String
    val dataClassSuffix = getAnnotationValue(annotation, "dataClassSuffix") as? String ?: "Record"
    val dataClassImplementsInterface = getAnnotationValue(annotation, "dataClassImplementsInterface") as? Boolean ?: false
    val nestedMode = parseNestedMode(getAnnotationValue(annotation, "nestedMode"))
    val generateWriteSupport = getAnnotationValue(annotation, "generateWriteSupport") as? Boolean ?: false

    return OntologyGenerationRequest(
      shaclPath = shaclPath,
      contextPath = contextRaw,
      targetPackage = targetPackage,
      generateInterfaces = generateInterfaces,
      generateWrappers = generateWrappers,
      validationMode = validationMode,
      validationAnnotations = validationAnnotations,
      externalValidatorClass = externalValidatorClass,
      generateDataClass = generateDataClass,
      dataClassSuffix = dataClassSuffix,
      dataClassImplementsInterface = dataClassImplementsInterface,
      nestedMode = nestedMode,
      generateWriteSupport = generateWriteSupport,
    )
  }

  /** Instance DSL when [Rdf.generateDsl] is true with [Rdf.dslName] and [Rdf.shacl]. */
  public fun parseInstanceDslFromRdf(annotation: KSAnnotation, defaultPackage: String): InstanceDslGenerationRequest? {
    if (annotation.shortName.asString() != "Rdf") return null
    val generateDsl = getAnnotationValue(annotation, "generateDsl") as? Boolean ?: false
    if (!generateDsl) return null
    val shaclPath = nonBlank(getAnnotationValue(annotation, "shacl") as? String) ?: run {
      logger.error("@Rdf(generateDsl = true) requires a non-blank shacl path")
      return null
    }
    val dslName = nonBlank(getAnnotationValue(annotation, "dslName") as? String) ?: run {
      logger.error("@Rdf(generateDsl = true) requires dslName")
      return null
    }
    val ontologyPath = nonBlank(getAnnotationValue(annotation, "ontologyPath") as? String)
    val contextPath = nonBlank(getAnnotationValue(annotation, "context") as? String)
    val targetPackage = nonBlank(getAnnotationValue(annotation, "packageName") as? String) ?: defaultPackage

    return InstanceDslGenerationRequest(
      ontologyPath = ontologyPath,
      shaclPath = shaclPath,
      contextPath = contextPath,
      dslName = dslName,
      targetPackage = targetPackage,
    )
  }

  public companion object {
    public const val RDF_ANNOTATION: String = RDF_ANNOTATION_FQN
  }

  private fun nonBlank(s: String?): String? = s?.takeIf { it.isNotBlank() }

  private fun getAnnotationValue(annotation: KSAnnotation, name: String): Any? =
    annotation.arguments.find { it.name?.asString() == name }?.value

  private fun parseValidationMode(value: Any?): ValidationMode = parseEnum(value, ValidationMode.EMBEDDED)

  private fun parseValidationAnnotations(value: Any?): ValidationAnnotations = parseEnum(value, ValidationAnnotations.JAKARTA)

  private fun parseNestedMode(value: Any?): NestedMode = parseEnum(value, NestedMode.INTERFACE)

  /**
   * Enum annotation arguments arrive as a KSType (KSP1), an enum-entry KSClassDeclaration (KSP2), a KSName or
   * a String depending on the KSP version; all are mapped by simple name. Unknown values are reported.
   */
  private inline fun <reified E : Enum<E>> parseEnum(value: Any?, default: E): E {
    val name = when (value) {
      null -> return default
      is Enum<*> -> value.name
      is KSType -> value.declaration.simpleName.asString()
      is KSClassDeclaration -> value.simpleName.asString()
      is KSName -> value.getShortName()
      else -> value.toString().substringAfterLast('.')
    }
    return enumValues<E>().firstOrNull { it.name == name } ?: run {
      logger.warn("Unknown ${E::class.simpleName} value '$value' in @Rdf; using $default")
      default
    }
  }
}

/**
 * Whether this annotation is Kastor's `@Rdf` (`com.geoknoesis.kastor.gen.annotations.Rdf`): the short name is
 * compared first (cheap), then the resolved annotation class, so an annotation of another library that is also
 * named `Rdf` is never taken for it.
 */
internal fun KSAnnotation.isKastorRdf(): Boolean =
    shortName.asString() == "Rdf" &&
        annotationType.resolve().declaration.qualifiedName?.asString() == RDF_ANNOTATION_FQN
