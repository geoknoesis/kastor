package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm

/**
 * Reference to an RDF node within a graph.
 */
data class RdfRef(val node: RdfTerm, val graph: RdfGraph)

/** Central materializer populated by generated registration code. */
object OntoMapper {
  
  /** Registry mapping domain interface classes to their wrapper factories. */
  @JvmField
  val registry = mutableMapOf<Class<*>, (RdfHandle) -> Any>()
  
  const val ERROR_NO_FACTORY = "No wrapper factory registered for"
  const val ERROR_NOT_RDF_BACKED = "Object is not RDF-backed:"

  /**
   * Materializes an RDF node as a domain object without validation.
   * 
   * This is the fast path for materialization. No SHACL validation is performed.
   * Use [materializeValidated] when validation is required.
   * 
   * @param ref The RDF reference (node + graph)
   * @param type The target domain interface class
   * @return Instance of the domain interface backed by RDF
   * @throws IllegalStateException if no factory is registered for the type
   */
  @JvmStatic
  fun <T: Any> materialize(ref: RdfRef, type: Class<T>): T {
    val factory = registry[type] ?: run {
      loadWrapperClass(type)
      registry[type]
    } ?: error("$ERROR_NO_FACTORY ${type.name}")
    val handle = DefaultRdfHandle(ref.node, ref.graph, known = emptySet(), validationContext = null)
    @Suppress("UNCHECKED_CAST")
    return factory(handle) as T
  }

  /**
   * Materializes an RDF node as a domain object and validates it against SHACL shapes.
   * 
   * Validation is mandatory when using this method. The validation context must be provided,
   * and validation failures will throw [ValidationException].
   * 
   * @param ref The RDF reference (node + graph)
   * @param type The target domain interface class
   * @param validation The validation context (required, not nullable)
   * @return Instance of the domain interface backed by RDF
   * @throws IllegalStateException if no factory is registered for the type
   * @throws ValidationException if SHACL validation fails
   */
  @JvmStatic
  fun <T: Any> materializeValidated(ref: RdfRef, type: Class<T>, validation: ValidationContext): T {
    val factory = registry[type] ?: run {
      loadWrapperClass(type)
      registry[type]
    } ?: error("$ERROR_NO_FACTORY ${type.name}")
    val handle = DefaultRdfHandle(ref.node, ref.graph, known = emptySet(), validationContext = validation)
    @Suppress("UNCHECKED_CAST")
    val instance = factory(handle) as T
    handle.validate().orThrow()
    return instance
  }
  
  /**
   * Explicit initialization to avoid class-loading races.
   * Call this during application bootstrap to ensure all wrappers are registered.
   */
  @JvmStatic
  fun initialize() {
    // Prefer initialize(vararg types) for deterministic registration.
  }

  /**
   * Explicitly loads wrapper classes for the given domain types.
   */
  @JvmStatic
  fun initialize(vararg types: Class<*>) {
    types.forEach { loadWrapperClass(it) }
  }

  private const val WRAPPER_SUFFIX = "Wrapper"
  
  private fun loadWrapperClass(type: Class<*>) {
    val wrapperName = "${type.name}$WRAPPER_SUFFIX"
    try {
      Class.forName(wrapperName, true, type.classLoader)
    } catch (_: ClassNotFoundException) {
      // Wrapper not present; caller will get a clear error if still unregistered.
    }
  }
}

/** Kotlin convenience for materialization without validation. */
@JvmName("asTypeExtension")
inline fun <reified T: Any> RdfRef.asType(): T =
  OntoMapper.materialize(this, T::class.java)

/** Kotlin convenience for materialization with mandatory validation. */
@JvmName("asValidatedTypeExtension")
inline fun <reified T: Any> RdfRef.asValidatedType(validation: ValidationContext): T =
  OntoMapper.materializeValidated(this, T::class.java, validation)

/** Ergonomic access to the RDF side-channel. */
@JvmName("asRdfExtension")
inline fun <reified T: Any> T.asRdf(): RdfHandle =
  (this as? RdfBacked)?.rdf ?: error("${OntoMapper.ERROR_NOT_RDF_BACKED} ${this::class}")












