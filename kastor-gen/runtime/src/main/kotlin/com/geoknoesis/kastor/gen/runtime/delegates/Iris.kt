package com.geoknoesis.kastor.gen.runtime.delegates

import com.geoknoesis.kastor.gen.runtime.KastorGraphOps
import com.geoknoesis.kastor.gen.runtime.RdfBacked
import com.geoknoesis.kastor.rdf.Iri
import kotlin.properties.ReadOnlyProperty

fun rdfIri(predicate: Iri): ReadOnlyProperty<RdfBacked, Iri> =
  rdfLazy { ref ->
    KastorGraphOps.getObjectValues(ref.rdf.graph, ref.rdf.node, predicate) { it }
      .filterIsInstance<Iri>()
      .firstOrNull() ?: error("Required IRI object for predicate $predicate missing")
  }

fun rdfIriOrNull(predicate: Iri): ReadOnlyProperty<RdfBacked, Iri?> =
  rdfLazy { ref ->
    KastorGraphOps.getObjectValues(ref.rdf.graph, ref.rdf.node, predicate) { it }
      .filterIsInstance<Iri>()
      .firstOrNull()
  }

fun rdfIris(predicate: Iri): ReadOnlyProperty<RdfBacked, List<Iri>> =
  rdfLazy { ref ->
    KastorGraphOps.getObjectValues(ref.rdf.graph, ref.rdf.node, predicate) { it }
      .filterIsInstance<Iri>()
  }
