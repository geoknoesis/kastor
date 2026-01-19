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
   * Materializes an RDF node as a domain object.
   * 
   * @param ref The RDF reference (node + graph)
   * @param type The target domain interface class
   * @return Instance of the domain interface backed by RDF
   * @throws IllegalStateException if no factory is registered for the type
   */
  @JvmStatic
  fun <T: Any> materialize(ref: RdfRef, type: Class<T>): T {
    return materializeInternal(ref, type, validate = false)
  }

  /**
   * Materializes an RDF node as a domain object and validates it.
   */
  @JvmStatic
  fun <T: Any> materializeValidated(ref: RdfRef, type: Class<T>): T {
    return materializeInternal(ref, type, validate = true)
  }

  private fun <T: Any> materializeInternal(ref: RdfRef, type: Class<T>, validate: Boolean): T {
    val factory = registry[type] ?: run {
      loadWrapperClass(type)
      registry[type]
    } ?: error("$ERROR_NO_FACTORY ${type.name}")
    val handle = DefaultRdfHandle(ref.node, ref.graph, known = emptySet())
    @Suppress("UNCHECKED_CAST")
    val instance = factory(handle) as T
    if (validate) handle.validate().orThrow()
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

  private fun loadWrapperClass(type: Class<*>) {
    val wrapperName = "${type.name}Wrapper"
    try {
      Class.forName(wrapperName, true, type.classLoader)
    } catch (_: ClassNotFoundException) {
      // Wrapper not present; caller will get a clear error if still unregistered.
    }
  }
}

/** Kotlin convenience for materialization. */
@JvmName("asTypeExtension")
inline fun <reified T: Any> RdfRef.asType(): T =
  OntoMapper.materialize(this, T::class.java)

@JvmName("asValidatedTypeExtension")
inline fun <reified T: Any> RdfRef.asValidatedType(): T =
  OntoMapper.materializeValidated(this, T::class.java)

/** Ergonomic access to the RDF side-channel. */
@JvmName("asRdfExtension")
inline fun <reified T: Any> T.asRdf(): RdfHandle =
  (this as? RdfBacked)?.rdf ?: error("${OntoMapper.ERROR_NOT_RDF_BACKED} ${this::class}")












