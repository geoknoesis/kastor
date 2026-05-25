package com.geoknoesis.kastor.gen.processor.internal.core

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.annotations.RDF_ANNOTATION_FQN
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.*

/**
 * Parses `@Rdf` annotations and extracts generation requests.
 */
class AnnotationParser(private val logger: KSPLogger) {

  data class OntologyGenerationRequest(
    val shaclPath: String,
    val contextPath: String,
    val targetPackage: String,
    val generateInterfaces: Boolean,
    val generateWrappers: Boolean,
    val validationMode: ValidationMode,
    val validationAnnotations: ValidationAnnotations,
    val externalValidatorClass: String?,
    val generateDataClass: Boolean = false,
    val dataClassSuffix: String = "",
    val dataClassImplementsInterface: Boolean = false,
    val nestedMode: NestedMode = NestedMode.INTERFACE,
    val generateWriteSupport: Boolean = false,
  )

  data class InstanceDslGenerationRequest(
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
  fun parseOntologyFromRdf(annotation: KSAnnotation, defaultPackage: String): OntologyGenerationRequest? {
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
    val dataClassSuffix = getAnnotationValue(annotation, "dataClassSuffix") as? String ?: ""
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
  fun parseInstanceDslFromRdf(annotation: KSAnnotation, defaultPackage: String): InstanceDslGenerationRequest? {
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

  companion object {
    const val RDF_ANNOTATION: String = RDF_ANNOTATION_FQN
  }

  private fun nonBlank(s: String?): String? = s?.takeIf { it.isNotBlank() }

  private fun getAnnotationValue(annotation: KSAnnotation, name: String): Any? =
    annotation.arguments.find { it.name?.asString() == name }?.value

  private fun parseValidationMode(value: Any?): ValidationMode {
    return when (value) {
      is ValidationMode -> value
      is KSType -> ValidationMode.valueOf(value.declaration.simpleName.asString())
      is KSName -> ValidationMode.valueOf(value.asString())
      is String -> ValidationMode.valueOf(value)
      else -> ValidationMode.EMBEDDED
    }
  }

  private fun parseValidationAnnotations(value: Any?): ValidationAnnotations {
    return when (value) {
      is ValidationAnnotations -> value
      is KSType -> ValidationAnnotations.valueOf(value.declaration.simpleName.asString())
      is KSName -> ValidationAnnotations.valueOf(value.asString())
      is String -> ValidationAnnotations.valueOf(value)
      else -> ValidationAnnotations.JAKARTA
    }
  }

  private fun parseNestedMode(value: Any?): NestedMode {
    return when (value) {
      is NestedMode -> value
      is KSType -> NestedMode.valueOf(value.declaration.simpleName.asString())
      is KSName -> NestedMode.valueOf(value.asString())
      is String -> NestedMode.valueOf(value)
      else -> NestedMode.INTERFACE
    }
  }
}
