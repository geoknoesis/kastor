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
 * [materialize] and [materializeValidated] resolve a wrapper `factory` registered via [register], then
 * invoke it with a provisional [DefaultRdfHandle] (no mapped-predicate filter for extras, and optional
 * [ValidationContext]). Generated wrappers typically replace that handle in a lazy `rdf` delegate
 * so [RdfHandle.extras] excludes mapped predicates and validation is wired as generated.
 *
 * ## Nested materialization and cycles
 * Each outermost [materialize] call opens a per-thread materialization scope that lasts until it returns.
 * Within the scope a (type, node, graph) that was already materialized is reused, and re-entering a
 * (type, node, graph) that is *still being built* fails fast with a [MaterializationException] naming the
 * cycle instead of recursing until `StackOverflowError`. That only happens for eagerly-loaded immutable
 * snapshots (data classes generated with `NestedMode.DATA_CLASS`) over cyclic data such as
 * `a foaf:knows b . b foaf:knows a`: an immutable value cannot contain itself. Live wrappers load nested
 * objects lazily and are unaffected; use `NestedMode.INTERFACE` or `NestedMode.IRI_ONLY` for cyclic data.
 */
object OntoMapper {

  /** Registry mapping domain interface classes to their wrapper factories. */
  private val registry: MutableMap<Class<*>, (RdfHandle) -> Any> = java.util.concurrent.ConcurrentHashMap()

  const val ERROR_NO_FACTORY = "No wrapper factory registered for"
  const val ERROR_NOT_RDF_BACKED = "Object is not RDF-backed:"

  /**
   * Registers the factory used to materialize [type]. Called by generated code from the static initialiser of a
   * wrapper or data-class factory.
   *
   * Registering the same factory instance again is a no-op. Registering a *different* factory for a type that
   * already has one fails, because it would silently change how the whole application materializes that type
   * (for example two generated modules claiming one interface); use the `replace = true` overload to replace a
   * factory deliberately (tests, plugins).
   *
   * **Class reloading.** When the previously registered factory is the *same* factory class re-defined by a
   * *different* class loader than [factory]'s (a hot-reload or plugin framework re-defined the wrapper in a new
   * child class loader while [type] stays in a shared parent loader), the new factory replaces the old one, logged
   * at debug level. The old factory is dropped, so its class loader is no longer reachable from the registry.
   * Factory classes are compared by binary name, ignoring the JVM-assigned suffix of lambda classes.
   *
   * A factory of a *different* class from another class loader (two independently loaded modules generating a
   * wrapper for one shared interface) is a genuine conflict and fails like any other conflicting registration.
   *
   * @throws IllegalStateException when a different factory is already registered for [type], unless it is the same
   *   factory class reloaded by another class loader
   */
  @JvmStatic
  fun <T : Any> register(type: Class<T>, factory: (RdfHandle) -> T): Unit = register(type, replace = false, factory = factory)

  /**
   * Registers the factory for [type]; with [replace] a previously registered factory is replaced, otherwise
   * behaves like the two-argument [register].
   */
  @JvmStatic
  fun <T : Any> register(type: Class<T>, replace: Boolean, factory: (RdfHandle) -> T) {
    if (replace) {
      registry[type] = factory
      return
    }
    while (true) {
      val previous = registry.putIfAbsent(type, factory) ?: return
      if (previous === factory) return
      val reloaded = previous.javaClass.classLoader !== factory.javaClass.classLoader &&
        factoryOrigin(previous.javaClass) == factoryOrigin(factory.javaClass)
      check(reloaded) {
        "A different factory (${factory.javaClass.name}) is already registered for ${type.name} " +
          "(${previous.javaClass.name}); call OntoMapper.register(type, replace = true, factory) to replace it deliberately"
      }
      if (registry.replace(type, previous, factory)) {
        ReplacementLog.logger.debug(
          "Replacing the factory for {} registered from class loader {} with one from class loader {}",
          type.name, previous.javaClass.classLoader, factory.javaClass.classLoader,
        )
        return
      }
    }
  }

