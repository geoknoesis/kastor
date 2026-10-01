package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.SPARQL_SD
import org.apache.jena.query.DatasetFactory
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.query.QueryFactory
import org.apache.jena.query.Syntax
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.update.UpdateAction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Regression tests for the second SPARQL renderer/DSL audit. Every rendered text is parsed with ARQ. */
class SparqlReauditRendererTest {

    private val s = `var`("s")
    private val o = `var`("o")
    private val z = `var`("z")
    private val a = Iri("urn:a")
    private val b = Iri("urn:b")
    private val p = Iri("urn:p")

    private fun rows(model: Model, sparql: String, variable: String): List<String> =
        QueryExecutionFactory.create(assertParsesQuery(sparql), model).use { exec ->
            exec.execSelect().asSequence().map { it.get(variable)?.toString() ?: "UNDEF" }.toList().sorted()
        }

    // ---------------------------------------------------------------- MINUS with a left operand

    @Test
    fun `MINUS with a left operand only subtracts from that operand`() {
        // T1 = ?s urn:a ?o ; X = ?s urn:b ?z shares ?s only with T1 ; T0 = ?t urn:c ?u shares nothing.
        val model = ModelFactory.createDefaultModel().apply {
            add(createResource("urn:x"), createProperty("urn:a"), "1")
            add(createResource("urn:y"), createProperty("urn:a"), "2")
            add(createResource("urn:y"), createProperty("urn:b"), "3")
            add(createResource("urn:t"), createProperty("urn:c"), "4")
        }
        val t0 = TriplePatternAst(`var`("t"), Iri("urn:c"), `var`("u"))
        val minus = MinusPatternAst(TriplePatternAst(s, a, o), TriplePatternAst(s, b, z))
        val query = SparqlRenderer.render(
            SelectQueryAst(listOf(VariableSelectItemAst(s), VariableSelectItemAst(`var`("t"))), where = GroupPatternAst(listOf(t0, minus)))
        )
        // Expected: T0 JOIN (T1 MINUS X) = {x} x {t}. Rendering MINUS inline would still give x here,
        // so also check the structure: the left operand and MINUS share their own group.
        assertEquals(listOf("urn:x"), rows(model, query, "s"), query)

        // A triple that precedes the MINUS in the group and shares ?s must not be subtracted from.
        val preceding = TriplePatternAst(s, Iri("urn:a"), `var`("q"))
        val scoped = MinusPatternAst(TriplePatternAst(`var`("t"), Iri("urn:c"), `var`("u")), TriplePatternAst(s, b, z))
        val q2 = SparqlRenderer.render(SelectQueryAst(listOf(VariableSelectItemAst(s)), where = GroupPatternAst(listOf(preceding, scoped))))
        // ?s of `preceding` must survive for urn:y: the MINUS only applies to T0, which does not bind ?s.
        assertEquals(listOf("urn:x", "urn:y"), rows(model, q2, "s"), q2)
    }

    // ---------------------------------------------------------------- UNION API

