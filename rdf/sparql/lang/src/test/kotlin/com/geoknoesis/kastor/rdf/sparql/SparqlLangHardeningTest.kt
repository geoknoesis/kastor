package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.BindingSet
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.MapBindingSet
import com.geoknoesis.kastor.rdf.ProviderCapabilities
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.SparqlQueryResult
import com.geoknoesis.kastor.rdf.boolean
import com.geoknoesis.kastor.rdf.sparqlQueryResult
import com.geoknoesis.kastor.rdf.string
import com.geoknoesis.kastor.rdf.toLiteral
import com.geoknoesis.kastor.rdf.`var`
import com.geoknoesis.kastor.rdf.vocab.SPARQL_SD
import com.geoknoesis.kastor.rdf.vocab.XSD
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Eighth audit of the SPARQL language module: bounded path repetition, service descriptions that
 * invent nothing, builders that keep their lists to themselves, the empty prefix, typed access to
 * bindings and flows over results.
 */
class SparqlLangHardeningTest {

    private val s = `var`("s")
    private val o = `var`("o")
    private val a = Iri("urn:a")
    private val b = Iri("urn:b")
    private val p = Iri("urn:p")

    // ------------------------------------------------------------------ bounded path repetition

    private fun rendered(path: PropertyPathAst): String =
        assertParsesQuery(select("o") { where { propertyPath(Iri("urn:n0"), path, o) } }.sparql)

    /** `urn:n0 -p-> urn:n1 -p-> ... -p-> urn:n5`. */
    private fun chain(): Model = ModelFactory.createDefaultModel().also { model ->
        for (i in 0 until 5) model.add(model.createResource("urn:n$i"), model.createProperty(p.value), model.createResource("urn:n${i + 1}"))
    }

    /** The numbers of the nodes [path] reaches from `urn:n0`. */
    private fun reached(path: PropertyPathAst): Set<Int> =
        QueryExecutionFactory.create(rendered(path), chain()).use { execution ->
            execution.execSelect().asSequence().map { it.getResource("o").uri.removePrefix("urn:n").toInt() }.toSet()
        }

    @Test
    fun `bounded path repetition is written as the sequence it stands for`() {
        // SPARQL has no `{n,m}` quantifier, so the repetition is spelled out.
        assertTrue(rendered(path(p).exactly(3)).contains(" <urn:p>/<urn:p>/<urn:p> "))
        assertTrue(rendered(path(p).between(1, 3)).contains(" <urn:p>/<urn:p>?/<urn:p>? "))
        assertTrue(rendered(path(p).atMost(2)).contains(" <urn:p>?/<urn:p>? "))
        assertTrue(rendered(path(p).atLeast(2)).contains(" <urn:p>/<urn:p>+ "))
        assertTrue(rendered(path(p).atLeast(1)).contains(" <urn:p>+ "))
        assertTrue(rendered(path(p).atLeast(0)).contains(" <urn:p>* "))
        assertTrue(rendered(path(p).exactly(1)).contains(" <urn:p> "))
        // Whatever is repeated keeps its own brackets, and the repetition gets its own where it is an operand.
        assertTrue(rendered((path(a) alternative path(b)).exactly(2)).contains(" (<urn:a>|<urn:b>)/(<urn:a>|<urn:b>) "))
        assertTrue(rendered((path(a) sequence path(b)).atMost(2)).contains(" (<urn:a>/<urn:b>)?/(<urn:a>/<urn:b>)? "))
        assertTrue(rendered(path(p).inverse().between(1, 2)).contains(" ^<urn:p>/(^<urn:p>)? "))
        assertTrue(rendered(path(p).exactly(2).zeroOrMore()).contains(" (<urn:p>/<urn:p>)* "))
        assertTrue(rendered(path(p).exactly(2).inverse()).contains(" ^(<urn:p>/<urn:p>) "))
        assertTrue(rendered(path(a) alternative path(p).exactly(2)).contains(" <urn:a>|<urn:p>/<urn:p> "))
        assertTrue(rendered(path(p).between(2, 3).exactly(2)).contains(" <urn:p>/<urn:p>/<urn:p>?/<urn:p>/<urn:p>/<urn:p>? "))

        // The expansion means what the quantifier says.
        assertEquals(setOf(3), reached(path(p).exactly(3)))
        assertEquals(setOf(1, 2, 3), reached(path(p).between(1, 3)))
        assertEquals(setOf(0, 1, 2), reached(path(p).atMost(2)))
        assertEquals(setOf(2, 3, 4, 5), reached(path(p).atLeast(2)))
        assertEquals(setOf(0, 1, 2, 3, 4, 5), reached(path(p).atLeast(0)))
        assertEquals(setOf(2, 4), reached(path(p).exactly(2).between(1, 2)))
    }

