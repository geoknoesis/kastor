package com.geoknoesis.kastor.gen.annotations

/**
 * Unified RDF code-generation marker. The processor distinguishes usage by
 * target and populated fields:
 *
 * - **Domain interface** ([kotlin.annotation.AnnotationTarget.CLASS]): set [iri]
 *   (and optional [prefixes] for QName resolution on that type). QNames in [iri]
 *   also resolve using `@file:Rdf(prefixes = …)` on the same source file when present.
 * - **Domain property** ([kotlin.annotation.AnnotationTarget.PROPERTY], or getter/setter):
 *   put `@Rdf(iri = …)` on the property line (preferred). Use `val` for read-only and `var` for
 *   read/write wrappers on scalar literals (`String`, `Int`, `Double`, `Boolean`) or a single object
 *   reference; `List<…>` mapped properties stay read-only even with `var`. QNames resolve using
 *   file-level then type-level [prefixes], same as the class.
 * - **Ontology / DSL source** ([kotlin.annotation.AnnotationTarget.CLASS] or
 *   [kotlin.annotation.AnnotationTarget.FILE]): set [shacl]; optional [context],
 *   [generateInterfaces], [generateWrappers], [generateDsl], [dslName], etc.
 */
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.PROPERTY_SETTER,
  AnnotationTarget.FILE,
)
@Retention(AnnotationRetention.SOURCE)
annotation class Rdf(
  /** IRI or QName for the RDF class (domain) or property predicate. */
  val iri: String = "",
  /**
   * Prefix bindings for expanding QNames in [iri] on this declaration.
   * Merged after (and therefore overriding) any `@file:Rdf(prefixes = …)` on the same source file.
   */
  val prefixes: Array<Prefix> = [],
  /** Path to SHACL shapes under `src/main/resources`. */
  val shacl: String = "",
  /** Path to JSON-LD `@context` under `src/main/resources` (optional). */
  val context: String = "",
  /** Target package for ontology-driven output; defaults to the declaration's package. */
  val packageName: String = "",
  val generateInterfaces: Boolean = true,
  val generateWrappers: Boolean = true,
  val generateDsl: Boolean = false,
  val dslName: String = "",
  /** Optional OWL/RDFS path for instance-DSL generation. */
  val ontologyPath: String = "",
  val validationMode: ValidationMode = ValidationMode.EMBEDDED,
  val validationAnnotations: ValidationAnnotations = ValidationAnnotations.JAKARTA,
  val externalValidatorClass: String = "",
  /** Generate an immutable data-class snapshot alongside (or instead of) interfaces + wrappers. */
  val generateDataClass: Boolean = false,
  /** Suffix appended to the shape name for the generated data class (e.g. "Record" → PersonRecord). */
  val dataClassSuffix: String = "",
  /** When true the data class implements the corresponding generated interface. */
  val dataClassImplementsInterface: Boolean = false,
  /** Controls how sh:class object properties are typed in the generated data class. */
  val nestedMode: NestedMode = NestedMode.INTERFACE,
  /**
   * When true (and [generateDataClass] is also true), the generated factory object also emits a
   * `toTriples(record, subject)` function that serializes a data-class snapshot back to RDF triples.
   * Callers control whether to append or replace existing triples.
   */
  val generateWriteSupport: Boolean = false,
)

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class Prefix(
  val name: String,
  val namespace: String,
)

/** How validation is wired into generated wrappers. */
enum class ValidationMode {
  EMBEDDED,
  EXTERNAL,
  NONE;

  companion object {
    val DEFAULT: ValidationMode = EMBEDDED

    fun ValidationMode.isEnabled(): Boolean = this != NONE

    fun ValidationMode.requiresExternalValidator(): Boolean = this == EXTERNAL
  }
}

/** Which Jakarta / javax / none validation annotations to emit on generated interfaces. */
enum class ValidationAnnotations {
  JAKARTA,
  JAVAX,
  NONE,
}

/**
 * Controls how `sh:class` object properties are typed inside a generated data class.
 * Has no effect on interface or wrapper generation.
 */
enum class NestedMode {
  /** Property type is the generated *interface* for the referenced shape (default). */
  INTERFACE,
  /** Property type is the generated *data class* for the referenced shape (fully recursive snapshot). */
  DATA_CLASS,
  /** Property type is `String` containing the object's IRI value. No sub-object materialisation. */
  IRI_ONLY,
}

/** Stable qualified name for KSP lookup. */
const val RDF_ANNOTATION_FQN: String = "com.geoknoesis.kastor.gen.annotations.Rdf"
