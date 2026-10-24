package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException

/**
 * Index of the shapes graph. An RDF graph is a **set** of triples: a provider that hands out the same triple twice
 * (a bag-like store, a merge of overlapping sources) must not make a parameter look repeated or a list cell look
 * forked, so duplicate triples are dropped here and every value list is duplicate-free.
 */
internal class ShapeGraphIndex(triples: List<RdfTriple>, private val budget: ValidationBudget = ValidationBudget.NONE) {
    /** All (distinct) shapes-graph triples. */
    val triples: List<RdfTriple>
    private val bySubject = HashMap<RdfResource, LinkedHashMap<Iri, MutableList<RdfTerm>>>()
    private val byPredicateObject = HashMap<Iri, HashMap<RdfTerm, MutableList<RdfResource>>>()
    private val reifiers = HashMap<RdfTriple, MutableList<RdfResource>>()

    init {
        val seen = HashSet<RdfTriple>(triples.size * 2)
        var distinct: ArrayList<RdfTriple>? = null
        for ((position, t) in triples.withIndex()) {
            budget.tick("shape indexing")
            if (!seen.add(t)) {
                // First duplicate: from here on the distinct triples are collected in a copy.
                if (distinct == null) distinct = ArrayList(triples.subList(0, position))
                continue
            }
            distinct?.add(t)
            bySubject.getOrPut(t.subject) { LinkedHashMap() }.getOrPut(t.predicate) { ArrayList(1) }.add(t.obj)
            byPredicateObject.getOrPut(t.predicate) { HashMap() }.getOrPut(t.obj) { ArrayList(1) }.add(t.subject)
            if (t.predicate == RDF.reifies) {
                (t.obj as? TripleTerm)?.let { reifiers.getOrPut(it.triple) { ArrayList(1) }.add(t.subject) }
            }
        }
        this.triples = distinct ?: triples
        budget.check("shape indexing")
    }

    /** Cooperative budget check for compile-time loops over this index. */
    fun tick(phase: String) = budget.tick(phase)

    /** Graph-level `sh:declare` prefixes (used by nodes without `sh:prefixes`), computed once per index. */
    val graphLevelPrefixes: Map<String, String> by lazy { SparqlPrefixes.graphLevel(this) }

    /** SPARQL-based constraint components of this shapes graph, collected once per index. */
    val sparqlComponents: SparqlComponentCatalog by lazy { SparqlComponentCatalog.build(this) }

    /** Custom node expression functions of this shapes graph (§6), collected once per index. */
    val nodeExpressionFunctions: NodeExpressionFunctions by lazy { NodeExpressionFunctions.build(this) }

    /** A SPARQL query of this shapes graph: its text and the prefixes its node declares (`sh:prefixes`, `sh:declare`). */
    class SparqlQueryText(val text: String, val prefixes: Map<String, String>)

    /** Every SPARQL query of this shapes graph (`sh:select`, `sh:ask`, `sh:sparqlExpr` literals), collected once. */
    val sparqlQueries: List<SparqlQueryText> by lazy {
        triples.filter { it.predicate == SHACL.select || it.predicate == SHACL.ask || it.predicate == ShaclSparqlTerms.sparqlExpr }
            .mapNotNull { t ->
                tick("SPARQL function compilation")
                val text = (t.obj as? Literal)?.lexical ?: return@mapNotNull null
                val prefixes = LinkedHashMap<String, String>()
                try {
                    prefixes.putAll(SparqlPrefixes.collect(t.subject, this))
                } catch (_: ShapeCompileException) {
                    // An ill-formed declaration is reported where the query is compiled.
                }
                SparqlQueryText(text, prefixes)
            }
    }

    /**
     * The IRIs that the [sparqlQueries] may call as functions, collected once per index in one scan of each query
     * ([SparqlLexer] tokens, ticking the budget per query): every IRI reference, and every prefixed name followed by
     * `(` whose prefix the query declares (a `PREFIX` of its text, which wins, or a declaration of its node), with
     * the `\` escapes of its local name removed. Prefix names may use any letters (SPARQL PN_PREFIX). Names in
     * strings and comments are not calls.
     */
    val sparqlFunctionCalls: Set<String> by lazy {
        val out = HashSet<String>()
        for (query in sparqlQueries) {
            tick("SPARQL function compilation")
            val tokens = SparqlLexer.tokenize(query.text).filter { it.significant }
            val prefixes = HashMap(query.prefixes)
            for (i in 0 until tokens.size - 2) {
                val name = tokens[i + 1]
                if (tokens[i].isWord("PREFIX") && name.kind == SparqlToken.Kind.WORD && name.text.endsWith(":") &&
                    tokens[i + 2].kind == SparqlToken.Kind.IRI
                ) {
                    prefixes[name.text.dropLast(1)] = tokens[i + 2].text.removeSurrounding("<", ">")
                }
            }
            for ((i, token) in tokens.withIndex()) {
                if (token.kind == SparqlToken.Kind.IRI) {
                    out.add(token.text.removeSurrounding("<", ">"))
                } else if (token.kind == SparqlToken.Kind.WORD && ':' in token.text && tokens.getOrNull(i + 1)?.text == "(") {
                    val namespace = prefixes[token.text.substringBefore(':')] ?: continue
                    out.add(namespace + unescapeLocalName(token.text.substringAfter(':')))
                }
            }
        }
        out
    }

