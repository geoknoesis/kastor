package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD

/**
 * Prefix declarations for SPARQL queries (SHACL 1.2 SPARQL §2). A node with `sh:prefixes` uses the declarations
 * reachable from those values; a node without it uses the declarations of every SHACL instance of `owl:Ontology`,
 * `sh:DataGraph`, `sh:ShapesGraph` or `sh:RulesGraph`. A prefix declared with two namespaces is ill-formed.
 */
internal object SparqlPrefixes {
    private val graphClasses = listOf(
        OWL.Ontology, ShaclSparqlTerms.DataGraph, ShaclSparqlTerms.ShapesGraph, ShaclSparqlTerms.RulesGraph,
    )

    /**
     * The prefixes of the SPARQL queries of [node]. The map is computed once per set of `sh:prefixes` values (and
     * once for the graph-level declarations) and shared, failures included ([ShapeGraphIndex.prefixMaps]): queries
     * that share a holder of many declarations must not each copy and check all of them.
     */
    fun collect(node: RdfResource, index: ShapeGraphIndex): Map<String, String> {
        val holders = index.objects(node, SHACL.prefixes)
        holders.forEach {
            if (it !is RdfResource) throw ShapeCompileException("The values of sh:prefixes are IRIs or blank nodes, but $node has $it")
        }
        val key: Any = holders.ifEmpty { GRAPH_LEVEL }
        val result = index.prefixMaps[key] ?: try {
            Result.success(compute(holders, index))
        } catch (e: ShapeCompileException) {
            Result.failure(e)
        }.also { index.prefixMaps[key] = it }
        return result.getOrThrow()
    }

    /** The [ShapeGraphIndex.prefixMaps] key of the graph-level declarations. */
    private val GRAPH_LEVEL = Any()

    private fun compute(holders: List<RdfTerm>, index: ShapeGraphIndex): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (holders.isNotEmpty()) {
            val visited = HashSet<RdfTerm>()
            val queue = ArrayDeque<RdfTerm>()
            holders.forEach { queue.addAll(withVersionedSubjects(it, index)) }
            while (queue.isNotEmpty()) {
                index.tick("SPARQL prefix collection")
                val holder = queue.removeFirst()
                if (holder !is RdfResource || !visited.add(holder)) continue
                declarations(holder, index, out)
                index.objects(holder, OWL.imports).forEach { queue.addAll(withVersionedSubjects(it, index)) }
            }
        } else {
            out.putAll(graphLevel(index))
        }
        for ((p, ns) in out) {
            index.tick("SPARQL prefix collection")
            if (SparqlQueryTemplate.renderIri(ns) == null) {
                throw ShapeCompileException("sh:namespace <$ns> of prefix '$p' is not a valid SPARQL IRI")
            }
        }
        return out
    }

    /** Declarations of all SHACL instances of the graph classes (computed once per index through [collect]). */
    fun graphLevel(index: ShapeGraphIndex): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        // Only resources that declare prefixes matter; instance-hood includes rdfs:subClassOf.
        val declarers = LinkedHashSet<RdfResource>()
        for (t in index.triples) {
            index.tick("SPARQL prefix collection")
            if (t.predicate == SHACL.declare) declarers.add(t.subject)
        }
        for (r in declarers) {
            index.tick("SPARQL prefix collection")
            if (graphClasses.any { index.isInstanceOf(r, it) }) declarations(r, index, out)
        }
        return out
    }

    /**
     * `PREFIX p: <ns>` lines for the [prefixes] that [query] may use; a prefix that is not a SPARQL PN_PREFIX or a
     * namespace that cannot be written as a SPARQL IRI is ill-formed, used or not.
     *
     * A prefix is used when it is the prefix of a prefixed name of [query] as written, or with its codepoint escapes
     * decoded strictly (as RDF4J does) or leniently ([usedPrefixNames]). Declaring the others would only grow every
     * query by every declaration of the shapes graph: n declarations and n queries would cost n * n in text, in
     * restriction checks and in the memory of the compiled templates. Each token ticks the budget of [index].
     */
    fun prologue(prefixes: Map<String, String>, query: String, index: ShapeGraphIndex): String {
        val out = StringBuilder()
        for (p in usedPrefixNames(query, index)) {
            val ns = prefixes[p] ?: continue
            // [collect] checked every declaration; checked again here, as the label is written verbatim.
            if (!SparqlQueryTemplate.isPrefixLabel(p)) throw ShapeCompileException("sh:prefix \"$p\" is not a valid SPARQL prefix name")
            val namespace = SparqlQueryTemplate.renderIri(ns)
                ?: throw ShapeCompileException("sh:namespace <$ns> of prefix '$p' is not a valid SPARQL IRI")
            out.append("PREFIX ").append(p).append(": ").append(namespace).append('\n')
        }
        return out.toString()
    }

    /** The prefix names of the prefixed names (WORD tokens with a `:`) of [query], in its three decoding views. */
    private fun usedPrefixNames(query: String, index: ShapeGraphIndex): Set<String> {
        val strict = try {
            SparqlQueryTemplate.decodeCodepointEscapes(query, strict = true)
        } catch (_: ShapeCompileException) {
            query // reported where the query is compiled
        }
        val out = LinkedHashSet<String>()
        for (view in linkedSetOf(query, strict, SparqlQueryTemplate.decodeCodepointEscapes(query, strict = false))) {
            for (token in SparqlLexer.tokenize(view)) {
                index.tick("SPARQL prefix collection")
                if (token.kind == SparqlToken.Kind.WORD && ':' in token.text) out.add(token.text.substringBefore(':'))
            }
        }
        return out
    }

    /** The holder itself plus the resources whose `owl:versionIRI` it is (`^owl:versionIRI`). */
    private fun withVersionedSubjects(holder: RdfTerm, index: ShapeGraphIndex): List<RdfTerm> =
        listOf(holder) + index.subjects(OWL.versionIRI, holder)

    private fun declarations(holder: RdfResource, index: ShapeGraphIndex, out: MutableMap<String, String>) {
        for (decl in index.objects(holder, SHACL.declare)) {
            val d = decl as? RdfResource ?: throw ShapeCompileException("sh:declare value must be a resource: $decl")
            val prefixTerm = index.objectSingle(d, SHACL.prefixProperty) as? Literal
                ?: throw ShapeCompileException("sh:declare $d must have exactly one sh:prefix literal")
            val namespaceTerm = index.objectSingle(d, SHACL.namespaceProperty) as? Literal
                ?: throw ShapeCompileException("sh:declare $d must have exactly one sh:namespace literal")
            if (prefixTerm.datatype != XSD.string) {
                throw ShapeCompileException("sh:prefix of $d must be an xsd:string literal: $prefixTerm")
            }
            if (namespaceTerm.datatype != XSD.string && namespaceTerm.datatype != XSD.anyURI) {
                throw ShapeCompileException("sh:namespace of $d must be an xsd:string or xsd:anyURI literal: $namespaceTerm")
            }
            val prefix = prefixTerm.lexical
            // The label is written verbatim into the query prologue: anything but a PN_PREFIX could inject query text.
            if (!SparqlQueryTemplate.isPrefixLabel(prefix)) {
                throw ShapeCompileException("sh:prefix of $d is not a valid SPARQL prefix name: \"$prefix\"")
            }
            val namespace = namespaceTerm.lexical
            val existing = out.putIfAbsent(prefix, namespace)
            if (existing != null && existing != namespace) {
                throw ShapeCompileException("Conflicting sh:declare for prefix '$prefix': <$existing> vs <$namespace>")
            }
        }
    }
}
