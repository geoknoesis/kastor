package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.query.QueryFactory
import org.apache.jena.query.ResultSetFormatter
import org.apache.jena.query.Syntax
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.update.UpdateFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Parse [sparql] with Jena ARQ (SPARQL 1.2 grammar); fails the test with the query text on error. */
internal fun assertParsesQuery(sparql: String): String {
    try {
        QueryFactory.create(sparql, Syntax.syntaxSPARQL_12)
    } catch (e: Exception) {
        fail<Unit>("Rendered query does not parse: ${e.message}\n$sparql")
    }
    return sparql
}

/** Parse [sparql] as a SPARQL Update request with Jena ARQ. */
internal fun assertParsesUpdate(sparql: String): String {
    try {
        UpdateFactory.create(sparql, Syntax.syntaxSPARQL_12)
    } catch (e: Exception) {
        fail<Unit>("Rendered update does not parse: ${e.message}\n$sparql")
    }
    return sparql
}

class SparqlRendererGrammarTest {

    private val s = `var`("s")
    private val o = `var`("o")
    private val p = Iri("urn:p")
    private val a = Iri("urn:a")
    private val b = Iri("urn:b")
    private val c = Iri("urn:c")

    private fun rowCount(model: Model, sparql: String): Int =
        QueryExecutionFactory.create(assertParsesQuery(sparql), model).use { ResultSetFormatter.consume(it.execSelect()) }

    // ---------------------------------------------------------------- literals / injection

    @Test
    fun `hostile string literals cannot change the query and still match exactly`() {
        val hostile = listOf(
            "a\" || true || \"",
            "back\\slash\\",
            "line\nbreak\r\ttab",
            "\"\"\"triple-quoted\"\"\"",
            "'''single'''",
            "ctl\u0001\u0008\u000C\u007F",
            "} } DROP ALL ; #",
            "escaped\\u0022 || true || \\u0022",
            "unicode αβγ 😀",
        )
        for (value in hostile) {
            val model = ModelFactory.createDefaultModel()
            model.add(model.createResource("urn:x"), model.createProperty("urn:name"), value)
            model.add(model.createResource("urn:y"), model.createProperty("urn:name"), "bob")
            val query = select("s") {
                where {
                    triple(s, Iri("urn:name"), `var`("name"))
                    filter(`var`("name") eq value)
                }
            }.sparql
            assertEquals(1, rowCount(model, query), "value <$value> must match exactly one row:\n$query")
        }
    }

    @Test
    fun `literals in regex contains replace and lang functions are escaped`() {
        // Must stay a valid regular expression: ARQ compiles constant REGEX patterns at parse time.
        val evil = "x\" || true || \" "
        val query = select("s") {
            where {
                triple(s, p, o)
                filter(regex(o, evil, "i"))
                filter(contains(o.expr(), evil))
                bind(`var`("r"), replace(o.expr(), evil, evil))
                filter(`var`("s") ne evil)
            }
        }.sparql
        assertParsesQuery(query)
        assertTrue(query.contains("\"x\\\" || true || \\\" \"^^"), query)
    }

    @Test
    fun `typed boolean language and directional literals render with escaping`() {
        val query = select("s") {
            where {
                triple(s, p, Literal("a\"b", Iri("urn:dt")))
                triple(s, p, TrueLiteral)
                triple(s, p, FalseLiteral)
                triple(s, p, LangString("x\"y", "en-GB"))
                triple(s, p, LangString("z", "ar", Direction.RTL))
            }
        }.sparql
        assertParsesQuery(query)
        assertTrue(query.contains("\"a\\\"b\"^^<urn:dt>"))
        assertTrue(query.contains("\"true\"^^<${XSD.boolean.value}>"))
        // Language tags are rendered as given (core preserves tag case).
        assertTrue(query.contains("\"x\\\"y\"@en-GB"))
        assertTrue(query.contains("\"z\"@ar--rtl"))
    }

