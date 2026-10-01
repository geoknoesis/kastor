package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
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

  @Test
  fun `the registry does not keep the class loader of a registered type alive`() {
    fun registerInFreshLoader(): WeakReference<ClassLoader> {
      val loader = IsolatingLoader(javaClass.classLoader, scopedNames)
      val type = loader.loadClass(LoaderScopedType::class.java.name)
      registerReflectively(type, loader.loadClass(LoaderScopedFactory::class.java.name).getDeclaredConstructor().newInstance())
      assertEquals(loader, materialize(type).javaClass.classLoader)
      return WeakReference(loader)
    }
    // No unregister: a discarded (hot-reloaded) module does not clean up after itself.
    val loader = registerInFreshLoader()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (loader.get() != null && System.nanoTime() < deadline) {
      System.gc()
      Thread.sleep(20)
    }
    assertNull(loader.get(), "the registry must not pin the class loader of a registered interface")
    assertTrue(OntoMapper.registeredTypes().none { it.name == LoaderScopedType::class.java.name })
  }
}