    @Test
    fun `bounds that have no expansion are rejected when the path is built`() {
        for (invalid in listOf<() -> PropertyPathAst>(
            { path(p).exactly(0) },
            { path(p).exactly(-1) },
            { path(p).atLeast(-1) },
            { path(p).atMost(0) },
            { path(p).atMost(-2) },
            { path(p).between(3, 2) },
            { path(p).between(-1, 2) },
            { path(p).between(0, 0) },
            { path(p).exactly(65) },
            { path(p).atLeast(65) },
            { path(p).between(1, 65) },
        )) {
            val e = assertThrows(IllegalArgumentException::class.java) { invalid() }
            assertTrue(e.message!!.contains("repetition"), e.message)
        }
        assertTrue(rendered(path(p).exactly(64)).contains("<urn:p>/<urn:p>"))
        // A node built by hand is checked when it is rendered.
        for (node in listOf(RangePathAst(path(p), 3, 2), RangePathAst(path(p), 0, 0), RangePathAst(path(p), -1, null), RangePathAst(path(p), 0, 1000))) {
            assertThrows(IllegalArgumentException::class.java) { rendered(node) }
        }
        // Nested repetitions multiply; the product is bounded too.
        assertThrows(IllegalArgumentException::class.java) { rendered(path(p).exactly(64).exactly(64).exactly(64)) }
    }

    // ------------------------------------------------------------------ service description

    @Test
    fun `a service description names no endpoint it was not given`() {
        val description = SparqlServiceDescriptionGenerator("https://example.org/service", ProviderCapabilities()).generateServiceDescription()
        val triples = description.getTriples()
        assertTrue(triples.none { it.predicate == SPARQL_SD.endpointProp || it.predicate == SPARQL_SD.updateEndpointProp }, triples.toString())
        assertFalse(triples.any { (it.obj as? Iri)?.value?.startsWith("https://example.org/service/") == true }, triples.toString())
    }

    // ------------------------------------------------------------------ builders

    @Test
    fun `an AST is not changed by its builder afterwards`() {
        val selectBuilder = SelectBuilder(emptyList())
        selectBuilder.variable("x")
        selectBuilder.prefix("ex", "http://example.org/")
        selectBuilder.from(a)
        selectBuilder.fromNamed(a)
        selectBuilder.groupBy(`var`("x"))
        selectBuilder.orderBy(`var`("x"))
        selectBuilder.having { filter(`var`("x") eq 1) }
        val select = selectBuilder.build()
        val before = select.copy()
        selectBuilder.variable("y")
        selectBuilder.prefix("ex2", "http://example.org/2/")
        selectBuilder.from(b)
        selectBuilder.fromNamed(b)
        selectBuilder.groupBy(`var`("y"))
        selectBuilder.orderBy(`var`("y"))
        selectBuilder.having { filter(`var`("y") eq 2) }
        assertEquals(1, select.selectItems.size)
        assertEquals(1, select.prefixes.size)
        assertEquals(listOf(a), select.from)
        assertEquals(listOf(a), select.fromNamed)
        assertEquals(1, select.groupBy.size)
        assertEquals(1, select.orderBy.size)
        assertEquals(1, select.having.size)
        assertEquals(before, select)

        val patternBuilder = PatternBuilder()
        patternBuilder.triple(s, p, o)
        val group = patternBuilder.build() as GroupPatternAst
        patternBuilder.triple(s, p, a)
        assertEquals(1, group.patterns.size)

        val askBuilder = AskBuilder().apply { prefix("ex", "http://example.org/"); from(a); fromNamed(a) }
        val ask = askBuilder.build()
        askBuilder.apply { prefix("ex2", "http://example.org/2/"); from(b); fromNamed(b) }
        assertEquals(listOf(1, 1, 1), listOf(ask.prefixes.size, ask.from.size, ask.fromNamed.size))

        val constructBuilder = ConstructBuilder().apply { prefix("ex", "http://example.org/"); from(a); fromNamed(a); template { triple(s, p, o) } }
        val construct = constructBuilder.build()
        constructBuilder.apply { prefix("ex2", "http://example.org/2/"); from(b); fromNamed(b); template { triple(s, p, a) } }
        assertEquals(listOf(1, 1, 1, 1), listOf(construct.prefixes.size, construct.from.size, construct.fromNamed.size, construct.template.size))

        val describeBuilder = DescribeBuilder(listOf(a)).apply { prefix("ex", "http://example.org/"); from(a); fromNamed(a) }
        val describe = describeBuilder.build()
        describeBuilder.apply { prefix("ex2", "http://example.org/2/"); from(b); fromNamed(b) }
        assertEquals(listOf(1, 1, 1), listOf(describe.prefixes.size, describe.from.size, describe.fromNamed.size))

        val updateBuilder = UpdateBuilder().apply { prefix("ex", "http://example.org/"); clear() }
        val update = updateBuilder.build()
        updateBuilder.apply { prefix("ex2", "http://example.org/2/"); clear() }
        assertEquals(listOf(1, 1), listOf(update.prefixes.size, update.operations.size))

        val insertBuilder = InsertDataBuilder().apply { triple(a, p, b); graph(a) { triple(a, p, b) } }
        val insert = insertBuilder.build()
        insertBuilder.apply { triple(b, p, a); graph(b) { triple(a, p, b) } }
        assertEquals(listOf(1, 1), listOf(insert.data.size, insert.graphData.size))

        val deleteBuilder = DeleteDataBuilder().apply { triple(a, p, b); graph(a) { triple(a, p, b) } }
        val delete = deleteBuilder.build()
        deleteBuilder.apply { triple(b, p, a); graph(b) { triple(a, p, b) } }
        assertEquals(listOf(1, 1), listOf(delete.data.size, delete.graphData.size))

        val modifyBuilder = ModifyBuilder().apply {
            using(a)
            usingNamed(a)
            delete { triple(s, p, o); graph(a) { triple(s, p, o) } }
            insert { triple(s, p, a); graph(a) { triple(s, p, a) } }
        }
        val modify = modifyBuilder.build()
        modifyBuilder.apply {
            using(b)
            usingNamed(b)
            delete { triple(s, p, b); graph(b) { triple(s, p, o) } }
            insert { triple(s, p, b); graph(b) { triple(s, p, a) } }
        }
        assertEquals(
            listOf(1, 1, 1, 1, 1, 1),
            listOf(modify.using.size, modify.usingNamed.size, modify.delete.size, modify.insert.size, modify.deleteGraphs.size, modify.insertGraphs.size),
        )
    }