    /** [local] without the `\` of its escapes (SPARQL PN_LOCAL_ESC: `ex:a\-b` is `a-b`). */
    private fun unescapeLocalName(local: String): String {
        if ('\\' !in local) return local
        val out = StringBuilder(local.length)
        var i = 0
        while (i < local.length) {
            if (local[i] == '\\' && i + 1 < local.length) i++
            out.append(local[i])
            i++
        }
        return out.toString()
    }

    /**
     * SHACL SPARQL function declarations by node, as [SparqlFunctionDeclarations] decided them (cached per index).
     * Filled only while the shapes graph is compiled ([ShapesCompiler.compile]: feature detection and the function
     * set); validation runs never read or write it. Concurrent all the same, as a compiled graph keeps its index.
     */
    internal val sparqlFunctionDeclarations = java.util.concurrent.ConcurrentHashMap<RdfResource, Any>()

    fun objects(sub: RdfResource, pred: Iri): List<RdfTerm> = bySubject[sub]?.get(pred) ?: emptyList()

    fun hasSubject(sub: RdfResource): Boolean = bySubject.containsKey(sub)

    /** Predicates of the triples whose subject is [sub]. */
    fun predicates(sub: RdfResource): Set<Iri> = bySubject[sub]?.keys ?: emptySet()

    fun subjects(pred: Iri, obj: RdfTerm): List<RdfResource> = byPredicateObject[pred]?.get(obj) ?: emptyList()

    fun objectSingle(sub: RdfResource, pred: Iri): RdfTerm? = objects(sub, pred).singleOrNull()

    /** Reifiers naming `claim` via `rdf:reifies` (Turtle 1.2 `- {| … |}` annotations); indexed, O(1). */
    fun reifiersForClaim(claim: RdfTriple): List<RdfResource> = reifiers[claim] ?: emptyList()

    /** SHACL instance test in the shapes graph: `rdf:type/rdfs:subClassOf*` reaches [cls]. */
    fun isInstanceOf(node: RdfResource, cls: Iri): Boolean {
        val seen = HashSet<RdfTerm>()
        val queue = ArrayDeque<RdfTerm>(objects(node, RDF.type))
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (c == cls) return true
            if (c is RdfResource && seen.add(c)) queue.addAll(objects(c, com.geoknoesis.kastor.rdf.vocab.RDFS.subClassOf))
        }
        return false
    }

    /**
     * Triples describing the blank-node structure reachable from [root] (e.g. a complex `sh:path`). The rest of an
     * RDF list is followed through cells named with IRIs as well (their `rdf:first` / `rdf:rest` triples only).
     */
    fun blankNodeClosure(root: RdfTerm): List<RdfTriple> {
        val out = mutableListOf<RdfTriple>()
        val seen = HashSet<RdfResource>()
        fun visit(term: RdfTerm, listRest: Boolean) {
            when {
                term is BlankNode -> {
                    if (!seen.add(term)) return
                    bySubject[term]?.forEach { (p, objs) ->
                        objs.forEach { o ->
                            out.add(RdfTriple(term, p, o))
                            visit(o, p == RDF.rest)
                        }
                    }
                }
                term is Iri && listRest && term != RDF.nil -> {
                    if (!seen.add(term)) return
                    for (p in listOf(RDF.first, RDF.rest)) {
                        objects(term, p).forEach { o ->
                            out.add(RdfTriple(term, p, o))
                            visit(o, p == RDF.rest)
                        }
                    }
                }
            }
        }
        visit(root, false)
        return out
    }

    /**
     * Members of the RDF list starting at [head]. The list must be well-formed (a SHACL list): every cell is a blank
     * node **or an IRI** with exactly one `rdf:first` and exactly one `rdf:rest`, ending in `rdf:nil`. A missing or
     * repeated `rdf:rest` / `rdf:first` raises [ShapeCompileException]: it would otherwise silently truncate (or fork)
     * `sh:in`, `sh:or`, path lists and the like.
     */
    fun parseRdfList(head: RdfTerm): List<RdfTerm> {
        val out = mutableListOf<RdfTerm>()
        var cur: RdfTerm? = head
        val visited = mutableSetOf<RdfTerm>()
        while (cur != null) {
            budget.check("shape collection")
            if (!visited.add(cur)) throw ShapeCompileException("Cyclic RDF collection in shapes")
            when (cur) {
                RDF.nil -> return out
                is RdfResource -> {
                    val firsts = objects(cur, RDF.first)
                    if (firsts.size != 1) {
                        throw ShapeCompileException("Ill-formed RDF list: cell $cur has ${firsts.size} rdf:first values (exactly one required)")
                    }
                    val rests = objects(cur, RDF.rest)
                    if (rests.size != 1) {
                        throw ShapeCompileException("Ill-formed RDF list: cell $cur has ${rests.size} rdf:rest values (exactly one required)")
                    }
                    val rest = rests[0]
                    out.add(firsts[0])
                    if (rest == RDF.nil) break
                    cur = rest
                }
                else -> throw ShapeCompileException("Invalid RDF list cell term: $cur")
            }
        }
        return out
    }
}

