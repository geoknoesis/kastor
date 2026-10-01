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

  /**
   * The factory of each domain interface class, stored with the class itself ([ClassValue]): the registry holds no
   * strong reference to a registered class, so it never keeps the class loader of a discarded (hot-reloaded or
   * unloaded) module alive.
   */
  private val factories = object : ClassValue<java.util.concurrent.atomic.AtomicReference<((RdfHandle) -> Any)?>>() {
    override fun computeValue(type: Class<*>) = java.util.concurrent.atomic.AtomicReference<((RdfHandle) -> Any)?>(null)
  }

  /** The classes that currently have a factory, weakly referenced (for [registeredTypes]). */
  private val registered: MutableSet<Class<*>> =
    java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap<Class<*>, Boolean>()))

  private fun factoryOf(type: Class<*>): ((RdfHandle) -> Any)? = factories.get(type).get()

  const val ERROR_NO_FACTORY = "No wrapper factory registered for"
  const val ERROR_NOT_RDF_BACKED = "Object is not RDF-backed:"

  /**
   * Registers the factory used to materialize [type]. Called by generated code from the static initialiser of a
   * wrapper or data-class factory.
   *
   * ## The rule
   * A type has **one** factory, and registrations are keyed by the interface **class** (not its name):
   * 1. **Each class has its own registration.** An interface defined by two class loaders (two plugins that each
   *    bundle the generated code) is two types with independent factories; they never conflict.
   * 2. **Registering the same factory class again is idempotent.** The same instance is a no-op; another instance of
   *    the same factory class (a capturing lambda evaluated again, e.g. in a test's `@BeforeEach`) takes its place,
   *    since it is the same code.
   * 3. **A different factory class is a conflict** and throws [IllegalStateException]: it would silently change how
   *    the whole application materializes the type (for example two generated modules claiming one interface). Use
   *    the `replace = true` overload (or [unregister] first) to replace a factory deliberately (tests, plugins).
   * 4. **Class reloading.** When [type] lives in a shared parent class loader and the registered factory is the
   *    *same-named* factory class defined by a *different* class loader than [factory]'s (a hot-reload framework
   *    re-defined the wrapper), the new factory replaces the old one, so the old class loader is no longer reachable
   *    from the registry. This cannot be told apart from two live plugins that bundle the same wrapper for a shared
   *    interface (the last one registered wins), so the replacement is logged as a **warning** naming both class
   *    loaders. Factory classes are compared by binary name, ignoring the JVM-assigned suffix of lambda classes. A
   *    *differently named* factory class from another class loader is a conflict (rule 3).
   *
   * ## Class loaders
   * The registry references registered classes weakly: when a module's class loader is discarded together with its
   * interfaces, wrappers and factories, nothing in the registry keeps it alive and no `unregister` call is needed.
   * A factory registered for an interface of a *longer-lived* class loader is referenced from that interface, and
   * keeps its own class loader alive until it is replaced (rule 4) or [unregister]ed.
   *
   * @throws IllegalStateException when a factory of a different class is already registered for [type] (rule 3)
   */
  @JvmStatic
  fun <T : Any> register(type: Class<T>, factory: (RdfHandle) -> T): Unit = register(type, replace = false, factory = factory)

  /**
   * Registers the factory for [type]; with [replace] a previously registered factory is replaced whatever its class,
   * otherwise behaves like the two-argument [register].
   */
  @JvmStatic
  fun <T : Any> register(type: Class<T>, replace: Boolean, factory: (RdfHandle) -> T) {
    val slot = factories.get(type)
    while (true) {
      val previous = slot.get()
      if (previous === factory) return
      if (previous == null || replace) {
        if (!slot.compareAndSet(previous, factory)) continue
        registered.add(type)
        return
      }
      val previousClass = previous.javaClass
      val factoryClass = factory.javaClass
      val reloaded = previousClass !== factoryClass && previousClass.classLoader !== factoryClass.classLoader &&
        factoryOrigin(previousClass) == factoryOrigin(factoryClass)
      check(previousClass === factoryClass || reloaded) {
        "A different factory is already registered for ${type.name}: ${previousClass.name} " +
          "(class loader ${previousClass.classLoader}); cannot register ${factoryClass.name} " +
          "(class loader ${factoryClass.classLoader}). A type has one factory: call " +
          "OntoMapper.register(type, replace = true, factory) to replace it deliberately, or OntoMapper.unregister(type) first"
      }
      if (!slot.compareAndSet(previous, factory)) continue
      if (reloaded) {
        ReplacementLog.logger.warn(
          "Replacing the factory {} for {} registered from class loader {} with the same-named factory from class " +
            "loader {} (a reloaded module, or two live modules that bundle the same wrapper: the last one wins)",
          factoryOrigin(previousClass), type.name, previousClass.classLoader, factoryClass.classLoader,
        )
      }
      return
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
   * Holder initialised by the JVM only when the class-reloading path (rule 4 of [register]) logs, so loading OntoMapper never requires SLF4J
   * on the runtime class path (a `lazy { }` field would still link `org.slf4j.Logger` in the static initialiser).
   */
  private object ReplacementLog {
    val logger: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(OntoMapper::class.java)
  }

  /** Removes the factory for [type]; returns true when one was registered. */
  @JvmStatic
  fun unregister(type: Class<*>): Boolean {
    val removed = factories.get(type).getAndSet(null) != null
    registered.remove(type)
    return removed
  }

  /** Whether a factory is currently registered for [type]. */
  @JvmStatic
  fun isRegistered(type: Class<*>): Boolean = factoryOf(type) != null

  /** Snapshot of the currently registered types (classes whose class loader was discarded are not listed). */
  @JvmStatic
  fun registeredTypes(): Set<Class<*>> = synchronized(registered) { registered.toSet() }.filterTo(LinkedHashSet()) { isRegistered(it) }

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
    factoryOf(type) ?: run {
      loadWrapperClass(type)
      factoryOf(type)
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
    // A nested interface `Outer$Inner` has the top-level wrapper `Outer_InnerWrapper`.
    if (!isRegistered(type) && '$' in type.name) {
      tryLoad("${type.name.replace('$', '_')}$WRAPPER_SUFFIX", type.classLoader)
    }
    // If still unregistered, try the data-class factory (eager-projection pattern).
    if (!isRegistered(type)) {
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

