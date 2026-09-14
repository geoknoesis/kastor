package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Literal
import org.slf4j.LoggerFactory

/** What generated readers do with a literal whose lexical form is not valid for the property's Kotlin type. */
enum class IllTypedValueHandling {
  /** Throw a [MaterializationException] naming the value, its datatype and the property (default). */
  THROW,

  /** Leave the value out of the result and log a warning. */
  SKIP,
}

/**
 * Rules applied by every generated reader: live wrappers and data-class factories generated from SHACL, and
 * wrappers generated for hand-written `@Rdf` interfaces.
 *
 * - **Ill-typed values** (e.g. `"abc"^^xsd:integer` read as a number) throw [MaterializationException] by default;
 *   set [illTypedValues] to [IllTypedValueHandling.SKIP] to leave them out (each skipped value is logged).
 * - **Missing required values** (a single-valued member with `sh:minCount >= 1` or a non-null `@Rdf` member without
 *   a value, or a list member with `sh:minCount >= 1` and no values) always throw [MaterializationException] naming
 *   the member, path and (for SHACL-generated types) shape. With [IllTypedValueHandling.SKIP] a required member
 *   whose only values were skipped is missing, so it throws too.
 */
object MaterializationPolicy {

  private val log = LoggerFactory.getLogger(MaterializationPolicy::class.java)

  @Volatile
  @JvmStatic
  var illTypedValues: IllTypedValueHandling = IllTypedValueHandling.THROW

  /**
   * Called by generated code for a [literal] of [property] that could not be decoded into [expected].
   *
   * @return `null` (the value is skipped) under [IllTypedValueHandling.SKIP]
   * @throws MaterializationException under [IllTypedValueHandling.THROW]
   */
  @JvmStatic
  fun illTyped(literal: Literal, property: String, expected: String): Nothing? {
    val message = "Ill-typed value \"${literal.lexical}\"^^<${literal.datatype.value}> for $property " +
      "(expected $expected)"
    when (illTypedValues) {
      IllTypedValueHandling.THROW -> throw MaterializationException(message)
      IllTypedValueHandling.SKIP -> log.warn("{}; value skipped", message)
    }
    return null
  }

  /**
   * Called by generated code when a required member ([property] describes member, path and shape) has no value.
   *
   * @throws MaterializationException always
   */
  @JvmStatic
  fun missingRequired(property: String): Nothing =
    throw MaterializationException("Required value missing for $property")
}
