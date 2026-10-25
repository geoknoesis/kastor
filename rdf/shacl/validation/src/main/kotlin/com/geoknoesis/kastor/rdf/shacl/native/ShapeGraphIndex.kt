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
internal class ShapeGraphIndex(triples: List<RdfTriple>, budget: ValidationBudget = ValidationBudget.NONE) {
    /**
     * The budget of the compilation that built this index. A compiled graph keeps its index across runs
     * ([NativeCompileCache]), so [ShapesCompiler.compile] [detaches][detachBudget] it once compilation ends: a later
     * run must never be timed by the clock of the run that compiled the graph.
     */
    @Volatile
    var budget: ValidationBudget = budget
        private set

    /** Ends the compile-time use of [budget] (see there). */
    fun detachBudget() {
        budget = ValidationBudget.NONE
    }

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

    /** A SPARQL query of this shapes graph: its [node], its text and the prefixes its node declares (`sh:prefixes`, `sh:declare`). */
    class SparqlQueryText(val node: RdfResource, val text: String, val prefixes: Map<String, String>)

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
                SparqlQueryText(t.subject, text, prefixes)
            }
    }

    /**
     * The functions that the [sparqlQueries] may call, collected once per index in one pass over the queries that
     * ticks the budget once per query.
     *
     * Each query is scanned ([SparqlLexer] tokens) in three views: as written, with its codepoint escapes decoded
     * strictly (as RDF4J does), and decoded leniently ([SparqlQueryTemplate.decodeCodepointEscapes]). The calls of
     * all views are kept. RDF4J decodes `\u` / `\U` escapes in the whole query before parsing, so an escape can end
     * a string or a comment early (`'a' + ex:f() + 'b'`, `# c \u000A FILTER (ex:f())`) or spell part of
     * an IRI, a name or a `PREFIX` / `BASE` IRI. A name that is inside a string or a comment in every view is not a
     * call.
     *
     * A call is every IRI reference, and every prefixed name followed by `(` whose prefix the view declares (a
     * `PREFIX` of its text, which wins, or a declaration of the query node), with the `\` escapes of its local name
     * removed. Prefix names may use any letters (SPARQL PN_PREFIX). A relative IRI (a reference or a namespace) is
     * resolved (RFC 3986 5.2) against the `BASE` the query declares before it. Without an absolute `BASE` the base is
     * the provider's own, which Kastor does not know, so a relative IRI that is called makes the query a possible
     * call of every function ([SparqlFunctionCalls.any]). That is sound, and only queries with such calls pay for it.
     */
    val sparqlFunctionCalls: SparqlFunctionCalls by lazy {
        val values = sparqlCallsByNode.values
        SparqlFunctionCalls(values.flatMapTo(HashSet()) { it.iris }, values.any { it.any })
    }

    /**
     * The functions the queries of each query node of [sparqlQueries] (the subject of its `sh:select`, `sh:ask` or
     * `sh:sparqlExpr`) may call, as [sparqlFunctionCalls] collects them; [sparqlFunctionCalls] is their union. A node
     * without a query here is unknown ([callsOf]).
     */
    val sparqlCallsByNode: Map<RdfResource, SparqlFunctionCalls> by lazy {
        val iris = LinkedHashMap<RdfResource, HashSet<String>>()
        val any = HashSet<RdfResource>()
        for (query in sparqlQueries) {
            tick("SPARQL function compilation")
            val out = iris.getOrPut(query.node) { HashSet() }
            val strict = try {
                SparqlQueryTemplate.decodeCodepointEscapes(query.text, strict = true)
            } catch (_: ShapeCompileException) {
                query.text
            }
            for (view in linkedSetOf(query.text, strict, SparqlQueryTemplate.decodeCodepointEscapes(query.text, strict = false))) {
                if (collectCalls(view, query.prefixes, out)) any.add(query.node)
            }
        }
        iris.mapValues { (node, calls) -> SparqlFunctionCalls(calls, node in any) }
    }

    /** The functions the queries of [node] may call ([sparqlCallsByNode]); every function for a node without a query. */
    fun callsOf(node: RdfResource): SparqlFunctionCalls = sparqlCallsByNode[node] ?: SparqlFunctionCalls.ANY

    /** The functions some SPARQL query may call: those of [iris], or every function when [any]. */
    class SparqlFunctionCalls(val iris: Set<String>, val any: Boolean) {
        fun mayCall(iri: String): Boolean = any || iri in iris

        companion object {
            val ANY = SparqlFunctionCalls(emptySet(), any = true)
        }
    }

    /**
     * Adds the calls of the SPARQL text [text] to [out] (see [sparqlFunctionCalls]). Returns true when the text calls
     * a relative IRI whose base is unknown.
     */
    private fun collectCalls(text: String, nodePrefixes: Map<String, String>, out: MutableSet<String>): Boolean {
        val tokens = SparqlLexer.tokenize(text).filter { it.significant }
        // Namespaces by prefix name; null for a relative namespace whose base is unknown.
        val prefixes = HashMap<String, String?>()
        for ((name, namespace) in nodePrefixes) prefixes[name] = namespace.takeIf { isAbsoluteIri(it) }
        // The absolute base the query declares so far, or null (none, or a relative one).
        var base: String? = null
        // Resolution ticks the budget per path segment: an IRI token can be as long as the query.
        val segmentTick = { tick("SPARQL function compilation") }
        fun resolve(reference: String): String? = when {
            isAbsoluteIri(reference) -> resolveIri(reference, reference, segmentTick)
            base != null -> resolveIri(base!!, reference, segmentTick)
            else -> null
        }
        var unknownCall = false
        for ((i, token) in tokens.withIndex()) {
            tick("SPARQL function compilation")
            val next = tokens.getOrNull(i + 1)
            when {
                token.isWord("BASE") && next?.kind == SparqlToken.Kind.IRI -> base = resolve(next.text.removeSurrounding("<", ">"))
                token.isWord("PREFIX") && next?.kind == SparqlToken.Kind.WORD && next.text.endsWith(":") &&
                    tokens.getOrNull(i + 2)?.kind == SparqlToken.Kind.IRI ->
                    prefixes[next.text.dropLast(1)] = resolve(tokens[i + 2].text.removeSurrounding("<", ">"))
                token.kind == SparqlToken.Kind.IRI -> {
                    val iri = resolve(token.text.removeSurrounding("<", ">"))
                    if (iri != null) out.add(iri) else if (next?.text == "(") unknownCall = true
                }
                token.kind == SparqlToken.Kind.WORD && ':' in token.text && next?.text == "(" -> {
                    val prefix = token.text.substringBefore(':')
                    if (prefix !in prefixes) continue
                    val namespace = prefixes[prefix]
                    if (namespace == null) unknownCall = true else out.add(namespace + unescapeLocalName(token.text.substringAfter(':')))
                }
            }
        }
        return unknownCall
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

    /**
     * The triples whose subject is the blank node [node], in index order: what a deep copy (SHACL 1.2 Core, "Deep
     * Copy") takes of each blank node it reaches. The report writer follows the blank-node objects itself
     * ([com.geoknoesis.kastor.rdf.shacl.SourceConstraintTriples]).
     */
    fun ownTriples(node: BlankNode): List<RdfTriple> =
        bySubject[node].orEmpty().flatMap { (predicate, objects) -> objects.map { RdfTriple(node, predicate, it) } }

    fun objects(sub: RdfResource, pred: Iri): List<RdfTerm> = bySubject[sub]?.get(pred) ?: emptyList()

    fun hasSubject(sub: RdfResource): Boolean = bySubject.containsKey(sub)

    /** Predicates of the triples whose subject is [sub]. */
    fun predicates(sub: RdfResource): Set<Iri> = bySubject[sub]?.keys ?: emptySet()

    fun subjects(pred: Iri, obj: RdfTerm): List<RdfResource> = byPredicateObject[pred]?.get(obj) ?: emptyList()

    fun objectSingle(sub: RdfResource, pred: Iri): RdfTerm? = objects(sub, pred).singleOrNull()

    /** Reifiers naming `claim` via `rdf:reifies` (Turtle 1.2 `- {| … |}` annotations); indexed, O(1). */
    fun reifiersForClaim(claim: RdfTriple): List<RdfResource> = reifiers[claim] ?: emptyList()

    /** SHACL instance test in the shapes graph: `rdf:type/rdfs:subClassOf*` reaches [cls]. */
    /**
     * Whether [node] is a SHACL subclass of [cls] (SHACL 1.2 Core: a sequence of one or more `rdfs:subClassOf` triples
     * leads from [node] to [cls]). Each class is visited once, so cycles terminate.
     */
    fun isSubClassOf(node: RdfResource, cls: Iri): Boolean {
        val seen = HashSet<RdfTerm>()
        val queue = ArrayDeque<RdfTerm>(objects(node, com.geoknoesis.kastor.rdf.vocab.RDFS.subClassOf))
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (c == cls) return true
            if (c is RdfResource && seen.add(c)) queue.addAll(objects(c, com.geoknoesis.kastor.rdf.vocab.RDFS.subClassOf))
        }
        return false
    }

    /**
     * Whether [function] is a custom list parameter function of Node Expressions §6.2 (Appendix A,
     * CustomListParameterFunction-syntax): a SHACL instance of `sh:ListParameterExpressionFunction` and a SHACL subclass
     * of `sh:ListParameterExpression`.
     */
    fun isCustomListFunction(function: RdfResource): Boolean =
        isInstanceOf(function, ShaclSparqlTerms.ListParameterExpressionFunction) &&
            isSubClassOf(function, ShaclSparqlTerms.ListParameterExpression)

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
     * Depth-first, in index order, with an explicit stack: a long list must not overflow the call stack. Each node is
     * visited once and ticks the budget.
     */
    fun blankNodeClosure(root: RdfTerm): List<RdfTriple> {
        val out = mutableListOf<RdfTriple>()
        val seen = HashSet<RdfResource>()
        val stack = ArrayDeque<Iterator<RdfTriple>>()
        fun enter(term: RdfTerm, listRest: Boolean) {
            when {
                term is BlankNode -> if (seen.add(term)) {
                    stack.addLast(ownTriples(term).iterator())
                }
                term is Iri && listRest && term != RDF.nil -> if (seen.add(term)) {
                    stack.addLast(listOf(RDF.first, RDF.rest).flatMap { p -> objects(term, p).map { RdfTriple(term, p, it) } }.iterator())
                }
            }
        }
        enter(root, false)
        while (stack.isNotEmpty()) {
            val top = stack.last()
            if (!top.hasNext()) { stack.removeLast(); continue }
            tick("path compilation")
            val t = top.next()
            out.add(t)
            enter(t.obj, t.predicate == RDF.rest)
        }
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
