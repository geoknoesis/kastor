package com.example.ontomapper.runtime

import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm

/**
 * Validation port interface for SHACL validation adapters.
 * Implementations bridge Kastor RDF graphs to backend validation engines (Jena/RDF4J).
 */
interface ValidationPort {
  /**
   * Validates the focus node against configured SHACL shapes.
   * 
   * @param data The RDF graph containing the data to validate
   * @param focus The focus node to validate
   * @throws RuntimeException if validation fails
   */
  @Throws(RuntimeException::class)
  fun validateOrThrow(data: RdfGraph, focus: RdfTerm)
}

/**
 * Global registry for validation ports.
 * Thread-safe singleton for registering and retrieving validation adapters.
 */
object ValidationRegistry {
  
  @Volatile 
  private var port: ValidationPort? = null
  
  private const val ERROR_NO_PORT = "No ValidationPort registered"
  
  /**
   * Registers a validation port.
   * 
   * @param p The validation port to register (null to clear)
   */
  @JvmStatic
  fun register(p: ValidationPort?) { 
    port = p 
  }
  
  /**
   * Retrieves the currently registered validation port.
   * 
   * @return The registered validation port
   * @throws IllegalStateException if no port is registered
   */
  @JvmStatic
  fun current(): ValidationPort = port ?: error(ERROR_NO_PORT)
}
