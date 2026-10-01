package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import org.slf4j.LoggerFactory

/**
 * What generated readers do with a value they cannot represent: a literal whose lexical form is not valid for the
 * property's Kotlin type, or a value of the wrong term kind (e.g. a literal where an IRI is expected).
 */
enum class IllTypedValueHandling {
  /** Throw a [MaterializationException] naming the value and the property (default). */
  THROW,

  /** Leave the value out of the result and log a warning. */
  SKIP,
}

/**
 * Rules applied by every generated reader: live wrappers and data-class factories generated from SHACL, and
 * wrappers generated for hand-written `@Rdf` interfaces.
 *
 * - **Ill-typed values** (e.g. `"abc"^^xsd:integer` read as a number) and, for SHACL-generated readers, **values of an
 *   unexpected term kind** (a literal for an IRI or object member, an IRI for a literal member) throw
 *   [MaterializationException] by default; with [IllTypedValueHandling.SKIP] they are left out (each skipped value is
 *   logged).
 * - **Missing required values** (a single-valued member with `sh:minCount >= 1` or a non-null `@Rdf` member without
 *   a value, or a list member with `sh:minCount >= 1` and no values) always throw [MaterializationException] naming
 *   the member, path and (for SHACL-generated types) shape. With [IllTypedValueHandling.SKIP] a required member
 *   whose only values were skipped is missing, so it throws too.
 *
 * ## Scope
 * [illTypedValues] is the process-wide default. [withIllTypedValues] overrides it for the current thread while a
 * block runs (nested scopes restore the enclosing value), so tests and request handlers can use a different policy
 * without racing other threads. The override is thread-local: work handed to other threads (executors, coroutines
 * resuming on another thread) sees the process-wide default.
 *
 * ## Wrappers capture the handling when they are created
 * Generated wrappers read their properties on demand, so each wrapper captures the handling in effect **when it is
 * created** (by `OntoMapper.materialize` or a factory) and applies it to every later read of every member, even after
 * the [withIllTypedValues] block has ended or on another thread. This holds for `val` members (lazy, read once) and
 * for the `var` members of `@Rdf` wrappers (read from the graph on every access). Consequently
 * `withIllTypedValues(SKIP) { existingWrapper.property }` does **not** affect a wrapper that already exists: wrap the
 * `materialize` call that creates the wrapper instead, e.g.
 * `val w = withIllTypedValues(SKIP) { graph.materialize<T>(node) }`. Wrappers reached through an object member are
 * created when that member is read, under the handling of the wrapper they are read from. Data-class factories read
 * every value eagerly, under the handling in effect during the factory call.
 */
object MaterializationPolicy {

  /**
   * Holder initialised by the JVM only when a skipped value is logged (IllTypedValueHandling.SKIP), so the policy and
   * the default THROW path never require SLF4J on the runtime class path.
   */
  private object SkipLog {
    val logger: org.slf4j.Logger = LoggerFactory.getLogger(MaterializationPolicy::class.java)
  }

  @Volatile
  private var defaultIllTypedValues: IllTypedValueHandling = IllTypedValueHandling.THROW

  private val scopedIllTypedValues = ThreadLocal<IllTypedValueHandling?>()

  /**
   * The handling in effect for the current thread: the innermost [withIllTypedValues] scope, otherwise the
   * process-wide default. Assigning changes the process-wide default (a running scope on this thread still wins).
   */
  @JvmStatic
  var illTypedValues: IllTypedValueHandling
    get() = scopedIllTypedValues.get() ?: defaultIllTypedValues
    set(value) {
      defaultIllTypedValues = value
    }

  /** Runs [block] with [handling] in effect for the current thread only, then restores the previous handling. */
  @JvmStatic
  fun <T> withIllTypedValues(handling: IllTypedValueHandling, block: () -> T): T {
    val previous = scopedIllTypedValues.get()
    scopedIllTypedValues.set(handling)
    try {
      return block()
    } finally {
      if (previous == null) scopedIllTypedValues.remove() else scopedIllTypedValues.set(previous)
    }
  }

  /**
   * A lazy value whose [initializer] runs with the ill-typed-value handling in effect now (when the lazy value is
   * created), whenever and on whichever thread it is first read. Generated wrappers use it for their properties.
   */
  @JvmStatic
  fun <T> lazyWithCurrentPolicy(initializer: () -> T): Lazy<T> {
    val handling = illTypedValues
    return lazy { withIllTypedValues(handling, initializer) }
  }

  /**
   * Called by generated code for a [literal] of [property] that could not be decoded into [expected].
   *
   * @return `null` (the value is skipped) under [IllTypedValueHandling.SKIP]
   * @throws MaterializationException under [IllTypedValueHandling.THROW]
   */
  @JvmStatic
  fun illTyped(literal: Literal, property: String, expected: String): Nothing? =
    reject("Ill-typed value \"${literal.lexical}\"^^<${literal.datatype.value}> for $property (expected $expected)")

  /**
   * Called by generated code for a [value] of [property] whose term kind is not [expected] (e.g. a literal where an
   * IRI or blank node is expected).
   *
   * @return `null` (the value is skipped) under [IllTypedValueHandling.SKIP]
   * @throws MaterializationException under [IllTypedValueHandling.THROW]
   */
  @JvmStatic
  fun unexpectedTerm(value: RdfTerm, property: String, expected: String): Nothing? =
    reject("Unexpected value $value for $property (expected $expected)")

  private fun reject(message: String): Nothing? {
    when (illTypedValues) {
      IllTypedValueHandling.THROW -> throw MaterializationException(message)
      IllTypedValueHandling.SKIP -> SkipLog.logger.warn("{}; value skipped", message)
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
