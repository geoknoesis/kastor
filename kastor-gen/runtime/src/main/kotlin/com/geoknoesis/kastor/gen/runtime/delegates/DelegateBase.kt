package com.geoknoesis.kastor.gen.runtime.delegates

import com.geoknoesis.kastor.gen.runtime.RdfBacked
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * Memoized [ReadOnlyProperty] over [RdfBacked], matching one-shot lazy semantics per wrapper instance.
 */
internal class LazyRdfProperty<T>(private val initializer: (RdfBacked) -> T) : ReadOnlyProperty<RdfBacked, T> {
  private val lock = Any()
  @Volatile private var memo: T? = null

  override fun getValue(thisRef: RdfBacked, property: KProperty<*>): T {
    memo?.let { return it }
    synchronized(lock) {
      memo?.let { return it }
      val v = initializer(thisRef)
      memo = v
      return v
    }
  }
}

internal fun <T> rdfLazy(block: (RdfBacked) -> T): ReadOnlyProperty<RdfBacked, T> = LazyRdfProperty(block)
