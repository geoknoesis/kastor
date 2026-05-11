package com.geoknoesis.kastor.gen.runtime.delegates

import com.geoknoesis.kastor.gen.runtime.KastorGraphOps
import com.geoknoesis.kastor.gen.runtime.RdfBacked
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import kotlin.properties.ReadOnlyProperty

/** First plain or language-tagged literal lexical for the given BCP47 tag (case-insensitive). */
fun rdfLangString(predicate: Iri, lang: String): ReadOnlyProperty<RdfBacked, String?> =
  rdfLazy { ref ->
    val want = lang.lowercase()
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate)
      .firstOrNull { lit ->
        when (lit) {
          is LangString -> lit.lang.lowercase() == want
          else -> false
        }
      }
      ?.let { (it as LangString).lexical }
  }

/** Map of language tag → lexical for all language-tagged literals on [predicate]. */
fun rdfLangStringMap(predicate: Iri): ReadOnlyProperty<RdfBacked, Map<String, String>> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate)
      .mapNotNull { lit ->
        when (lit) {
          is LangString -> lit.lang to lit.lexical
          else -> null
        }
      }
      .toMap()
  }

/** Lexical form of the first literal (any datatype), or null. */
fun rdfLexicalFirstOrNull(predicate: Iri): ReadOnlyProperty<RdfBacked, String?> =
  rdfLazy { ref ->
    KastorGraphOps.getLiteralValues(ref.rdf.graph, ref.rdf.node, predicate).map { it.lexical }.firstOrNull()
  }
