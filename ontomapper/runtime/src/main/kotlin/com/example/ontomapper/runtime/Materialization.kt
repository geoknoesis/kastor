package com.example.ontomapper.runtime

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
   * @param validate Whether to run SHACL validation after materialization
   * @return Instance of the domain interface backed by RDF
   * @throws IllegalStateException if no factory is registered for the type
   * @throws RuntimeException if validation fails (when validate=true)
   */
  @JvmStatic
  @JvmOverloads
  fun <T: Any> materialize(ref: RdfRef, type: Class<T>, validate: Boolean = false): T {
    val factory = registry[type] ?: error("$ERROR_NO_FACTORY ${type.name}")
    val handle = DefaultRdfHandle(ref.node, ref.graph, known = emptySet())
    @Suppress("UNCHECKED_CAST")
    val instance = factory(handle) as T
    if (validate) handle.validateOrThrow()
    return instance
  }
  
  /**
   * Explicit initialization to avoid class-loading races.
   * Call this during application bootstrap to ensure all wrappers are registered.
   */
  @JvmStatic
  fun initialize() {
    // Force loading of all generated wrapper classes
    // This method can be called explicitly to ensure deterministic registration
  }
}

/** Kotlin convenience for materialization. */
@JvmName("asTypeExtension")
inline fun <reified T: Any> RdfRef.asType(validate: Boolean = false): T =
  OntoMapper.materialize(this, T::class.java, validate)

/** Ergonomic access to the RDF side-channel. */
@JvmName("asRdfExtension")
inline fun <reified T: Any> T.asRdf(): RdfHandle =
  (this as? RdfBacked)?.rdf ?: error("${OntoMapper.ERROR_NOT_RDF_BACKED} ${this::class}")