    // ------------------------------------------------------------------ the empty prefix

    @Test
    fun `the empty prefix can be declared and used`() {
        val query = select("r") {
            prefix("", "http://example.org/fn#")
            prefix("ex", "http://example.org/")
            where {
                triple(s, p, o)
                bind(`var`("r"), function(":double", o.expr()))
            }
        }.sparql
        assertTrue(query.contains("PREFIX : <http://example.org/fn#>\n"), query)
        assertTrue(query.contains("PREFIX ex: <http://example.org/>\n"), query)
        assertTrue(query.contains("BIND(:double(?o) AS ?r)"), query)
        assertParsesQuery(query)

        val ask = ask { prefix("", "http://example.org/"); where { triple(s, p, o) } }.sparql
        assertTrue(assertParsesQuery(ask).startsWith("PREFIX : <http://example.org/>\n"), ask)
        val construct = construct { prefix("", "http://example.org/"); template { triple(s, p, o) }; where { triple(s, p, o) } }.sparql
        assertTrue(assertParsesQuery(construct).startsWith("PREFIX : <http://example.org/>\n"), construct)
        val describe = describe(a) { prefix("", "http://example.org/") }.sparql
        assertTrue(assertParsesQuery(describe).startsWith("PREFIX : <http://example.org/>\n"), describe)
        val update = update { prefix("", "http://example.org/"); clear() }.sparql
        assertTrue(assertParsesUpdate(update).startsWith("PREFIX : <http://example.org/>\n"), update)

        // A label is still checked: nothing but a prefix label can stand before the colon.
        for (label in listOf(" ", "a b", "1a", "a:", "a>", "-a", "a.")) {
            assertThrows(IllegalArgumentException::class.java, { select("s") { prefix(label, "http://example.org/"); where { triple(s, p, o) } } }, label)
        }
    }

    // ------------------------------------------------------------------ typed access

    private fun row(term: RdfTerm): BindingSet = MapBindingSet(mapOf("v" to term))

