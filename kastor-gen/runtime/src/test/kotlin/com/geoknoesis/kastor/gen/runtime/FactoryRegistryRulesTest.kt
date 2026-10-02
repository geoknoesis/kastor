package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import org.junit.jupiter.api.Test
import java.lang.ref.Reference
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A type whose interface and factory are both re-defined by child class loaders in the tests. */
interface LoaderScopedType {
  val tag: String
}

class LoaderScopedImpl(override val tag: String) : LoaderScopedType

class LoaderScopedFactory : (RdfHandle) -> LoaderScopedType {
  override fun invoke(handle: RdfHandle): LoaderScopedType = LoaderScopedImpl("scoped")
}

class OtherLoaderScopedFactory : (RdfHandle) -> LoaderScopedType {
  override fun invoke(handle: RdfHandle): LoaderScopedType = LoaderScopedImpl("other")
}

/** Defines the classes named in [names] itself (child first) and delegates everything else to [parent]. */
private class IsolatingLoader(parent: ClassLoader, private val names: Set<String>) : ClassLoader(parent) {
  override fun loadClass(className: String, resolve: Boolean): Class<*> {
    if (className !in names) return super.loadClass(className, resolve)
    synchronized(getClassLoadingLock(className)) {
      findLoadedClass(className)?.let { return it }
      val bytes = parent.getResourceAsStream(className.replace('.', '/') + ".class")!!.use { it.readBytes() }
      return defineClass(className, bytes, 0, bytes.size)
    }
  }
}

/**
 * The registration rule of [OntoMapper]: one factory per interface class, keyed by the class itself and held without
 * pinning its class loader; registering the same factory class again is idempotent; another factory class needs
 * `replace = true`.
 */
class FactoryRegistryRulesTest {

  private val scopedNames = setOf(
    LoaderScopedType::class.java.name,
    LoaderScopedImpl::class.java.name,
    LoaderScopedFactory::class.java.name,
  )

  private fun capturing(tag: String): (RdfHandle) -> LoaderScopedType = { _ -> LoaderScopedImpl(tag) }

  private fun materialize(type: Class<*>): Any = OntoMapper.materialize(RdfRef(Iri("urn:x"), MemoryGraph()), type)

  @Suppress("UNCHECKED_CAST")
  private fun registerReflectively(type: Class<*>, factory: Any) {
    OntoMapper.register(type as Class<Any>, factory as (RdfHandle) -> Any)
  }

  @Test
  fun `registering the same factory class again is idempotent and uses the newest instance`() {
    OntoMapper.unregister(LoaderScopedType::class.java)
    try {
      // A capturing lambda is a new object on every evaluation (e.g. in @BeforeEach) but the same factory class.
      OntoMapper.register(LoaderScopedType::class.java, capturing("first"))
      OntoMapper.register(LoaderScopedType::class.java, capturing("second"))
      assertEquals("second", (materialize(LoaderScopedType::class.java) as LoaderScopedType).tag)

      OntoMapper.unregister(LoaderScopedType::class.java)
      OntoMapper.register(LoaderScopedType::class.java, LoaderScopedFactory())
      OntoMapper.register(LoaderScopedType::class.java, LoaderScopedFactory())
      assertEquals("scoped", (materialize(LoaderScopedType::class.java) as LoaderScopedType).tag)
    } finally {
      OntoMapper.unregister(LoaderScopedType::class.java)
    }
  }

  @Test
  fun `a different factory class needs replace = true and the error says so`() {
    OntoMapper.unregister(LoaderScopedType::class.java)
    try {
      OntoMapper.register(LoaderScopedType::class.java, LoaderScopedFactory())
      val error = assertFailsWith<IllegalStateException> {
        OntoMapper.register(LoaderScopedType::class.java, OtherLoaderScopedFactory())
      }
      val message = error.message!!
      assertTrue(LoaderScopedType::class.java.name in message, message)
      assertTrue(LoaderScopedFactory::class.java.name in message, message)
      assertTrue(OtherLoaderScopedFactory::class.java.name in message, message)
      assertTrue("replace = true" in message, message)
      assertEquals("scoped", (materialize(LoaderScopedType::class.java) as LoaderScopedType).tag, "the first factory stays")

      OntoMapper.register(LoaderScopedType::class.java, replace = true, factory = OtherLoaderScopedFactory())
      assertEquals("other", (materialize(LoaderScopedType::class.java) as LoaderScopedType).tag)
    } finally {
      OntoMapper.unregister(LoaderScopedType::class.java)
    }
  }

