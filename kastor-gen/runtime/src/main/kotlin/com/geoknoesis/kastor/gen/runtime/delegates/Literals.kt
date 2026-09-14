package com.geoknoesis.kastor.gen.runtime.delegates

import com.geoknoesis.kastor.gen.runtime.KastorGraphOps
import com.geoknoesis.kastor.gen.runtime.MaterializationPolicy
import com.geoknoesis.kastor.gen.runtime.RdfBacked
import com.geoknoesis.kastor.gen.runtime.XsdLiterals
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import kotlin.properties.ReadOnlyProperty

private const val XSD_LITERALS = "com.geoknoesis.kastor.gen.runtime.XsdLiterals"
private const val DEFAULTING = "invents a default value when the value is missing instead of throwing MaterializationException"
private const val DROPPING = "silently drops values that cannot be decoded instead of following MaterializationPolicy"

@Deprecated("rdfString $DEFAULTING", ReplaceWith("rdfLiteral(predicate, XsdLiterals::string)", XSD_LITERALS))
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

@Deprecated("rdfInt $DEFAULTING", ReplaceWith("rdfLiteral(predicate, XsdLiterals::int)", XSD_LITERALS))
fun rdfInt(predicate: Iri): ReadOnlyProperty<RdfBacked, Int> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate)
      .mapNotNull { it.lexical.toIntOrNull() }
      .firstOrNull() ?: 0
  }

@Deprecated("rdfIntOrNull $DROPPING", ReplaceWith("rdfLiteralOrNull(predicate, XsdLiterals::int)", XSD_LITERALS))
fun rdfIntOrNull(predicate: Iri): ReadOnlyProperty<RdfBacked, Int?> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate)
      .mapNotNull { it.lexical.toIntOrNull() }
      .firstOrNull()
  }

@Deprecated("rdfInts $DROPPING", ReplaceWith("rdfLiterals(predicate, XsdLiterals::int)", XSD_LITERALS))
fun rdfInts(predicate: Iri): ReadOnlyProperty<RdfBacked, List<Int>> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate).mapNotNull { it.lexical.toIntOrNull() }
  }

@Deprecated("rdfDouble $DEFAULTING", ReplaceWith("rdfLiteral(predicate, XsdLiterals::double)", XSD_LITERALS))
fun rdfDouble(predicate: Iri): ReadOnlyProperty<RdfBacked, Double> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate)
      .mapNotNull { it.lexical.toDoubleOrNull() }
      .firstOrNull() ?: 0.0
  }

@Deprecated("rdfDoubleOrNull $DROPPING", ReplaceWith("rdfLiteralOrNull(predicate, XsdLiterals::double)", XSD_LITERALS))
fun rdfDoubleOrNull(predicate: Iri): ReadOnlyProperty<RdfBacked, Double?> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate)
      .mapNotNull { it.lexical.toDoubleOrNull() }
      .firstOrNull()
  }

@Deprecated("rdfDoubles $DROPPING", ReplaceWith("rdfLiterals(predicate, XsdLiterals::double)", XSD_LITERALS))
fun rdfDoubles(predicate: Iri): ReadOnlyProperty<RdfBacked, List<Double>> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate).mapNotNull { it.lexical.toDoubleOrNull() }
  }

@Deprecated("rdfBoolean $DEFAULTING", ReplaceWith("rdfLiteral(predicate, XsdLiterals::boolean)", XSD_LITERALS))
fun rdfBoolean(predicate: Iri): ReadOnlyProperty<RdfBacked, Boolean> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate)
      .mapNotNull { XsdLiterals.boolean(it) }
      .firstOrNull() ?: false
  }

@Deprecated("rdfBooleanOrNull $DROPPING", ReplaceWith("rdfLiteralOrNull(predicate, XsdLiterals::boolean)", XSD_LITERALS))
fun rdfBooleanOrNull(predicate: Iri): ReadOnlyProperty<RdfBacked, Boolean?> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate)
      .mapNotNull { XsdLiterals.boolean(it) }
      .firstOrNull()
  }

@Deprecated("rdfBooleans $DROPPING", ReplaceWith("rdfLiterals(predicate, XsdLiterals::boolean)", XSD_LITERALS))
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