    @Test
    fun `numbers are only read from literals of a numeric datatype and within range`() {
        assertEquals(42, row(Literal("42", XSD.integer)).getAs<Int>("v"))
        assertEquals(42L, row(Literal("42", XSD.integer)).getAs<Long>("v"))
        assertEquals(-7, row(Literal("-7", XSD.int)).getAs<Int>("v"))
        assertEquals(7, row(Literal("+007", XSD.nonNegativeInteger)).getAs<Int>("v"))
        assertEquals(4_294_967_295L, row(Literal("4294967295", XSD.unsignedInt)).getAs<Long>("v"))
        assertEquals(Long.MIN_VALUE, row(Literal("-9223372036854775808", XSD.long)).getAs<Long>("v"))
        assertEquals(Int.MAX_VALUE, row(Literal("2147483647", XSD.integer)).getAs<Int>("v"))

        // Out of range for the type asked for.
        assertNull(row(Literal("2147483648", XSD.integer)).getAs<Int>("v"))
        assertNull(row(Literal("4294967295", XSD.unsignedInt)).getAs<Int>("v"))
        assertNull(row(Literal("9223372036854775808", XSD.integer)).getAs<Long>("v"))
        assertNull(row(Literal("99999999999999999999999999", XSD.integer)).getAs<Long>("v"))

        // Not an integer datatype, whatever the text looks like.
        for (term in listOf<RdfTerm>(string("42"), LangString("42", "en"), Literal("42", XSD.date), Literal("42", Iri("urn:dt")), Literal("42.0", XSD.decimal), Literal("4.2E1", XSD.double), Iri("urn:42"), BlankNode("b42"))) {
            assertNull(row(term).getAs<Int>("v"), term.toString())
            assertNull(row(term).getAs<Long>("v"), term.toString())
            val e = assertThrows(IllegalArgumentException::class.java) { row(term).getAsOrThrow<Int>("v") }
            assertTrue(e.message!!.contains("'v'"), e.message)
        }
        // An integer datatype with text that is no integer.
        for (lexical in listOf("", "abc", "1.0", "1e3", "0x1F", "1 000", "--1")) {
            assertNull(row(Literal(lexical, XSD.integer)).getAs<Int>("v"), lexical)
            assertNull(row(Literal(lexical, XSD.integer)).getAs<Long>("v"), lexical)
        }

        assertEquals(1.5, row(Literal("1.5", XSD.decimal)).getAs<Double>("v"))
        assertEquals(1000.0, row(Literal("1e3", XSD.double)).getAs<Double>("v"))
        assertEquals(3.0, row(Literal("3", XSD.integer)).getAs<Double>("v"))
        assertEquals(Double.POSITIVE_INFINITY, row(Literal("INF", XSD.float)).getAs<Double>("v"))
        assertNull(row(string("1.5")).getAs<Double>("v"))
        assertNull(row(Literal("1.5", XSD.date)).getAs<Double>("v"))

        assertEquals(true, row(Literal("true", XSD.boolean)).getAs<Boolean>("v"))
        assertEquals(false, row(Literal("0", XSD.boolean)).getAs<Boolean>("v"))
        assertNull(row(string("true")).getAs<Boolean>("v"))
        assertNull(row(Literal("1", XSD.integer)).getAs<Boolean>("v"))

        // The lexical form of any literal is its String.
        assertEquals("42", row(Literal("42", XSD.integer)).getAs<String>("v"))
        assertNull(MapBindingSet(emptyMap()).getAs<Int>("v"))
    }

    @Test
    fun `a type that typed access does not know is an error, not an absent value`() {
        val binding = row(Literal("42", XSD.integer))
        val e = assertThrows(IllegalArgumentException::class.java) { binding.getAs<java.math.BigInteger>("v") }
        assertTrue(e.message!!.contains("BigInteger") && e.message!!.contains("Long"), e.message)
        assertThrows(IllegalArgumentException::class.java) { binding.getAs<Short>("v") }
        assertThrows(IllegalArgumentException::class.java) { binding.getAsOrThrow<Float>("v") }
        assertThrows(IllegalArgumentException::class.java) { binding.getAs<List<String>>("v") }
        // Also when the variable is unbound: the mistake is in the call, not in the data.
        assertThrows(IllegalArgumentException::class.java) { MapBindingSet(emptyMap()).getAs<Short>("v") }
    }

    // ------------------------------------------------------------------ flows

    /** A result whose rows are produced by [rows]; it records the threads that read it and whether it was closed. */
    private class Recording(private val rows: () -> Sequence<BindingSet>) : SparqlQueryResult, AutoCloseable {
        val readers = CopyOnWriteArrayList<Thread>()
        val closed = CountDownLatch(1)

        override fun iterator(): Iterator<BindingSet> = asSequence().iterator()
        override fun count(): Int = error("not used")
        override fun first(): BindingSet? = error("not used")
        override fun toList(): List<BindingSet> = error("not used")
        override fun asSequence(): Sequence<BindingSet> = sequence {
            for (row in rows()) {
                readers += Thread.currentThread()
                yield(row)
            }
        }

        override fun close() {
            closed.countDown()
        }
    }

    private fun bindings(n: Int): Sequence<BindingSet> = (0 until n).asSequence().map { MapBindingSet(mapOf("x" to string("row-$it"))) }

