package com.geoknoesis.kastor.gen.runtime.delegates

import com.geoknoesis.kastor.gen.runtime.KastorGraphOps
import com.geoknoesis.kastor.gen.runtime.OntoMapper
import com.geoknoesis.kastor.gen.runtime.RdfBacked
import com.geoknoesis.kastor.gen.runtime.RdfRef
import com.geoknoesis.kastor.rdf.Iri
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass

inline fun <reified T : Any> rdfObject(predicate: Iri): ReadOnlyProperty<RdfBacked, T> =
  rdfObject(predicate, T::class)

fun <T : Any> rdfObject(predicate: Iri, type: KClass<T>): ReadOnlyProperty<RdfBacked, T> =
  rdfLazy { ref ->
    KastorGraphOps.getObjectValues(ref.rdf.graph, ref.rdf.node, predicate) { child ->
      OntoMapper.materialize(RdfRef(child, ref.rdf.graph), type.java)
    }.firstOrNull() ?: error("Required object of type ${type.simpleName} for predicate $predicate missing")
  }

inline fun <reified T : Any> rdfObjectOrNull(predicate: Iri): ReadOnlyProperty<RdfBacked, T?> =
  rdfObjectOrNull(predicate, T::class)

fun <T : Any> rdfObjectOrNull(predicate: Iri, type: KClass<T>): ReadOnlyProperty<RdfBacked, T?> =
  rdfLazy { ref ->
    KastorGraphOps.getObjectValues(ref.rdf.graph, ref.rdf.node, predicate) { child ->
      OntoMapper.materialize(RdfRef(child, ref.rdf.graph), type.java)
    }.firstOrNull()
  }

inline fun <reified T : Any> rdfObjects(predicate: Iri): ReadOnlyProperty<RdfBacked, List<T>> =
  rdfObjects(predicate, T::class)

fun <T : Any> rdfObjects(predicate: Iri, type: KClass<T>): ReadOnlyProperty<RdfBacked, List<T>> =
  rdfLazy { ref ->
    KastorGraphOps.getObjectValues(ref.rdf.graph, ref.rdf.node, predicate) { child ->
      OntoMapper.materialize(RdfRef(child, ref.rdf.graph), type.java)
    }
  }