    @Test
    fun `explicit union branches keep preceding filters applied`() {
        val model = ModelFactory.createDefaultModel().apply {
            add(createResource("urn:x"), createProperty("urn:a"), "1")
            add(createResource("urn:y"), createProperty("urn:a"), "2")
            add(createResource("urn:x"), createProperty("urn:b"), "3")
        }
        val query = select("s") {
            where {
                triple(s, a, o)
                filter(s.expr() eq Iri("urn:x").expr())
                union({ triple(s, b, z) }, { triple(s, Iri("urn:c"), z) })
            }
        }.sparql
        assertEquals(listOf("urn:x"), rows(model, query, "s"), query)

        val built = select("s") {
            where {
                triple(s, a, o)
                filter(s.expr() eq Iri("urn:x").expr())
                unionOf {
                    branch { triple(s, b, z) }
                    branch { triple(s, Iri("urn:c"), z) }
                    branch { triple(s, Iri("urn:d"), z) }
                }
            }
        }.sparql
        assertEquals(listOf("urn:x"), rows(model, built, "s"), built)
        assertEquals(2, Regex("UNION").findAll(built).count(), built)

        assertThrows(IllegalArgumentException::class.java) { select("s") { where { unionOf { branch { triple(s, a, o) } } } } }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `deprecated previous-element union still captures only the preceding element`() {
        val query = select("s") {
            where {
                triple(s, a, o)
                filter(s.expr() eq Iri("urn:x").expr())
                union { triple(s, b, z) }
            }
        }.sparql
        assertParsesQuery(query)
        assertTrue(Regex("FILTER[^\\n]*\\n\\s*\\}\\s*UNION").containsMatchIn(query), query)
    }

    // ---------------------------------------------------------------- SELECT projection

    @Test
    fun `invalid projections are rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            SelectQueryAst(emptyList(), groupBy = listOf(s))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelectQueryAst(listOf(WildcardSelectItemAst), groupBy = listOf(s))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelectQueryAst(listOf(WildcardSelectItemAst, VariableSelectItemAst(s)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            select { where { triple(s, p, o) }; groupBy(s) }
        }
        val ok = SparqlRenderer.render(
            SelectQueryAst(
                listOf(VariableSelectItemAst(s), AliasedSelectItemAst(countAll(), "n")),
                where = TriplePatternAst(s, p, o),
                groupBy = listOf(s),
            )
        )
        assertParsesQuery(ok)
        assertParsesQuery(SparqlRenderer.render(SelectQueryAst(listOf(WildcardSelectItemAst), where = TriplePatternAst(s, p, o))))
    }

    // ---------------------------------------------------------------- CLEAR / DROP / COPY / MOVE / ADD

    @Test
    fun `graph management forms cover NAMED ALL and DEFAULT`() {
        val g = Iri("urn:g")
        val text = update {
            clear(GraphScope.NAMED)
            clear(GraphScope.ALL, silent = true)
            clear()
            drop(GraphScope.NAMED, silent = true)
            drop(GraphScope.ALL)
            copy(null, g)
            move(g, null, silent = true)
            add(null, null)
            copy(g, Iri("urn:h"))
        }.sparql
        assertParsesUpdate(text)
        listOf(
            "CLEAR NAMED", "CLEAR SILENT ALL", "CLEAR DEFAULT", "DROP SILENT NAMED", "DROP ALL",
            "COPY DEFAULT TO <urn:g>", "MOVE SILENT <urn:g> TO DEFAULT", "ADD DEFAULT TO DEFAULT", "COPY <urn:g> TO <urn:h>",
        ).forEach { assertTrue(text.contains(it), "missing $it in\n$text") }
        assertThrows(IllegalArgumentException::class.java) { ClearOperationAst(graph = g, scope = GraphScope.ALL) }
        assertThrows(IllegalArgumentException::class.java) { DropOperationAst(graph = g, scope = GraphScope.NAMED) }

        // Execute: COPY DEFAULT TO <g> then CLEAR NAMED empties the named graph but keeps the default graph.
        val dataset = DatasetFactory.create()
        UpdateAction.parseExecute(update { insertData { triple(a, p, b) } }.sparql, dataset)
        UpdateAction.parseExecute(update { copy(null, g) }.sparql, dataset)
        assertEquals(1L, dataset.getNamedModel("urn:g").size())
        UpdateAction.parseExecute(update { clear(GraphScope.NAMED) }.sparql, dataset)
        assertEquals(0L, dataset.getNamedModel("urn:g").size())
        assertEquals(1L, dataset.defaultModel.size())
    }

    // ---------------------------------------------------------------- blank node validation

    @Test
    fun `blank node labels reused across basic graph patterns are rejected like ARQ does`() {
        val bn = BlankNode("a")
        fun query(vararg patterns: GraphPatternAst) =
            SelectQueryAst(listOf(WildcardSelectItemAst), where = GroupPatternAst(patterns.toList()))
        val illegal = listOf(
            query(TriplePatternAst(bn, p, o), OptionalPatternAst(TriplePatternAst(bn, b, z))),
            query(UnionPatternAst(TriplePatternAst(bn, p, o), TriplePatternAst(bn, b, z))),
            query(TriplePatternAst(bn, p, o), GroupPatternAst(listOf(TriplePatternAst(bn, b, z)))),
            query(TriplePatternAst(bn, p, o), GraphPatternAstImpl(`var`("g"), TriplePatternAst(bn, b, z))),
            query(TriplePatternAst(bn, p, o), MinusPatternAst(GroupPatternAst(emptyList()), TriplePatternAst(bn, b, z))),
            query(TriplePatternAst(bn, p, o), SubSelectPatternAst(SelectQueryAst(listOf(WildcardSelectItemAst), where = TriplePatternAst(bn, b, z)))),
            query(OptionalPatternAst(TriplePatternAst(bn, b, z)), OptionalPatternAst(TriplePatternAst(bn, p, z))),
        )
        for (ast in illegal) {
            val e = assertThrows(IllegalArgumentException::class.java, { SparqlRenderer.render(ast) }, ast.toString())
            assertTrue(e.message!!.contains("_:a"), e.message)
        }
        // Same basic graph pattern (including across FILTER / BIND / VALUES, as ARQ allows) is fine.
        val legal = listOf(
            query(TriplePatternAst(bn, p, o), TriplePatternAst(bn, b, z)),
            query(TriplePatternAst(bn, p, o), FilterPatternAst(bound(o)), TriplePatternAst(bn, b, z)),
            query(TriplePatternAst(bn, p, o), BindPatternAst(`var`("k"), TermExpressionAst(Literal("1"))), TriplePatternAst(bn, b, z)),
            query(OptionalPatternAst(TriplePatternAst(bn, b, z))),
        )
        legal.forEach { assertParsesQuery(SparqlRenderer.render(it)) }

        // Update requests: one label in two operations is rejected; within one operation it is fine.
        assertThrows(IllegalArgumentException::class.java) {
            update { insertData { triple(bn, p, Literal("1")) }; insertData { triple(bn, p, Literal("2")) } }
        }
        assertParsesUpdate(update { insertData { triple(bn, p, Literal("1")); triple(a, p, bn) } }.sparql)
    }

    @Test
    fun `ARQ agrees that the rejected blank node reuse is illegal`() {
        val texts = listOf(
            "SELECT * { _:a <urn:p> ?o OPTIONAL { _:a <urn:b> ?z } }",
            "SELECT * { { _:a <urn:p> ?o } UNION { _:a <urn:b> ?z } }",
            "SELECT * { _:a <urn:p> ?o { SELECT * { _:a <urn:b> ?z } } }",
            "INSERT DATA { _:a <urn:p> 1 } ; INSERT DATA { _:a <urn:p> 2 }",
        )
        for (text in texts) {
            assertThrows(Exception::class.java, {
                if (text.startsWith("INSERT")) org.apache.jena.update.UpdateFactory.create(text, Syntax.syntaxSPARQL_12)
                else QueryFactory.create(text, Syntax.syntaxSPARQL_12)
            }, text)
        }
    }

    @Test
    fun `blank nodes in expressions and in VALUES triple terms are rejected`() {
        val bn = BlankNode("a")
        assertThrows(IllegalArgumentException::class.java) {
            select("s") { where { triple(s, p, o); filter(o.expr() eq bn.expr()) } }
        }
        assertThrows(IllegalArgumentException::class.java) {
            select("s") { where { triple(s, p, o); bind(`var`("k"), bn) } }
        }
        assertThrows(IllegalArgumentException::class.java) {
            select("s") { where { values(`var`("t"), TripleTerm(RdfTriple(bn, p, Literal("1")))) } }
        }
        assertParsesQuery(select("t") { where { values(`var`("t"), TripleTerm(RdfTriple(a, p, Literal("1")))) } }.sparql)
    }

    // ---------------------------------------------------------------- string escaping and \u pre-processing

    /** Naive SPARQL 1.1 §19.2 pre-pass: decodes every `\uXXXX`/`\UXXXXXXXX`, ignoring preceding backslashes. */
    private fun naivePrePass(text: String): String =
        Regex("\\\\U([0-9A-Fa-f]{8})|\\\\u([0-9A-Fa-f]{4})").replace(text) { m ->
            String(Character.toChars((m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt(16)))
        }

    /** Java-style pre-pass: a backslash starts an escape only after an even number of backslashes. */
    private fun javaPrePass(text: String): String = buildString {
        var i = 0
        var run = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && run % 2 == 0 && i + 5 < text.length && text[i + 1] == 'u' &&
                text.substring(i + 2, i + 6).all { h -> h.lowercaseChar() in "0123456789abcdef" }
            ) {
                append(text.substring(i + 2, i + 6).toInt(16).toChar())
                i += 6
                run = 0
                continue
            }
            run = if (c == '\\') run + 1 else 0
            append(c)
            i++
        }
    }

    @Test
    fun `literal text with backslash-u sequences survives every codepoint escape reading`() {
        val values = listOf(
            "\\u0022 || true || \\u0022",
            "path C:\\users\\Uwe",
            "\\\\u0041 and \\U0001F600",
            "ends with backslash-u \\u",
            "plain u and U",
        )
        for (value in values) {
            val model = ModelFactory.createDefaultModel()
            model.add(model.createResource("urn:x"), model.createProperty("urn:name"), value)
            model.add(model.createResource("urn:y"), model.createProperty("urn:name"), "other")
            val query = select("s") {
                where {
                    triple(s, Iri("urn:name"), `var`("name"))
                    filter(`var`("name") eq value)
                }
            }.sparql
            for ((reading, text) in listOf("none" to query, "naive" to naivePrePass(query), "java" to javaPrePass(query))) {
                val found = QueryExecutionFactory.create(QueryFactory.create(text, Syntax.syntaxSPARQL_12), model).use { exec ->
                    exec.execSelect().asSequence().map { it.getResource("s").uri }.toList()
                }
                assertEquals(listOf("urn:x"), found, "reading=$reading value=<$value>\n$text")
            }
        }
    }

    // ---------------------------------------------------------------- service description

    @Test
    fun `service description keeps invented terms out of the W3C namespaces`() {
        val custom = SparqlExtensionFunction(iri = "http://example.org/fn", name = "fn", description = "d", isBuiltIn = false)
        val capabilities = ProviderCapabilities(
            sparqlVersion = "1.2",
            supportsRdfStar = true,
            supportsFederation = true,
            extensionFunctions = Sparql12BuiltInFunctions.functions + custom,
            supportedLanguages = listOf("SPARQL11Query", "sparql12"),
        )
        val graph = SparqlServiceDescriptionGenerator("https://example.com/sparql", capabilities).generateServiceDescription()
        val iris = graph.getTriples().flatMap { listOf(it.subject, it.predicate, it.obj) }.filterIsInstance<Iri>().map { it.value }
        assertTrue(iris.none { it.startsWith("http://www.w3.org/ns/sparql#") }, iris.toString())
        val sdTerms = setOf(
            "Service", "Dataset", "DefaultGraph", "NamedGraph", "endpoint", "updateEndpoint", "supportedLanguage", "resultFormat",
            "inputFormat", "defaultDataset", "defaultGraph", "namedGraph", "extensionFunction", "functionName", "description",
            "isAggregate", "returnType", "feature", "BasicFederatedQuery", "SPARQL10Query", "SPARQL11Query", "SPARQL11Update",
        )
        iris.filter { it.startsWith(SPARQL_SD.namespace) }.forEach { assertTrue(it.removePrefix(SPARQL_SD.namespace) in sdTerms, it) }

        val service = Iri("https://example.com/sparql")
        val extensions = graph.getTriples().filter { it.subject == service && it.predicate == SPARQL_SD.extensionFunction }.map { it.obj }
        assertEquals(listOf<RdfTerm>(Iri("http://example.org/fn")), extensions)
        assertTrue(graph.hasTriple(RdfTriple(service, Iri("${SPARQL_SD.namespace}feature"), Iri("${SPARQL_SD.namespace}BasicFederatedQuery"))))
        assertTrue(graph.hasTriple(RdfTriple(service, KastorSparqlVocabulary.supportsRdfStar, boolean(true))))
        assertTrue(graph.hasTriple(RdfTriple(service, SPARQL_SD.supportedLanguageProp, Iri("${SPARQL_SD.namespace}SPARQL11Query"))))
    }

    @Test
    fun `service description skolem IRIs are valid for urn service URIs`() {
        val generator = SparqlServiceDescriptionGenerator("urn:example:sparql", ProviderCapabilities(sparqlVersion = "1.1"))
        val sparql = generator.generateAsSparqlResult()
        assertFalse(sparql.contains("null"), sparql)
        assertTrue(sparql.contains("<urn:kastor:genid:"), sparql)
        val rows = QueryExecutionFactory.create(assertParsesQuery(sparql), ModelFactory.createDefaultModel()).use { exec ->
            exec.execSelect().asSequence().map { it.get("subject").toString() }.toList()
        }
        assertTrue(rows.any { it.startsWith("urn:kastor:genid:") }, rows.toString())

        val http = SparqlServiceDescriptionGenerator("https://example.com/sparql", ProviderCapabilities()).generateAsSparqlResult()
        assertTrue(http.contains("<https://example.com/.well-known/genid/"), http)
        assertParsesQuery(http)
    }

    // ---------------------------------------------------------------- ORDER BY bracketing

    @Test
    fun `ORDER BY conditions are bracketed by their structure and not by their first character`() {
        val c = `var`("c")
        fun eq(left: ExpressionAst, right: ExpressionAst) = ComparisonExpressionAst(left, ComparisonOperator.EQ, right)
        fun render(expression: ExpressionAst, direction: OrderDirection) = SparqlRenderer.render(
            SelectQueryAst(
                listOf(VariableSelectItemAst(s)),
                where = GroupPatternAst(listOf(TriplePatternAst(s, p, o), TriplePatternAst(s, b, c))),
                orderBy = listOf(OrderClauseAst(expression, direction)),
            )
        )
        // A comparison whose left operand is itself bracketed starts with "(" but is not a bracketed expression.
        val nested = eq(eq(s.expr(), o.expr()), c.expr())
        val ascending = render(nested, OrderDirection.ASC)
        assertTrue(ascending.contains("ORDER BY ASC((?s = ?o) = ?c)"), ascending)
        assertParsesQuery(ascending)

        val sum = ArithmeticExpressionAst(o.expr(), ArithmeticOperator.ADD, c.expr())
        val shapes = listOf(
            nested,
            eq(s.expr(), eq(o.expr(), c.expr())),
            eq(s.expr(), o.expr()),
            eq(sum, c.expr()),
            eq(AndExpressionAst(eq(s.expr(), o.expr()), eq(o.expr(), c.expr())), c.expr()),
            AndExpressionAst(eq(s.expr(), o.expr()), eq(o.expr(), c.expr())),
            OrExpressionAst(eq(s.expr(), o.expr()), eq(o.expr(), c.expr())),
            NotExpressionAst(eq(s.expr(), o.expr())),
            sum,
            ArithmeticExpressionAst(sum, ArithmeticOperator.MULTIPLY, sum),
            FunctionCallAst("STR", listOf(s.expr())),
            ConditionalExpressionAst(eq(s.expr(), o.expr()), o.expr(), c.expr()),
            TermExpressionAst(Literal("x")),
            TermExpressionAst(a),
            s.expr(),
        )
        for (shape in shapes) {
            for (direction in OrderDirection.values()) {
                assertParsesQuery(render(shape, direction))
            }
        }
        // Forms the grammar accepts bare stay bare.
        assertTrue(render(s.expr(), OrderDirection.ASC).contains("ORDER BY ?s"))
        assertTrue(render(FunctionCallAst("STR", listOf(s.expr())), OrderDirection.ASC).contains("ORDER BY STR(?s)"))
        assertTrue(render(sum, OrderDirection.ASC).contains("ORDER BY (?o + ?c)"))
    }
}