    @Test
    fun `a flow reads its result off the collector's thread`(): Unit = runBlocking {
        val result = Recording { bindings(3) }
        val collector = Thread.currentThread()
        assertEquals(3, result.asFlow("x").toList().size)
        assertEquals(3, result.readers.size)
        assertTrue(result.readers.none { it === collector }, "the result was read on the collecting thread ${collector.name}")
        assertTrue(result.closed.await(30, TimeUnit.SECONDS), "a result that can be closed is closed when the flow is done")
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `cancelling the collector ends a read that blocks`(): Unit = runBlocking {
        val blocked = CountDownLatch(1)
        val never = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val result = Recording {
            sequence {
                yield(MapBindingSet(mapOf("x" to string("first"))))
                blocked.countDown()
                try {
                    never.await()
                } catch (e: InterruptedException) {
                    interrupted.countDown()
                    throw e
                }
            }
        }
        val firstSeen = CountDownLatch(1)
        val job = launch(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { result.asFlow().collect { firstSeen.countDown() } }
        // The row before the blocking read reaches the collector; then the collector is cancelled while the read blocks.
        assertTrue(firstSeen.await(30, TimeUnit.SECONDS), "the first row was not delivered")
        assertTrue(blocked.await(30, TimeUnit.SECONDS), "the second read never started")
        job.cancelAndJoin()
        assertEquals(0L, interrupted.count, "the blocked read was not interrupted")
        assertEquals(0L, result.closed.count, "the result was not closed")
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `cancelling the collector closes a result whose read only ends when it is closed`(): Unit = runBlocking {
        val blocked = CountDownLatch(1)
        val closed = CountDownLatch(1)
        // A read that ignores interrupts, as a read of a socket does: only closing the result ends it.
        val result = object : SparqlQueryResult, AutoCloseable {
            override fun iterator(): Iterator<BindingSet> = asSequence().iterator()
            override fun count(): Int = error("not used")
            override fun first(): BindingSet? = error("not used")
            override fun toList(): List<BindingSet> = error("not used")
            override fun asSequence(): Sequence<BindingSet> = sequence<BindingSet> {
                blocked.countDown()
                var interrupted = false
                while (true) {
                    try {
                        closed.await()
                        break
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                if (interrupted) Thread.currentThread().interrupt()
                throw java.io.IOException("closed")
            }

            override fun close() {
                closed.countDown()
            }
        }
        val job = launch(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { result.asFlow().collect { } }
        assertTrue(blocked.await(30, TimeUnit.SECONDS), "the read never started")
        job.cancelAndJoin()
        assertEquals(0L, closed.count)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `a flow reads on the dispatcher it is given and reports what the read reports`(): Unit = runBlocking {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "result-reader").apply { isDaemon = true } }
        try {
            val result = Recording { bindings(4) }
            assertEquals(4, result.asFlow(executor.asCoroutineDispatcher(), "x").toList().size)
            // In debug mode kotlinx.coroutines appends " @coroutine#N" to the name of a thread while it runs a coroutine.
            assertEquals(listOf("result-reader"), result.readers.map { it.name.substringBefore(" @") }.distinct())
            assertEquals(0L, result.closed.count)

            // A failing read fails the flow with what it threw, and the result is closed.
            val failing = Recording { sequence { yield(MapBindingSet(mapOf("x" to string("first")))); throw IllegalStateException("read failed") } }
            val e = assertThrows(IllegalStateException::class.java) { runBlocking { failing.asFlow(executor.asCoroutineDispatcher()).collect { } } }
            assertEquals("read failed", e.message)
            assertEquals(0L, failing.closed.count)

            // So does a row that lacks a required variable.
            val incomplete = Recording { bindings(2) }
            assertThrows(IllegalStateException::class.java) { runBlocking { incomplete.asFlow(executor.asCoroutineDispatcher(), "x", "y").toList() } }
            assertEquals(0L, incomplete.closed.count)

            // A result that cannot be closed is simply read.
            assertEquals(2, sparqlQueryResult(bindings(2).toList()).asFlow(executor.asCoroutineDispatcher()).toList().size)
        } finally {
            executor.shutdownNow()
        }
    }

    // ------------------------------------------------------------------ additions to the DSL

    private val q = Iri("urn:q")
    private val v = `var`("v")

    /** `urn:x` has `p` and `q`, `urn:z` only `p`; the values of `q` are numbers. */
    private fun people(): Model = ModelFactory.createDefaultModel().also { model ->
        val x = model.createResource("urn:x")
        val z = model.createResource("urn:z")
        model.add(x, model.createProperty(p.value), model.createResource("urn:a"))
        model.add(z, model.createProperty(p.value), model.createResource("urn:b"))
        model.addLiteral(x, model.createProperty(q.value), 5L)
        model.addLiteral(x, model.createProperty(q.value), 7L)
    }

    private fun subjects(sparql: String): Set<String> =
        QueryExecutionFactory.create(assertParsesQuery(sparql), people()).use { execution ->
            execution.execSelect().asSequence().map { it.getResource("s").uri }.toSet()
        }

    @Test
    fun `EXISTS and NOT EXISTS are conditions over a pattern`() {
        val having = select("s") { where { triple(s, p, o); filter { exists { triple(s, q, v) } } } }.sparql
        assertTrue(having.contains("FILTER(EXISTS {\n  ?s <urn:q> ?v .\n})"), having)
        assertEquals(setOf("urn:x"), subjects(having))

        val lacking = select("s") { where { triple(s, p, o); filter { notExists { triple(s, q, v) } } } }.sparql
        assertTrue(lacking.contains("FILTER(NOT EXISTS {"), lacking)
        assertEquals(setOf("urn:z"), subjects(lacking))

        // They combine like any other condition, and the pattern may hold whatever a group may.
        assertEquals(setOf("urn:z"), subjects(select("s") { where { triple(s, p, o); filter { !exists { triple(s, q, v) } } } }.sparql))
        assertEquals(setOf("urn:z"), subjects(select("s") { where { triple(s, p, o); filter { not(exists { triple(s, q, v) }) } } }.sparql))
        val nested = select("s") {
            where {
                triple(s, p, o)
                filter { exists { triple(s, q, v); filter(v gt 6) } and notExists { triple(s, q, v); filter(v gt 8) } }
            }
        }.sparql
        assertEquals(setOf("urn:x"), subjects(nested))
        assertEquals(emptySet<String>(), subjects(select("s") { where { triple(s, p, o); filter { exists { triple(s, q, v); filter(v gt 8) } } } }.sparql))

        // Outside FILTER: BIND, the projection, HAVING and ORDER BY.
        val places = select {
            variable("s")
            expression(exists { triple(s, q, v) }, "has")
            where { triple(s, p, o); bind(`var`("b"), notExists { triple(s, q, v) }) }
            orderBy(exists { triple(s, q, v) }, OrderDirection.DESC)
            orderBy(notExists { triple(s, q, v) })
        }.sparql
        assertTrue(places.contains("(EXISTS {") && places.contains("BIND(NOT EXISTS {") && places.contains("ORDER BY DESC(EXISTS {"), places)
        assertEquals(setOf("urn:x", "urn:z"), subjects(places))

        // The pattern of EXISTS is a group of its own: a blank node label cannot be shared with the patterns around it.
        val shared = com.geoknoesis.kastor.rdf.BlankNode("b1")
        assertThrows(IllegalArgumentException::class.java) {
            select("s") { where { triple(s, p, shared); filter { exists { triple(shared, q, v) } } } }
        }
        assertParsesQuery(select("s") { where { triple(s, p, o); filter { exists { triple(shared, q, v); triple(shared, p, o) } } } }.sparql)
    }

    @Test
    fun `IN and NOT IN test membership of a list`() {
        val one = select("s") { where { triple(s, p, o); filter(o.isIn(a, Iri("urn:c"))) } }.sparql
        assertTrue(one.contains("FILTER(?o IN (<urn:a>, <urn:c>))"), one)
        assertEquals(setOf("urn:x"), subjects(one))
        val other = select("s") { where { triple(s, p, o); filter(o.isNotIn(a, Iri("urn:c"))) } }.sparql
        assertTrue(other.contains("FILTER(?o NOT IN (<urn:a>, <urn:c>))"), other)
        assertEquals(setOf("urn:z"), subjects(other))

        // Any expression on either side; an operand that binds less tightly is bracketed.
        val sum = select("s") { where { triple(s, q, v); filter { (v.expr() plus v.expr()) isIn listOf(10.toLiteral().expr(), strlen(v.expr())) } } }.sparql
        assertTrue(sum.contains("FILTER((?v + ?v) IN ("), sum)
        assertEquals(setOf("urn:x"), subjects(sum))
        val compared = select("s") { where { triple(s, q, v); filter { (v gt 6).isIn(boolean(true).expr()) } } }.sparql
        assertTrue(compared.contains("FILTER((?v > "), compared)
        assertEquals(setOf("urn:x"), subjects(compared))
        val both = select("s") { where { triple(s, p, o); filter { o.isIn(a) or o.expr().isNotIn(a.expr(), b.expr()) } } }.sparql
        assertEquals(setOf("urn:x"), subjects(both))

        // The empty list is legal: nothing is in it.
        assertEquals(emptySet<String>(), subjects(select("s") { where { triple(s, p, o); filter(o.isIn()) } }.sparql))
        assertEquals(setOf("urn:x", "urn:z"), subjects(select("s") { where { triple(s, p, o); filter(o.expr() isNotIn emptyList()) } }.sparql))
    }

    @Test
    fun `unary minus negates an expression`() {
        fun values(expression: ExpressionAst): Set<Long> {
            val query = select("r") { where { triple(s, q, v); bind(`var`("r"), expression) } }.sparql
            return QueryExecutionFactory.create(assertParsesQuery(query), people()).use { execution ->
                execution.execSelect().asSequence().map { it.getLiteral("r").long }.toSet()
            }
        }
        assertEquals(setOf(-5L, -7L), values(-v.expr()))
        assertEquals(setOf(5L, 7L), values(-(-v.expr())))
        assertEquals(setOf(-10L, -14L), values(-(v.expr() plus v.expr())))
        assertEquals(setOf(0L), values(v.expr() plus -v.expr()))
        assertEquals(setOf(10L, 14L), values(v.expr() minus -v.expr()))
        assertEquals(setOf(-4L, -6L), values(-v.expr() plus 1.toLiteral().expr()))

        fun text(expression: ExpressionAst) = select("r") { where { triple(s, q, v); bind(`var`("r"), expression) } }.sparql
        assertTrue(text(-v.expr()).contains("BIND(-?v AS ?r)"))
        assertTrue(text(-(-v.expr())).contains("BIND(-(-?v) AS ?r)"))
        assertTrue(text(-(v.expr() plus v.expr())).contains("BIND(-(?v + ?v) AS ?r)"))
        assertTrue(text(v.expr() minus -v.expr()).contains("BIND((?v - -?v) AS ?r)"))
        assertTrue(text(-strlen(v.expr())).contains("BIND(-STRLEN(?v) AS ?r)"))
    }

    @Test
    fun `GROUP BY takes expressions with or without a name`() {
        val named = select {
            variable("len")
            expression(countAll(), "n")
            where { triple(s, p, o) }
            groupBy(strlen(function("STR", o.expr())), `var`("len"))
        }.sparql
        assertTrue(named.contains("GROUP BY (STRLEN(STR(?o)) AS ?len)\n"), named)
        val counts = QueryExecutionFactory.create(assertParsesQuery(named), people()).use { execution ->
            execution.execSelect().asSequence().map { it.getLiteral("len").int to it.getLiteral("n").int }.toList()
        }
        assertEquals(listOf(5 to 2), counts)

        val unnamed = select {
            expression(countAll(), "n")
            where { triple(s, q, v) }
            groupBy(v gt 6)
        }.sparql
        assertTrue(unnamed.contains("GROUP BY (?v > "), unnamed)
        assertParsesQuery(unnamed)

        // Variables come first, then the expressions, each in the order it was given.
        val mixed = select {
            variable("s")
            variable("k")
            expression(countAll(), "n")
            where { triple(s, q, v) }
            groupBy(s)
            groupBy(v.expr() plus 1.toLiteral().expr(), `var`("k"))
            groupBy(-v.expr())
            having { filter(countAll() gt 0.toLiteral().expr()) }
        }.sparql
        assertTrue(mixed.contains("GROUP BY ?s ((?v + "), mixed)
        assertTrue(mixed.contains(" AS ?k) (-?v)\n"), mixed)
        assertParsesQuery(mixed)

        // A grouped query still needs an explicit projection.
        assertThrows(IllegalArgumentException::class.java) {
            SelectQueryAst(emptyList(), where = GroupPatternAst(emptyList()), groupByExpressions = listOf(GroupConditionAst(v.expr())))
        }
    }

    @Test
    fun `SERVICE SILENT is written when asked for`() {
        val endpoint = Iri("http://example.org/sparql")
        val silent = select("s") { where { service(endpoint, silent = true) { triple(s, p, o) } } }.sparql
        assertTrue(silent.contains("SERVICE SILENT <http://example.org/sparql> {"), silent)
        assertParsesQuery(silent)
        val byVariable = select("s") { where { triple(`var`("e"), p, o); service(`var`("e"), silent = true) { triple(s, p, o) } } }.sparql
        assertTrue(byVariable.contains("SERVICE SILENT ?e {"), byVariable)
        assertParsesQuery(byVariable)
        for (plain in listOf(
            select("s") { where { service(endpoint) { triple(s, p, o) } } }.sparql,
            select("s") { where { service(endpoint, silent = false) { triple(s, p, o) } } }.sparql,
        )) {
            assertTrue(plain.contains("SERVICE <http://example.org/sparql> {") && !plain.contains("SILENT"), plain)
            assertParsesQuery(plain)
        }
        assertEquals(ServicePatternAst(endpoint, GroupPatternAst(emptyList())), ServicePatternAst(endpoint, GroupPatternAst(emptyList()), silent = false))
    }

    @Test
    fun `a function call is a condition as well as an expression`() {
        val query = select("s") {
            prefix("ex", "http://example.org/fn#")
            where {
                triple(s, p, o)
                filter { function("isIRI", o.expr()) }
                filter(function("ex:accept", o.expr(), s.expr()))
                filter { function("isIRI", o.expr()) and not(function("isBLANK", s.expr())) or (o.expr() eq a.expr()) }
                bind(`var`("r"), function("STR", o.expr()))
            }
        }.sparql
        assertTrue(query.contains("FILTER(isIRI(?o))") && query.contains("FILTER(ex:accept(?o, ?s))"), query)
        assertParsesQuery(query)
        assertEquals(
            setOf("urn:x", "urn:z"),
            subjects(select("s") { where { triple(s, p, o); filter { function("isIRI", o.expr()) and !function("isBLANK", s.expr()) } } }.sparql),
        )

        // Callers compiled against the earlier return type still link.
        val dsl = Class.forName("com.geoknoesis.kastor.rdf.sparql.SparqlDslKt")
        val returnTypes = dsl.declaredMethods.filter { it.name == "function" }.map { it.returnType }.toSet()
        assertEquals(setOf<Class<*>>(ExpressionAst::class.java, FunctionCallAst::class.java), returnTypes)
    }

    @Test
    fun `earlier constructors of the changed AST nodes still link`() {
        val list = List::class.java
        val string = String::class.java
        val integer = Integer::class.java
        val bool = Boolean::class.javaPrimitiveType!!
        val pattern = GraphPatternAst::class.java
        val items = listOf(VariableSelectItemAst(s))
        val select = SelectQueryAst::class.java
        val built = select.getConstructor(list, string, list, pattern, list, list, list, list, list, integer, integer, bool, bool)
            .newInstance(items, null, emptyList<Any>(), null, emptyList<Any>(), emptyList<Any>(), listOf(s), emptyList<Any>(), emptyList<Any>(), 10, null, true, false)
        assertEquals(SelectQueryAst(items, groupBy = listOf(s), limit = 10, distinct = true), built)
        val grouped = SelectQueryAst(items, groupByExpressions = listOf(GroupConditionAst(o.expr())))
        val copied = select.getMethod("copy", list, string, list, pattern, list, list, list, list, list, integer, integer, bool, bool)
            .invoke(grouped, items, null, emptyList<Any>(), null, emptyList<Any>(), emptyList<Any>(), emptyList<Any>(), emptyList<Any>(), emptyList<Any>(), 5, null, false, false)
        assertEquals(grouped.copy(limit = 5), copied)

        val service = ServicePatternAst::class.java
        val term = RdfTerm::class.java
        val group = GroupPatternAst(emptyList())
        assertEquals(ServicePatternAst(a, group), service.getConstructor(term, pattern).newInstance(a, group))
        assertEquals(
            ServicePatternAst(b, group, silent = true),
            service.getMethod("copy", term, pattern).invoke(ServicePatternAst(a, group, silent = true), b, group),
        )
    }

    // ------------------------------------------------------------------ service description, as asked for

    @Test
    fun `a service description states the endpoints it is given`() {
        val service = Iri("https://example.org/service")
        val generator = SparqlServiceDescriptionGenerator(
            "https://example.org/service", ProviderCapabilities(), endpoint = "https://example.org/query", updateEndpoint = "https://example.org/data/update",
        )
        val triples = generator.generateServiceDescription().getTriples()
        assertEquals(listOf<RdfTerm>(Iri("https://example.org/query")), triples.filter { it.subject == service && it.predicate == SPARQL_SD.endpointProp }.map { it.obj })
        assertEquals(
            listOf<RdfTerm>(Iri("https://example.org/data/update")),
            triples.filter { it.subject == service && it.predicate == SPARQL_SD.updateEndpointProp }.map { it.obj },
        )
        // One without the other, and a service that is its own endpoint.
        val queryOnly = SparqlServiceDescriptionGenerator("https://example.org/sparql", ProviderCapabilities(), endpoint = "https://example.org/sparql")
            .generateServiceDescription().getTriples()
        assertEquals(1, queryOnly.count { it.predicate == SPARQL_SD.endpointProp && it.obj == Iri("https://example.org/sparql") })
        assertEquals(0, queryOnly.count { it.predicate == SPARQL_SD.updateEndpointProp })
        // What is not an IRI is refused, not passed on.
        assertThrows(IllegalArgumentException::class.java) {
            SparqlServiceDescriptionGenerator("https://example.org/service", ProviderCapabilities(), endpoint = "not an iri").generateServiceDescription()
        }
        // The constructor without endpoints is still there for compiled callers.
        SparqlServiceDescriptionGenerator::class.java.getConstructor(String::class.java, ProviderCapabilities::class.java)
    }

    @Test
    fun `the service description as a SELECT query is called what it is`() {
        val generator = SparqlServiceDescriptionGenerator("https://example.org/service", ProviderCapabilities(), endpoint = "https://example.org/query")
        val query = generator.generateAsSelectQuery()
        assertTrue(query.startsWith("SELECT ?subject ?predicate ?object WHERE {"), query)
        assertTrue(query.contains("<https://example.org/query>"), query)
        // Run anywhere, it yields the triples of the description.
        val rows = QueryExecutionFactory.create(assertParsesQuery(query), ModelFactory.createDefaultModel()).use { execution ->
            execution.execSelect().asSequence().count()
        }
        assertEquals(generator.generateServiceDescription().getTriples().size, rows)
        @Suppress("DEPRECATION")
        assertEquals(query, generator.generateAsSparqlResult())
    }
}
