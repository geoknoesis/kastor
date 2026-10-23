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

    fun collect(node: RdfResource, index: ShapeGraphIndex): Map<String, String> {
        val holders = index.objects(node, SHACL.prefixes)
        val out = LinkedHashMap<String, String>()
        if (holders.isNotEmpty()) {
            val visited = HashSet<RdfTerm>()
            val queue = ArrayDeque<RdfTerm>()
            holders.forEach { queue.addAll(withVersionedSubjects(it, index)) }
            while (queue.isNotEmpty()) {
                val holder = queue.removeFirst()
                if (holder !is RdfResource || !visited.add(holder)) continue
                declarations(holder, index, out)
                index.objects(holder, OWL.imports).forEach { queue.addAll(withVersionedSubjects(it, index)) }
            }
        } else {
            // Only resources that declare prefixes matter; instance-hood includes rdfs:subClassOf.
            val declarers = index.triples.filter { it.predicate == SHACL.declare }.map { it.subject }.distinct()
            declarers.filter { r -> graphClasses.any { index.isInstanceOf(r, it) } }.forEach { declarations(it, index, out) }
        }
        return out
    }

    /** `PREFIX p: <ns>` lines for [prefixes]; a namespace that cannot be written as a SPARQL IRI is ill-formed. */
    fun prologue(prefixes: Map<String, String>): String =
        prefixes.entries.joinToString("") { (p, ns) ->
            val namespace = SparqlQueryTemplate.renderIri(ns)
                ?: throw ShapeCompileException("sh:namespace <$ns> of prefix '$p' is not a valid SPARQL IRI")
            "PREFIX $p: $namespace\n"
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
            val namespace = namespaceTerm.lexical
            val existing = out.putIfAbsent(prefix, namespace)
            if (existing != null && existing != namespace) {
                throw ShapeCompileException("Conflicting sh:declare for prefix '$prefix': <$existing> vs <$namespace>")
            }
        }
    }
}
