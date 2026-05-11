package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.getCbdClosure
import kotlin.reflect.KClass

/**
 * Reference to an RDF node within a graph.
 *
 * Prefer **`graph.materialize<…>(node)`** or **`node.materializeIn(graph)`** at call sites;
 * use `RdfRef` when you want to pass the pair around or call [asType] explicitly.
 */
data class RdfRef(val node: RdfTerm, val graph: RdfGraph)

/**
 * Focus [node] in this graph — the usual starting point before [materialize] / [RdfRef.asType].
 *
 * Example: `repo.defaultGraph.ref(uri).asType<Person>()` or `graph.ref(uri).asType()`.
 */
fun RdfGraph.ref(node: RdfTerm): RdfRef = RdfRef(node, this)

/**
 * Materialize [node] in this graph as [T] using [OntoMapper] (reified; idiomatic at call sites).
 *
 * Example: `repo.defaultGraph.materialize<Person>(subjectIri)`
 */
inline fun <reified T : Any> RdfGraph.materialize(node: RdfTerm): T =
  ref(node).asType()

/**
 * Non-reified materialization when the target class is only known at runtime.
 */
fun <T : Any> RdfGraph.materialize(node: RdfTerm, type: KClass<T>): T =
  OntoMapper.materialize(RdfRef(node, this), type.java)

/**
 * Materialize this term in [graph] (subject-first; reads naturally after an IRI expression).
 *
 * Example: `subjectIri.materializeIn<Person>(repo.defaultGraph)`
 */
inline fun <reified T : Any> RdfTerm.materializeIn(graph: RdfGraph): T =
  graph.materialize<T>(this)

fun <T : Any> RdfTerm.materializeIn(graph: RdfGraph, type: KClass<T>): T =
  graph.materialize(this, type)

/**
 * Materialize each term in this collection against the same [graph], preserving order.
 */
inline fun <reified T : Any> Iterable<RdfTerm>.materializeIn(graph: RdfGraph): List<T> =
  map { graph.materialize<T>(it) }

fun <T : Any> Iterable<RdfTerm>.materializeIn(graph: RdfGraph, type: KClass<T>): List<T> =
  map { graph.materialize(it, type) }

/**
 * Materialize [node] from [graph], defaulting to the repository's [RdfRepository.defaultGraph].
 *
 * Example: `repo.materialize<Person>(uri)`
 */
inline fun <reified T : Any> RdfRepository.materialize(node: RdfTerm, graph: RdfGraph = defaultGraph): T =
  graph.materialize<T>(node)

fun <T : Any> RdfRepository.materialize(node: RdfTerm, type: KClass<T>, graph: RdfGraph = defaultGraph): T =
  graph.materialize(node, type)

/**
 * Like [materialize] but runs SHACL validation on the focus node before returning.
 */
inline fun <reified T : Any> RdfGraph.materializeValidated(node: RdfTerm, validation: ValidationContext): T =
  ref(node).asValidatedType(validation)

inline fun <reified T : Any> RdfRepository.materializeValidated(
  node: RdfTerm,
  validation: ValidationContext,
  graph: RdfGraph = defaultGraph,
): T = graph.materializeValidated(node, validation)

/** Central materializer populated by generated registration code.
 *
 * [materialize] and [materializeValidated] resolve a wrapper [factory] from [registry], then invoke
 * it with a provisional [DefaultRdfHandle] (no mapped-predicate filter for extras, and optional
 * [ValidationContext]). Generated wrappers typically replace that handle in a lazy `rdf` delegate
 * so [RdfHandle.extras] excludes mapped predicates and validation is wired as generated.
 */
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
   * Explicitly loads wrapper classes for the given domain types (e.g. to avoid first-hit
   * class-loading races in server startup).
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

/**
 * Writes the CBD (Concise Bounded Description) closure of this RDF-backed instance to the target graph.
 * 
 * CBD includes:
 * 1. All triples where this resource is the subject (direct properties)
 * 2. Recursively, for any blank node object, all triples where that blank node is the subject
 * 
 * This method extracts the complete resource description from the backing graph and writes it
 * to the target graph, following blank nodes recursively but not following IRIs.
 * 
 * **Example:**
 * ```kotlin
 * val person: Person = // ... instance from graph
 * val newGraph = Rdf.graph()
 * 
 * // Write CBD closure to new graph (uses rdf.node as subject)
 * person.writeToGraph(newGraph)
 * 
 * // Write to different IRI
 * person.writeToGraph(newGraph, subject = Iri("http://example.org/copy"))
 * ```
 * 
 * @param targetGraph The mutable graph to write triples to
 * @param subject Optional subject IRI. If not provided, uses rdf.node as Iri
 * @throws IllegalArgumentException if subject is required but not available
 */
fun <T : RdfBacked> T.writeToGraph(
    targetGraph: com.geoknoesis.kastor.rdf.MutableRdfGraph,
    subject: com.geoknoesis.kastor.rdf.Iri? = null
) {
    val originalSubject = (rdf.node as? com.geoknoesis.kastor.rdf.Iri)
        ?: (rdf.node as? com.geoknoesis.kastor.rdf.RdfResource)
        ?: throw IllegalArgumentException("Subject resource required for ${this::class.simpleName}")
    
    // Extract CBD closure from backing graph using original subject
    val cbdTriples = rdf.graph.getCbdClosure(originalSubject)
    
    // If a different subject is provided, remap triples
    val triplesToWrite = if (subject != null && subject != originalSubject) {
        cbdTriples.map { triple ->
            if (triple.subject == originalSubject) {
                com.geoknoesis.kastor.rdf.RdfTriple(subject, triple.predicate, triple.obj)
            } else {
                triple
            }
        }
    } else {
        cbdTriples
    }
    
    // Write to target graph
    targetGraph.addTriples(triplesToWrite)
}

