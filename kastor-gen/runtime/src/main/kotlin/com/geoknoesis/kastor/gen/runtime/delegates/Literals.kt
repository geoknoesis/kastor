package com.geoknoesis.kastor.gen.runtime.delegates

import com.geoknoesis.kastor.gen.runtime.KastorGraphOps
import com.geoknoesis.kastor.gen.runtime.MaterializationPolicy
import com.geoknoesis.kastor.gen.runtime.RdfBacked
import com.geoknoesis.kastor.gen.runtime.XsdLiterals
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import kotlin.properties.ReadOnlyProperty

fun rdfString(predicate: Iri): ReadOnlyProperty<RdfBacked, String> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate).map { it.lexical }.firstOrNull() ?: ""
  }

fun rdfStringOrNull(predicate: Iri): ReadOnlyProperty<RdfBacked, String?> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate).map { it.lexical }.firstOrNull()
  }

fun rdfStrings(predicate: Iri): ReadOnlyProperty<RdfBacked, List<String>> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate).map { it.lexical }
  }

fun rdfInt(predicate: Iri): ReadOnlyProperty<RdfBacked, Int> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate)
      .mapNotNull { it.lexical.toIntOrNull() }
      .firstOrNull() ?: 0
  }

fun rdfIntOrNull(predicate: Iri): ReadOnlyProperty<RdfBacked, Int?> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate)
      .mapNotNull { it.lexical.toIntOrNull() }
      .firstOrNull()
  }

fun rdfInts(predicate: Iri): ReadOnlyProperty<RdfBacked, List<Int>> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate).mapNotNull { it.lexical.toIntOrNull() }
  }

fun rdfDouble(predicate: Iri): ReadOnlyProperty<RdfBacked, Double> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate)
      .mapNotNull { it.lexical.toDoubleOrNull() }
      .firstOrNull() ?: 0.0
  }

fun rdfDoubleOrNull(predicate: Iri): ReadOnlyProperty<RdfBacked, Double?> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate)
      .mapNotNull { it.lexical.toDoubleOrNull() }
      .firstOrNull()
  }

fun rdfDoubles(predicate: Iri): ReadOnlyProperty<RdfBacked, List<Double>> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate).mapNotNull { it.lexical.toDoubleOrNull() }
  }

fun rdfBoolean(predicate: Iri): ReadOnlyProperty<RdfBacked, Boolean> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate)
      .mapNotNull { XsdLiterals.boolean(it) }
      .firstOrNull() ?: false
  }

fun rdfBooleanOrNull(predicate: Iri): ReadOnlyProperty<RdfBacked, Boolean?> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate)
      .mapNotNull { XsdLiterals.boolean(it) }
      .firstOrNull()
  }

fun rdfBooleans(predicate: Iri): ReadOnlyProperty<RdfBacked, List<Boolean>> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate).mapNotNull { XsdLiterals.boolean(it) }
  }

/** Decodes every literal of [predicate]; values [decoder] rejects follow [MaterializationPolicy.illTypedValues]. */
private fun <T : Any> decodeAll(ref: RdfBacked, predicate: Iri, decoder: (Literal) -> T?): List<T> =
  KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate).mapNotNull { literal ->
    decoder(literal) ?: MaterializationPolicy.illTyped(literal, "<${predicate.value}>", "a value accepted by its decoder")
  }

/**
 * Required single literal: the first decoded value. Ill-typed values follow [MaterializationPolicy]; no (remaining)
 * value throws [com.geoknoesis.kastor.gen.runtime.MaterializationException].
 */
fun <T : Any> rdfLiteral(predicate: Iri, decoder: (Literal) -> T?): ReadOnlyProperty<RdfBacked, T> =
  rdfLazy { ref ->
    decodeAll(ref, predicate, decoder).firstOrNull() ?: MaterializationPolicy.missingRequired("<${predicate.value}>")
  }

/** Optional single literal: the first decoded value or `null`. Ill-typed values follow [MaterializationPolicy]. */
fun <T : Any> rdfLiteralOrNull(predicate: Iri, decoder: (Literal) -> T?): ReadOnlyProperty<RdfBacked, T?> =
  rdfLazy { ref -> decodeAll(ref, predicate, decoder).firstOrNull() }

/** All decoded literals. Ill-typed values follow [MaterializationPolicy]. */
fun <T : Any> rdfLiterals(predicate: Iri, decoder: (Literal) -> T?): ReadOnlyProperty<RdfBacked, List<T>> =
  rdfLazy { ref -> decodeAll(ref, predicate, decoder) }
