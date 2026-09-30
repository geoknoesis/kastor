package com.geoknoesis.kastor.gen.runtime.delegates

import com.geoknoesis.kastor.gen.runtime.MaterializationPolicy
import com.geoknoesis.kastor.gen.runtime.RdfBacked
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * Memoized [ReadOnlyProperty] over [RdfBacked], matching one-shot lazy semantics per wrapper instance.
 *
 * The delegate is created with its wrapper, so it captures the [MaterializationPolicy] ill-typed-value handling in
 * effect then and applies it when the value is first read.
 */
internal class LazyRdfProperty<T>(private val initializer: (RdfBacked) -> T) : ReadOnlyProperty<RdfBacked, T> {
  private val lock = Any()
  private val handling = MaterializationPolicy.illTypedValues

  // UNSET distinguishes "not yet computed" from "computed as null". Using a plain
  // nullable `memo` would re-run the initializer on every access for properties
  // whose value is legitimately null (e.g. ...OrNull delegates), defeating memoization.
  private object UNSET
  @Volatile private var memo: Any? = UNSET

  override fun getValue(thisRef: RdfBacked, property: KProperty<*>): T {
    val cached = memo
    if (cached !== UNSET) {
      @Suppress("UNCHECKED_CAST")
      return cached as T
    }
    synchronized(lock) {
      val current = memo
      if (current !== UNSET) {
        @Suppress("UNCHECKED_CAST")
        return current as T
      }
      val v = MaterializationPolicy.withIllTypedValues(handling) { initializer(thisRef) }
      memo = v
      return v
    }
  }
}

internal fun <T> rdfLazy(block: (RdfBacked) -> T): ReadOnlyProperty<RdfBacked, T> = LazyRdfProperty(block)
