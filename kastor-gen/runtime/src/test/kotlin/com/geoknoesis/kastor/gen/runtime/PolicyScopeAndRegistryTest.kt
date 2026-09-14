package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.gen.runtime.delegates.rdfObject
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Test type registered by factories loaded through different class loaders. */
interface ReloadableType {
  val factoryLoader: ClassLoader?
}

class ReloadableImpl(override val factoryLoader: ClassLoader?) : ReloadableType

/** Factory whose class is re-defined by a child-first class loader in the test (class reloading). */
class ReloadableTestFactory : (RdfHandle) -> ReloadableType {
  override fun invoke(handle: RdfHandle): ReloadableType = ReloadableImpl(javaClass.classLoader)
}

/** Loads [name] itself (child first) and delegates everything else to [parent]. */
private class ChildFirstLoader(parent: ClassLoader, private val name: String) : ClassLoader(parent) {
  override fun loadClass(className: String, resolve: Boolean): Class<*> {
    if (className != name) return super.loadClass(className, resolve)
    synchronized(getClassLoadingLock(className)) {
      findLoadedClass(className)?.let { return it }
      val bytes = parent.getResourceAsStream(className.replace('.', '/') + ".class")!!.use { it.readBytes() }
      return defineClass(className, bytes, 0, bytes.size)
    }
  }
}

class PolicyScopeAndRegistryTest {

  private val bad = Literal("abc", Iri("http://www.w3.org/2001/XMLSchema#integer"))

  @Test
  fun `a scoped ill-typed policy applies to the current thread only and is restored afterwards`() {
    assertEquals(IllTypedValueHandling.THROW, MaterializationPolicy.illTypedValues)
    val inside = CountDownLatch(1)
    val release = CountDownLatch(1)
    val otherThread = AtomicReference<IllTypedValueHandling>()
    val result = MaterializationPolicy.withIllTypedValues(IllTypedValueHandling.SKIP) {
      val worker = thread {
        inside.await()
        otherThread.set(MaterializationPolicy.illTypedValues)
        release.countDown()
      }
      inside.countDown()
      release.await()
      worker.join()
      val nested = MaterializationPolicy.withIllTypedValues(IllTypedValueHandling.THROW) {
        assertFailsWith<MaterializationException> { MaterializationPolicy.illTyped(bad, "p", "Int") }
        "nested"
      }
      assertEquals(IllTypedValueHandling.SKIP, MaterializationPolicy.illTypedValues, "nested scope restores the outer one")
      nested + ":" + MaterializationPolicy.illTyped(bad, "p", "Int")
    }
    assertEquals("nested:null", result)
    assertEquals(IllTypedValueHandling.THROW, otherThread.get(), "other threads keep the global default")
    assertEquals(IllTypedValueHandling.THROW, MaterializationPolicy.illTypedValues)
  }

  @Test
  fun `values of an unexpected term kind follow the policy`() {
    val g = MemoryGraph()
    val s = Iri("urn:s")
    val p = Iri("urn:p")
    val blank = BlankNode("b1")
    g.addTriple(RdfTriple(s, p, Iri("urn:o")))
    g.addTriple(RdfTriple(s, p, blank))
    g.addTriple(RdfTriple(s, p, Literal("lit")))

    val e = assertFailsWith<MaterializationException> { KastorGraphOps.getIriValues(g, s, p, "member <urn:p>") }
    assertTrue(e.message!!.contains("member <urn:p>"), e.message)
    assertFailsWith<MaterializationException> { KastorGraphOps.getResourceValues(g, s, p, "member") }
    assertFailsWith<MaterializationException> { KastorGraphOps.getLiteralValues(g, s, p, "member") }
    assertFailsWith<MaterializationException> { KastorGraphOps.getObjectValues(g, s, p, "member") { it } }

    MaterializationPolicy.withIllTypedValues(IllTypedValueHandling.SKIP) {
      assertEquals(listOf<RdfTerm>(Iri("urn:o")), KastorGraphOps.getIriValues(g, s, p, "member"))
      assertEquals(setOf<RdfTerm>(Iri("urn:o"), blank), KastorGraphOps.getResourceValues(g, s, p, "member").toSet())
      assertEquals(listOf("lit"), KastorGraphOps.getLiteralValues(g, s, p, "member").map { it.lexical })
      assertEquals(2, KastorGraphOps.getObjectValues(g, s, p, "member") { it }.size)
    }
  }

  @Test
  fun `a missing required object throws a materialization exception`() {
    val g = MemoryGraph()
    val backed = object : RdfBacked {
      override val rdf: RdfHandle = DefaultRdfHandle(Iri("urn:none"), g, known = emptySet(), validationContext = null)
      val friend: ReloadableType by rdfObject(Iri("urn:friend"))
    }
    val e = assertFailsWith<IllegalStateException> { backed.friend }
    assertTrue(e is MaterializationException, "expected MaterializationException, got $e")
  }

  @Test
  fun `shared validators are created once per implementation and can be closed`() {
    class Counting : ValidationContext {
      var closed = false
      override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult = ValidationResult.Ok
      override fun close() { closed = true }
    }
    var created = 0
    val first = SharedValidators.get(Counting::class.java) { created++; Counting() }
    val second = SharedValidators.get(Counting::class.java) { created++; Counting() }
    assertSame(first, second)
    assertEquals(1, created)
    assertTrue(SharedValidators.close(Counting::class.java))
    assertTrue(first.closed)
    val third = SharedValidators.get(Counting::class.java) { created++; Counting() }
    assertNotSame(first, third)
    SharedValidators.closeAll()
    assertTrue(third.closed)
    assertEquals(false, SharedValidators.close(Counting::class.java))
  }

  @Test
  fun `a factory from a reloaded class loader replaces the previous one`() {
    OntoMapper.unregister(ReloadableType::class.java)
    try {
      val original = ReloadableTestFactory()
      OntoMapper.register(ReloadableType::class.java, original)
      OntoMapper.register(ReloadableType::class.java, original) // same instance: no-op

      // A different factory from the same class loader is still a conflict.
      assertFailsWith<IllegalStateException> {
        OntoMapper.register(ReloadableType::class.java) { _ -> ReloadableImpl(null) }
      }

      val child = ChildFirstLoader(javaClass.classLoader, ReloadableTestFactory::class.java.name)
      @Suppress("UNCHECKED_CAST")
      val reloaded = child.loadClass(ReloadableTestFactory::class.java.name).getDeclaredConstructor().newInstance() as (RdfHandle) -> ReloadableType
      OntoMapper.register(ReloadableType::class.java, reloaded)

      val instance = OntoMapper.materialize(RdfRef(Iri("urn:x"), MemoryGraph()), ReloadableType::class.java)
      assertSame(child, instance.factoryLoader, "the factory of the reloaded class loader is used")
    } finally {
      OntoMapper.unregister(ReloadableType::class.java)
    }
  }

  @Test
  fun `legacy defaulting delegates are deprecated`() {
    val literals = Class.forName("com.geoknoesis.kastor.gen.runtime.delegates.LiteralsKt")
    val deprecated = listOf("rdfString", "rdfInt", "rdfDouble", "rdfBoolean", "rdfIntOrNull", "rdfInts")
    deprecated.forEach { name ->
      val method = literals.methods.first { it.name == name || it.name.startsWith("$name-") }
      assertTrue(method.isAnnotationPresent(Deprecated::class.java), "$name should be @Deprecated")
    }
    val kept = literals.methods.first { it.name.startsWith("rdfLiteral-") }
    assertTrue(!kept.isAnnotationPresent(Deprecated::class.java))
  }
}