  /**
   * Stable identity of a factory class across class loaders: its binary name without the JVM-assigned lambda
   * suffix (`Foo$$Lambda/0x…`, `Foo$$Lambda$12/0x…`), so a reloaded lambda compares equal to the original.
   */
  private fun factoryOrigin(factoryClass: Class<*>): String {
    val name = factoryClass.name
    val lambda = name.indexOf("$\$Lambda")
    return if (lambda >= 0) name.substring(0, lambda) else name.substringBefore('/')
  }

  /**
   * Holder initialised by the JVM only when the class-reloading path logs, so loading OntoMapper never requires SLF4J
   * on the runtime class path (a `lazy { }` field would still link `org.slf4j.Logger` in the static initialiser).
   */
  private object ReplacementLog {
    val logger: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(OntoMapper::class.java)
  }

  /** Removes the factory for [type]; returns true when one was registered. */
  @JvmStatic
  fun unregister(type: Class<*>): Boolean = registry.remove(type) != null

  /** Whether a factory is currently registered for [type]. */
  @JvmStatic
  fun isRegistered(type: Class<*>): Boolean = registry.containsKey(type)

  /** Snapshot of the currently registered types. */
  @JvmStatic
  fun registeredTypes(): Set<Class<*>> = registry.keys.toSet()

  private class ScopeKey(val type: Class<*>, val node: RdfTerm, val graph: RdfGraph) {
    override fun equals(other: Any?): Boolean =
      other is ScopeKey && other.type == type && other.node == node && other.graph === graph
    override fun hashCode(): Int = (type.hashCode() * 31 + node.hashCode()) * 31 + System.identityHashCode(graph)
    override fun toString(): String = "${type.simpleName} $node"
  }

  private class Scope {
    val inProgress = LinkedHashSet<ScopeKey>()
    val done = HashMap<ScopeKey, Any>()
  }

  private val currentScope = ThreadLocal<Scope?>()

  private fun <T : Any> withinScope(ref: RdfRef, type: Class<T>, build: () -> T): T {
    val existing = currentScope.get()
    val scope = existing ?: Scope().also { currentScope.set(it) }
    try {
      val key = ScopeKey(type, ref.node, ref.graph)
      scope.done[key]?.let {
        @Suppress("UNCHECKED_CAST")
        return it as T
      }
      if (!scope.inProgress.add(key)) {
        val path = (scope.inProgress.dropWhile { it != key } + key).joinToString(" -> ")
        throw MaterializationException(
          "Cyclic reference while materializing immutable snapshot: $path. " +
            "Eager snapshots cannot represent cycles; use NestedMode.INTERFACE or NestedMode.IRI_ONLY."
        )
      }
      try {
        val instance = build()
        scope.done[key] = instance
        return instance
      } finally {
        scope.inProgress.remove(key)
      }
    } finally {
      if (existing == null) currentScope.remove()
    }
  }

  private fun factoryFor(type: Class<*>): (RdfHandle) -> Any =
    registry[type] ?: run {
      loadWrapperClass(type)
      registry[type]
    } ?: error("$ERROR_NO_FACTORY ${type.name}")

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
   * @throws MaterializationException if an eagerly-loaded snapshot graph is cyclic
   */
  @JvmStatic
  fun <T: Any> materialize(ref: RdfRef, type: Class<T>): T {
    val factory = factoryFor(type)
    return withinScope(ref, type) {
      val handle = DefaultRdfHandle(ref.node, ref.graph, known = emptySet(), validationContext = null)
      @Suppress("UNCHECKED_CAST")
      factory(handle) as T
    }
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
    val factory = factoryFor(type)
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

  private const val WRAPPER_SUFFIX  = "Wrapper"
  private const val FACTORY_SUFFIX  = "Factory"

  private fun loadWrapperClass(type: Class<*>) {
    // Try the live-wrapper class first (interface + delegate pattern).
    tryLoad("${type.name}$WRAPPER_SUFFIX", type.classLoader)
    // If still unregistered, try the data-class factory (eager-projection pattern).
    if (!registry.containsKey(type)) {
      tryLoad("${type.name}$FACTORY_SUFFIX", type.classLoader)
    }
  }

  private fun tryLoad(className: String, loader: ClassLoader?) {
    try {
      // loader may be null for bootstrap-loaded types (e.g. java.lang.Object);
      // Class.forName treats a null loader as the bootstrap class loader.
      Class.forName(className, true, loader)
    } catch (_: ClassNotFoundException) {
      // Not present; caller decides whether to error.
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