/** Normalized SHACL property path for the native path engine. */
internal sealed class ShaclPath {
    data class Predicate(val iri: Iri) : ShaclPath()
    data class Inverse(val child: ShaclPath) : ShaclPath()
    data class Sequence(val segments: List<ShaclPath>) : ShaclPath()
    data class Alternative(val options: List<ShaclPath>) : ShaclPath()
    data class ZeroOrMore(val child: ShaclPath) : ShaclPath()
    data class OneOrMore(val child: ShaclPath) : ShaclPath()
    data class ZeroOrOne(val child: ShaclPath) : ShaclPath()
}

internal object ShaclPathParser {
    fun parse(term: RdfTerm, shapes: ShapeGraphIndex): ShaclPath = parse(term, shapes, emptySet())

    private fun parse(term: RdfTerm, shapes: ShapeGraphIndex, ancestors: Set<RdfTerm>): ShaclPath {
        if (term in ancestors || ancestors.size >= 128) throw ShapeCompileException("Cyclic or excessively nested SHACL path")
        val next = ancestors + term
        return when (term) {
            // `sh:path ( )` is rdf:nil: an empty sequence, not the predicate rdf:nil.
            RDF.nil -> throw ShapeCompileException(
                "Ill-formed SHACL path: an empty list is not a sequence path (a sequence path is a list of at least two paths)",
            )
            is Iri -> ShaclPath.Predicate(term)
            is BlankNode ->
                if (shapes.objects(term, RDF.first).isNotEmpty() || shapes.objects(term, RDF.rest).isNotEmpty()) {
                    ShaclPath.Sequence(members("sequence path", term, shapes, next))
                } else {
                    parseBlankPath(term, shapes, next)
                }
            else -> throw ShapeCompileException("Unsupported SHACL path term: $term")
        }
    }

    /** The member paths of the list [head] of a sequence or alternative path: SHACL requires at least two. */
    private fun members(kind: String, head: RdfTerm, shapes: ShapeGraphIndex, next: Set<RdfTerm>): List<ShaclPath> {
        val terms = shapes.parseRdfList(head)
        if (terms.size < 2) {
            throw ShapeCompileException(
                "Ill-formed SHACL path: $kind with ${terms.size} member(s); SHACL requires a list of at least two paths - " +
                    "write a single member as the path itself",
            )
        }
        return terms.map { parse(it, shapes, next) }
    }

    private fun parseBlankPath(node: BlankNode, shapes: ShapeGraphIndex, next: Set<RdfTerm>): ShaclPath {
        val triples = shapes.objects(node, SHACL.alternativePath).map { ShaclPath.Alternative(members("alternative path", it, shapes, next)) }
            .plus(shapes.objects(node, SHACL.sequencePath).map { ShaclPath.Sequence(members("sequence path", it, shapes, next)) })
            .plus(shapes.objects(node, SHACL.inversePath).map { ShaclPath.Inverse(parse(it, shapes, next)) })
            .plus(shapes.objects(node, SHACL.zeroOrMorePath).map { ShaclPath.ZeroOrMore(parse(it, shapes, next)) })
            .plus(shapes.objects(node, SHACL.oneOrMorePath).map { ShaclPath.OneOrMore(parse(it, shapes, next)) })
            .plus(shapes.objects(node, SHACL.zeroOrOnePath).map { ShaclPath.ZeroOrOne(parse(it, shapes, next)) })

        if (triples.size != 1) {
            throw ShapeCompileException(
                "SHACL path blank node must describe exactly one path constructor; got ${triples.size} for $node",
            )
        }
        return triples.first()
    }
}
