package com.geoknoesis.kastor.gen.runtime.delegates

import com.geoknoesis.kastor.gen.runtime.KastorGraphOps
import com.geoknoesis.kastor.gen.runtime.RdfBacked
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
      .mapNotNull { it.lexical.toBooleanStrictOrNull() }
      .firstOrNull() ?: false
  }

fun rdfBooleanOrNull(predicate: Iri): ReadOnlyProperty<RdfBacked, Boolean?> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate)
      .mapNotNull { it.lexical.toBooleanStrictOrNull() }
      .firstOrNull()
  }

fun rdfBooleans(predicate: Iri): ReadOnlyProperty<RdfBacked, List<Boolean>> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate).mapNotNull { it.lexical.toBooleanStrictOrNull() }
  }

fun <T : Any> rdfLiteral(predicate: Iri, decoder: (Literal) -> T?): ReadOnlyProperty<RdfBacked, T> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate)
      .mapNotNull { decoder(it) }
      .firstOrNull() ?: error("Required literal for predicate $predicate missing or did not decode")
  }

fun <T : Any> rdfLiteralOrNull(predicate: Iri, decoder: (Literal) -> T?): ReadOnlyProperty<RdfBacked, T?> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate).mapNotNull { decoder(it) }.firstOrNull()
  }
