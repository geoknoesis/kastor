package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import org.apache.jena.query.DatasetFactory
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.update.UpdateAction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Execute rendered DSL queries with an independent engine and assert observable results. */
class SparqlExecutionContractTest {
    private val value = `var`("value")
    private fun text(value: String) = string(value).expr()
    private fun evaluate(expression: ExpressionAst): RDFNode {
        val query = select { expression(expression, "result") }.sparql
        val model = ModelFactory.createDefaultModel()
        try {
            return QueryExecutionFactory.create(assertParsesQuery(query), model).use { execution ->
                val rows = execution.execSelect()
                assertTrue(rows.hasNext(), query)
                val result = rows.next().get("result")
                assertNotNull(result, "Expression failed to bind: $query")
                assertFalse(rows.hasNext(), query)
                result
            }
        } finally { model.close() }
    }

    @TestFactory
    fun `string functions preserve their arguments and return the expected lexical value`() = listOf(
        Triple("concat", concat(text("a\""), text("b\\")), "a\"b\\"),
        Triple("uppercase", ucase(text("aBc")), "ABC"),
        Triple("lowercase", lcase(text("AbC")), "abc"),
        Triple("substring with length", substr(text("abcdef"), 2, 3), "bcd"),
        Triple("substring to end", substr(text("abcdef"), 4), "def"),
        Triple("before separator", strBefore(text("left::right"), "::"), "left"),
        Triple("after separator", strAfter(text("left::right"), "::"), "right"),
        Triple("missing separator", strAfter(text("abc"), "/"), ""),
        Triple("replace all matches", replace(text("aba"), "a", "x"), "xbx"),
        Triple("URI encoding", encodeForUri(text("a b/c")), "a%20b%2Fc"),
    ).map { (name, expression, expected) ->
        dynamicTest(name) { assertEquals(expected, evaluate(expression).asLiteral().string) }
    }

    @TestFactory
    fun `aggregate helpers preserve duplicate and distinct semantics`() = listOf(
        Triple("count", count(value.expr()), 4.0),
        Triple("distinct count", count(value.expr(), distinct = true), 3.0),
        Triple("sum", sum(value.expr()), 10.0),
        Triple("distinct sum", sum(value.expr(), distinct = true), 8.0),
        Triple("average", avg(value.expr()), 2.5),
        Triple("distinct average", avg(value.expr(), distinct = true), 8.0 / 3.0),
        Triple("minimum", min(value.expr()), 1.0),
        Triple("maximum", max(value.expr()), 5.0),
    ).map { (name, expression, expected) ->
        dynamicTest(name) {
            val query = select {
                expression(expression, "result")
                where { values(value, Literal(1), Literal(2), Literal(2), Literal(5)) }
            }.sparql
            val model = ModelFactory.createDefaultModel()
            try {
                QueryExecutionFactory.create(assertParsesQuery(query), model).use { execution ->
                    val rows = execution.execSelect()
                    assertEquals(expected, rows.next().getLiteral("result").double, 0.000001)
                    assertFalse(rows.hasNext())
                }
            } finally { model.close() }
        }
    }

    @Test
    fun `group concat and sample return members without assuming aggregate ordering`() {
        val query = select {
            expression(groupConcat(value.expr(), distinct = true), "joined")
            expression(sample(value.expr()), "sampled")
            where { values(value, string("a"), string("b"), string("a")) }
        }.sparql
        val model = ModelFactory.createDefaultModel()
        try {
            QueryExecutionFactory.create(assertParsesQuery(query), model).use { execution ->
                val row = execution.execSelect().next()
                assertEquals(listOf("a", "b"), row.getLiteral("joined").string.split(" ").sorted())
                assertTrue(row.getLiteral("sampled").string in setOf("a", "b"))
            }
        } finally { model.close() }
    }

    @Test
    fun `distinct is applied before ordered offset and limit`() {
        val query = select(value) {
            distinct()
            where { values(value, Literal(3), Literal(1), Literal(2), Literal(2), Literal(4)) }
            orderBy(value)
            offset(1)
            limit(2)
        }.sparql
        val model = ModelFactory.createDefaultModel()
        try {
            QueryExecutionFactory.create(assertParsesQuery(query), model).use { execution ->
                val rows = execution.execSelect()
                val values = mutableListOf<Int>()
                while (rows.hasNext()) values += rows.next().getLiteral("value").int
                assertEquals(listOf(2, 3), values)
            }
        } finally { model.close() }
    }

    @TestFactory
    fun `copy move and add preserve the correct source and destination graphs`() =
        listOf("copy", "move", "add").flatMap { operation ->
            listOf("named to named", "default to named", "named to default").map { direction ->
                dynamicTest("$operation $direction") {
                    val dataset = DatasetFactory.createTxnMem()
                    try {
                        val source = if (direction == "default to named") null else Iri("urn:source")
                        val destination = if (direction == "named to default") null else Iri("urn:destination")
                        fun graph(name: Iri?) = if (name == null) dataset.defaultModel else dataset.getNamedModel(name.value)
                        fun populate(name: Iri?, subject: String) {
                            val graph = graph(name)
                            graph.add(graph.createResource(subject), graph.createProperty("urn:p"), "value")
                        }
                        populate(source, "urn:source-value")
                        populate(destination, "urn:destination-value")
                        populate(Iri("urn:unrelated"), "urn:untouched")
                        val query = update {
                            when (operation) {
                                "copy" -> copy(source, destination)
                                "move" -> move(source, destination)
                                else -> add(source, destination)
                            }
                        }.sparql
                        UpdateAction.parseExecute(assertParsesUpdate(query), dataset)
                        val target = graph(destination)
                        assertTrue(target.containsResource(target.createResource("urn:source-value")))
                        assertEquals(operation == "add", target.containsResource(target.createResource("urn:destination-value")))
                        assertEquals(if (operation == "move") 0L else 1L, graph(source).size())
                        assertEquals(1L, graph(Iri("urn:unrelated")).size())
                    } finally { dataset.close() }
                }
            }
        }

    @Test
    fun `create insert delete and drop execute in request order without touching other graphs`() {
        val dataset = DatasetFactory.createTxnMem()
        try {
            val target = Iri("urn:target")
            val other = Iri("urn:other")
            val s = Iri("urn:s")
            val p = Iri("urn:p")
            val request = update {
                create(target, silent = true)
                insertData {
                    graph(target) { triple(s, p, string("old")); triple(s, p, string("keep")) }
                    graph(other) { triple(s, p, string("untouched")) }
                }
                deleteData { graph(target) { triple(s, p, string("old")) } }
            }.sparql
            UpdateAction.parseExecute(assertParsesUpdate(request), dataset)
            val result = dataset.getNamedModel(target.value)
            assertEquals(1L, result.size())
            assertTrue(result.contains(result.createResource(s.value), result.createProperty(p.value), "keep"))
            UpdateAction.parseExecute(assertParsesUpdate(update { drop(target) }.sparql), dataset)
            assertTrue(dataset.getNamedModel(target.value).isEmpty)
            assertEquals(1L, dataset.getNamedModel(other.value).size())
        } finally { dataset.close() }
    }
}