    @Test
    fun `invalid language tags and unpaired surrogates are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            select("s") { where { triple(s, p, LangString("x", "en\" . } #")) } }
        }
        assertThrows(IllegalArgumentException::class.java) {
            select("s") { where { filter(`var`("s") eq "bad\uD800") } }
        }
    }

    @Test
    fun `special floating point values use XSD lexical forms`() {
        val query = select("s") {
            where {
                triple(s, p, o)
                filter(o gt Double.POSITIVE_INFINITY)
                filter(o lt Double.NEGATIVE_INFINITY)
                filter(o ne Double.NaN)
                triple(s, p, Literal(Float.POSITIVE_INFINITY))
            }
        }.sparql
        assertParsesQuery(query)
        assertTrue(query.contains("\"INF\"^^<${XSD.double.value}>"))
        assertTrue(query.contains("\"-INF\"^^<${XSD.double.value}>"))
        assertTrue(query.contains("\"NaN\"^^<${XSD.double.value}>"))
        assertTrue(query.contains("\"INF\"^^<${XSD.float.value}>"))
        assertFalse(query.contains("Infinity"))
    }

    // ---------------------------------------------------------------- names

    @Test
    fun `unvalidated names cannot rewrite the query`() {
        assertThrows(IllegalArgumentException::class.java) { select("x WHERE { ?a ?b ?c } #") { } }
        assertThrows(IllegalArgumentException::class.java) {
            select { expression(o.expr(), "x) WHERE { ?a ?b ?c } #") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            select("s") { prefix("ex", "http://example.org/> SELECT * WHERE { ?a ?b ?c } #") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            select("s") { prefix("ex: <urn:x> PREFIX y", "http://example.org/") }
        }
        assertThrows(IllegalArgumentException::class.java) { select("s") { version("1.2\" SELECT") } }
        assertThrows(IllegalArgumentException::class.java) {
            select("s") { where { bind(`var`("r"), function("STR(?x)) || (true", o.expr())) } }
        }
        assertThrows(IllegalArgumentException::class.java) {
            select("s") { where { triple(s, p, Var("o } #")) } }
        }
    }

    @Test
    fun `function names may be built-ins prefixed names or IRIs`() {
        val query = select("s") {
            prefix("ex", "http://example.org/fn#")
            where {
                triple(s, p, o)
                bind(`var`("a"), function("STRLEN", o.expr()))
                bind(`var`("b"), function("ex:custom", o.expr()))
                bind(`var`("c"), function("http://example.org/fn#other", o.expr()))
                filter(startsWith(o.expr(), "x"))
                filter(endsWith(o.expr(), "y"))
            }
        }.sparql
        assertParsesQuery(query)
        assertTrue(query.contains("<http://example.org/fn#other>(?o)"))
        assertTrue(query.contains("STRSTARTS(?o"))
        assertTrue(query.contains("STRENDS(?o"))
    }

    @Test
    fun `version renders as a SPARQL 1 2 string specifier`() {
        val query = select("s") { version("1.2"); where { triple(s, p, o) } }.sparql
        assertParsesQuery(query)
        assertTrue(query.startsWith("VERSION \"1.2\""))
    }

    // ---------------------------------------------------------------- query structure

    @Test
    fun `select and ask without where render an empty group`() {
        assertTrue(assertParsesQuery(select("s") { }.sparql).contains("WHERE {}"))
        assertTrue(assertParsesQuery(ask { }.sparql).contains("WHERE {}"))
    }

    @Test
    fun `dataset clauses follow the query form`() {
        val query = select("s") {
            from(Iri("urn:g1"))
            fromNamed(Iri("urn:g2"))
            where { triple(s, p, o) }
        }.sparql
        assertParsesQuery(query)
        assertTrue(query.indexOf("SELECT") < query.indexOf("FROM <urn:g1>"))
        assertParsesQuery(ask { from(Iri("urn:g1")); where { triple(s, p, o) } }.sparql)
        assertParsesQuery(construct { from(Iri("urn:g1")); template { triple(s, p, o) }; where { triple(s, p, o) } }.sparql)
        assertParsesQuery(describe(s) { from(Iri("urn:g1")); where { triple(s, p, o) } }.sparql)
    }

    @Test
    fun `HAVING renders bracketed constraints`() {
        val query = select {
            variable(s)
            expression(countAll(), "n")
            where { triple(s, p, o) }
            groupBy(s)
            having {
                filter(countAll() gt TermExpressionAst(1.toLiteral()))
                filter(count(o.expr(), distinct = true) lt TermExpressionAst(9.toLiteral()))
            }
        }.sparql
        assertParsesQuery(query)
        assertTrue(query.contains("HAVING (COUNT(*) > "))
        assertFalse(query.contains("HAVING FILTER"))
    }

    @Test
    fun `COUNT star GROUP_CONCAT separator and VALUES UNDEF render`() {
        val query = select {
            expression(countAll(distinct = true), "n")
            expression(groupConcat(o.expr(), "; \" ,"), "all")
            where {
                triple(s, p, o)
                values(listOf(s, o), listOf(listOf(a, null), listOf(null, Literal("x"))))
            }
        }.sparql
        assertParsesQuery(query)
        assertTrue(query.contains("COUNT(DISTINCT *)"))
        assertTrue(query.contains("GROUP_CONCAT(?o ; SEPARATOR=\"; \\\" ,\")"))
        assertTrue(query.contains("(<urn:a> UNDEF)"))
    }

    @Test
    fun `negative limit and offset are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { select("s") { limit(-1) } }
        assertThrows(IllegalArgumentException::class.java) { select("s") { offset(-1) } }
        assertThrows(IllegalArgumentException::class.java) { SelectQueryAst(emptyList(), limit = -5) }
    }

    @Test
    fun `sub-select renders without prologue and rejects outer-only clauses`() {
        val query = select("s") {
            prefix("ex", "http://example.org/")
            where {
                subSelect {
                    variable(s)
                    where { triple(s, p, o) }
                    limit(1)
                }
            }
        }.sparql
        assertParsesQuery(query)
        assertEquals(1, Regex("PREFIX").findAll(query).count())
        assertThrows(IllegalArgumentException::class.java) {
            select("s") { where { subSelect { prefix("ex", "http://example.org/"); variable(s) } } }
        }
    }

    // ---------------------------------------------------------------- UNION / MINUS / OPTIONAL

    @Test
    fun `union after a triple uses group operands and keeps semantics`() {
        val model = ModelFactory.createDefaultModel().apply {
            add(createResource("urn:x"), createProperty("urn:a"), "1")
            add(createResource("urn:y"), createProperty("urn:b"), "2")
        }
        val query = select("s") {
            where {
                union({ triple(s, a, o) }, { triple(s, b, o) })
            }
        }.sparql
        assertTrue(query.contains("} UNION {"), query)
        assertEquals(2, rowCount(model, query))

        val chained = select("s") {
            where {
                union({ triple(s, a, o) }, { triple(s, b, o) }, { triple(s, c, o) })
            }
        }.sparql
        assertEquals(2, rowCount(model, chained))
    }

    @Test
    fun `minus subtracts from the patterns before it and a leading minus excludes nothing`() {
        val model = ModelFactory.createDefaultModel().apply {
            add(createResource("urn:x"), createProperty("urn:a"), "1")
            add(createResource("urn:y"), createProperty("urn:a"), "2")
            add(createResource("urn:y"), createProperty("urn:b"), "3")
        }
        val trailing = select("s") {
            where {
                triple(s, a, o)
                minus { triple(s, b, `var`("z")) }
            }
        }.sparql
        assertTrue(trailing.contains("MINUS {"))
        assertEquals(1, rowCount(model, trailing))

        val leading = select("s") {
            where {
                minus { triple(s, b, `var`("z")) }
                triple(s, a, o)
            }
        }.sparql
        assertTrue(leading.contains("MINUS {"), leading)
        // MINUS applies to what precedes it in the group; leading, that is the empty solution, so
        // nothing is excluded and both urn:x and urn:y match.
        assertEquals(2, rowCount(model, leading))
    }

    @Test
    fun `optional graph and service wrap their bodies in groups`() {
        val query = select("s") {
            where {
                triple(s, p, o)
                optional { triple(o, p, `var`("x")) }
                graph(`var`("g")) { triple(s, p, o) }
                service(Iri("http://example.org/sparql")) { triple(s, p, o) }
                filter { `var`("x") ne "" }
            }
        }.sparql
        assertParsesQuery(query)
    }

    // ---------------------------------------------------------------- property paths

    @Test
    fun `property paths respect precedence`() {
        fun render(path: PropertyPathAst) = assertParsesQuery(select("s") { where { propertyPath(s, path, o) } }.sparql)

        assertTrue(render((path(a) alternative path(b)) sequence path(c)).contains("(<urn:a>|<urn:b>)/<urn:c>"))
        assertTrue(render((path(a) alternative path(b).inverse()).oneOrMore()).contains("(<urn:a>|^<urn:b>)+"))
        assertTrue(render((path(a) sequence path(b)).inverse()).contains("^(<urn:a>/<urn:b>)"))
        assertTrue(render(path(a).inverse().zeroOrMore()).contains("(^<urn:a>)*"))
        assertTrue(render(path(a) alternative (path(b) sequence path(c))).contains("<urn:a>|<urn:b>/<urn:c>"))
        assertTrue(render((path(a) alternative path(b).inverse()).negation()).contains("!(<urn:a>|^<urn:b>)"))
        assertTrue(render(path(a).negation()).contains("!<urn:a>"))
    }

    @Test
    fun `invalid negated sets and non-standard ranges are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            select("s") { where { propertyPath(s, (path(a) sequence path(b)).negation(), o) } }
        }
        assertThrows(IllegalArgumentException::class.java) {
            select("s") { where { propertyPath(s, path(a).between(2, 4), o) } }
        }
    }

    // ---------------------------------------------------------------- RDF 1.2

    @Test
    fun `RDF 1 2 patterns render valid SPARQL 1 2`() {
        val quoted = select("s") { where { quotedTriple(s, p, o) } }.sparql
        assertParsesQuery(quoted)
        assertTrue(quoted.contains("<< ?s <urn:p> ?o >> ."))

        val ast = SelectQueryAst(
            selectItems = emptyList(),
            where = GroupPatternAst(listOf(
                ReifierPatternAst(`var`("r"), TripleTermPatternAst(s, p, o)),
                TripleTermObjectPatternAst(`var`("x"), Iri("urn:says"), TripleTermPatternAst(s, p, o)),
            )),
        )
        val rendered = assertParsesQuery(SparqlRenderer.render(ast))
        assertTrue(rendered.contains("?r <${RDF.reifies.value}> <<( ?s <urn:p> ?o )>> ."))
        assertFalse(rendered.contains("rdf:reifies"))

        assertThrows(IllegalArgumentException::class.java) {
            SparqlRenderer.render(SelectQueryAst(emptyList(), where = GroupPatternAst(listOf(TripleTermPatternAst(s, p, o)))))
        }
    }

    // ---------------------------------------------------------------- updates

    @Test
    fun `multiple update operations are separated by semicolons`() {
        val update = update {
            prefix("ex", "http://example.org/")
            insertData { triple(a, p, Literal("x\"y")) }
            deleteData { triple(a, p, Literal("old")) }
            clear(Iri("urn:g"), silent = true)
        }.sparql
        assertParsesUpdate(update)
        assertEquals(2, Regex(" ;\n").findAll(update).count(), update)
    }

    @Test
    fun `modify renders WITH DELETE INSERT USING WHERE in grammar order`() {
        val update = update {
            modify {
                using(Iri("urn:u"))
                usingNamed(Iri("urn:n"))
                with(Iri("urn:w"))
                delete { triple(s, p, o) }
                insert { triple(s, p, Literal("new")) }
                where { triple(s, p, o) }
            }
        }.sparql
        assertParsesUpdate(update)
        val order = listOf("WITH <urn:w>", "DELETE {", "INSERT {", "USING <urn:u>", "USING NAMED <urn:n>", "WHERE {")
        assertEquals(order, order.sortedBy { update.indexOf(it) }, update)
        assertTrue(order.all { update.contains(it) })
    }

    @Test
    fun `modify without where renders an empty WHERE group`() {
        val update = update { modify { insert { triple(a, p, b) } } }.sparql
        assertParsesUpdate(update)
        assertTrue(update.contains("WHERE {}"))
    }

    @Test
    fun `load into uses GRAPH keyword`() {
        val update = update { load(Iri("http://example.org/data.ttl"), Iri("urn:g"), silent = true) }.sparql
        assertParsesUpdate(update)
        assertTrue(update.contains("LOAD SILENT <http://example.org/data.ttl> INTO GRAPH <urn:g>"))
    }

    @Test
    fun `USING and WITH are rejected on operations that do not allow them`() {
        assertThrows(IllegalArgumentException::class.java) { InsertDataOperationAst(emptyList(), with = Iri("urn:g")) }
        assertThrows(IllegalArgumentException::class.java) { DeleteDataOperationAst(emptyList(), using = listOf(Iri("urn:g"))) }
        assertThrows(IllegalArgumentException::class.java) { DeleteWhereOperationAst(GroupPatternAst(emptyList()), usingNamed = listOf(Iri("urn:g"))) }
        assertThrows(IllegalArgumentException::class.java) { ClearOperationAst(with = Iri("urn:g")) }
    }

    @Test
    fun `named graph quads render in data blocks and templates`() {
        val update = update {
            insertData {
                triple(a, p, b)
                graph(Iri("urn:g")) { triple(a, p, Literal("in g")) }
            }
            deleteData {
                graph(Iri("urn:g")) { triple(a, p, Literal("in g")) }
            }
            modify {
                delete { graph(`var`("g")) { triple(s, p, o) } }
                insert {
                    triple(s, p, o)
                    graph(Iri("urn:h")) { triple(s, p, o) }
                }
                where { graph(`var`("g")) { triple(s, p, o) } }
            }
            deleteWhere {
                where {
                    triple(s, p, o)
                    graph(Iri("urn:g")) { triple(s, p, o) }
                }
            }
        }.sparql
        assertParsesUpdate(update)
        assertTrue(update.contains("DELETE {\n  GRAPH ?g {"), update)
        assertTrue(update.contains("GRAPH <urn:h> {"), update)

        // Execute against Jena to make sure the named-graph data really lands in the graph.
        val dataset = org.apache.jena.query.DatasetFactory.create()
        org.apache.jena.update.UpdateAction.parseExecute(
            update { insertData { graph(Iri("urn:g")) { triple(a, p, b) } } }.sparql, dataset
        )
        assertEquals(1L, dataset.getNamedModel("urn:g").size())
        assertEquals(0L, dataset.defaultModel.size())
    }

    @Test
    fun `templates reject patterns they cannot represent instead of dropping them`() {
        assertThrows(IllegalArgumentException::class.java) {
            construct { template { graph(Iri("urn:g")) { triple(s, p, o) } } }
        }
        assertThrows(IllegalArgumentException::class.java) {
            update { modify { delete { optional { triple(s, p, o) } }; where { triple(s, p, o) } } }
        }
        assertThrows(IllegalArgumentException::class.java) {
            update { modify { delete { triple(BlankNode("b"), p, o) }; where { triple(s, p, o) } } }
        }
        assertThrows(IllegalArgumentException::class.java) { update { insertData { triple(s, p, o) } } }
        assertThrows(IllegalArgumentException::class.java) { update { deleteData { triple(BlankNode("b"), p, b) } } }
        assertThrows(IllegalArgumentException::class.java) {
            update { deleteWhere { where { filter(bound(s)) } } }
        }
    }

    // ---------------------------------------------------------------- DSL / registry

    @Test
    fun `documented filter lambda form compiles and renders`() {
        val query = select("name", "age") {
            where {
                triple(`var`("person"), Iri("http://xmlns.com/foaf/0.1/name"), `var`("name"))
                triple(`var`("person"), Iri("http://xmlns.com/foaf/0.1/age"), `var`("age"))
                filter { `var`("age") gt 18 }
                filter { `var`("age") gt 18 and (`var`("age") lt 65) }
            }
        }.sparql
        assertParsesQuery(query)
        assertTrue(query.contains("FILTER(?age > \"18\"^^<${XSD.integer.value}>)"))
    }

    @Test
    fun `extension registry exposes built-ins without prior initialisation and is thread safe`() {
        assertTrue(SparqlExtensionFunctionRegistry.getBuiltInFunctions().any { it.name == "TRIPLE" })
        val threads = (0 until 8).map { t ->
            Thread {
                repeat(200) { i ->
                    SparqlExtensionFunctionRegistry.register(
                        SparqlExtensionFunction(iri = "urn:fn:$t:$i", name = "fn$t$i", description = "d", isBuiltIn = false)
                    )
                    SparqlExtensionFunctionRegistry.getAllFunctions()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue((0 until 8).all { t -> (0 until 200).all { i -> SparqlExtensionFunctionRegistry.isRegistered("urn:fn:$t:$i") } })
    }
}
