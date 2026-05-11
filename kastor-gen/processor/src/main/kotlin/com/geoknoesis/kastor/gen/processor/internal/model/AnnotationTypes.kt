package com.geoknoesis.kastor.gen.processor.internal.model

/**
 * Sealed class representing annotation-driven generation kinds (legacy naming for diagnostics).
 */
internal sealed class GenerationAnnotationType(val name: String) {
  object FromOntology : GenerationAnnotationType("RdfOntology")
  object InstanceDsl : GenerationAnnotationType("RdfInstanceDsl")

  companion object {
    const val RDF_ANNOTATION_FQN = "com.geoknoesis.kastor.gen.annotations.Rdf"

    fun fromName(name: String): GenerationAnnotationType? {
      return when (name) {
        "Rdf", "GenerateFromOntology" -> FromOntology
        "GenerateInstanceDsl" -> InstanceDsl
        else -> null
      }
    }
  }
}