  @Test
  fun `class loaders that each define the interface have independent registrations`() {
    val parent = javaClass.classLoader
    val a = IsolatingLoader(parent, scopedNames)
    val b = IsolatingLoader(parent, scopedNames)
    val typeA = a.loadClass(LoaderScopedType::class.java.name)
    val typeB = b.loadClass(LoaderScopedType::class.java.name)
    assertNotSame(typeA, typeB)
    try {
      registerReflectively(typeA, a.loadClass(LoaderScopedFactory::class.java.name).getDeclaredConstructor().newInstance())
      registerReflectively(typeB, b.loadClass(LoaderScopedFactory::class.java.name).getDeclaredConstructor().newInstance())
      assertTrue(OntoMapper.isRegistered(typeA) && OntoMapper.isRegistered(typeB))
      assertFalse(OntoMapper.isRegistered(LoaderScopedType::class.java), "the parent's interface is another type")
      assertTrue(OntoMapper.registeredTypes().containsAll(listOf(typeA, typeB)))
      assertEquals(a, materialize(typeA).javaClass.classLoader)
      assertEquals(b, materialize(typeB).javaClass.classLoader)
    } finally {
      OntoMapper.unregister(typeA)
      OntoMapper.unregister(typeB)
    }
  }

  /**
   * Whether an object that satisfies [pinned] is strongly reachable from [root]: through instance fields and array
   * elements, never through the referent of a `java.lang.ref.Reference`, and never through a `Class` (the classes of
   * the walked objects are not what the root "holds"). This is the question "would the collector keep it alive
   * because of [root]?", answered by looking instead of by waiting for a collection that may or may not run.
   */
  private fun stronglyReaches(root: Any, pinned: (Any) -> Boolean): Boolean = strongPath(root, pinned) != null

  /** The chain of fields that leads from [root] to an object that satisfies [pinned], or null when there is none. */
  private fun strongPath(root: Any, pinned: (Any) -> Boolean): String? {
    val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    val pending = ArrayDeque<Pair<Any, String>>()
    pending += root to root.javaClass.simpleName
    while (pending.isNotEmpty()) {
      val (current, path) = pending.removeLast()
      if (!seen.add(current)) continue
      if (pinned(current)) return path
      if (current is Class<*> || current is ClassLoader || current is Thread) continue
      if (current.javaClass.isArray) {
        if (!current.javaClass.componentType.isPrimitive) (current as Array<*>).forEach { element -> element?.let { pending += it to "$path[]" } }
        continue
      }
      var type: Class<*>? = current.javaClass
      while (type != null) {
        for (field in type.declaredFields) {
          if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
          // The referent of a reference is not held strongly; `discovered` and `next` belong to the collector.
          if (type == Reference::class.java && field.name != "queue") continue
          try {
            field.isAccessible = true
          } catch (e: RuntimeException) {
            throw AssertionError(
              "cannot read ${type.name}.${field.name}: add --add-opens for ${type.packageName} to the test JVM " +
                "(kastor-gen/runtime/build.gradle.kts)",
              e,
            )
          }
          field.get(current)?.let { pending += it to "$path.${field.name}" }
        }
        type = type.superclass
      }
    }
    return null
  }

  @Test
  fun `the registry does not keep the class loader of a registered type alive`() {
    val loader = IsolatingLoader(javaClass.classLoader, scopedNames)
    val type = loader.loadClass(LoaderScopedType::class.java.name)
    // Anything of the discarded module pins its class loader: the loader, its classes, instances of its classes.
    val ofLoader = { held: Any ->
      held === loader || (held is Class<*> && held.classLoader === loader) || held.javaClass.classLoader === loader
    }
    try {
      registerReflectively(type, loader.loadClass(LoaderScopedFactory::class.java.name).getDeclaredConstructor().newInstance())
      assertEquals(loader, materialize(type).javaClass.classLoader)
      assertTrue(OntoMapper.isRegistered(type))
      // No unregister: a discarded (hot-reloaded) module does not clean up after itself.
      assertNull(
        strongPath(OntoMapper, ofLoader),
        "the registry must not pin the class loader of a registered interface: it may hold its classes weakly only",
      )
      // The same walk does find a registry that holds the class (or its factory) strongly.
      assertTrue(stronglyReaches(hashMapOf<Any, Any>("type" to type), ofLoader))
      assertTrue(stronglyReaches(arrayOf<Any?>(null, listOf(loader.loadClass(LoaderScopedImpl::class.java.name))), ofLoader))
      // A weakly held class is not "held".
      assertFalse(stronglyReaches(java.util.WeakHashMap<Any, Any>().apply { put(type, true) }, ofLoader))
    } finally {
      OntoMapper.unregister(type)
    }
  }
}
