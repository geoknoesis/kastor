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
 * Process-wide policy applied by generated wrappers and data-class factories while reading values.
 *
 * Ill-typed values (e.g. `"abc"^^xsd:integer` on an `xsd:integer` property) used to disappear silently from
 * list-valued properties. By default they now fail loudly; set [illTypedValues] to [IllTypedValueHandling.SKIP]
 * to keep reading the remaining values (each skipped value is logged).
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
}
