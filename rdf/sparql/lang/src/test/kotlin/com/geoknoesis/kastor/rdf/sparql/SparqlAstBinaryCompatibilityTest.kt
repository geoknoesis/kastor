package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Var
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * JVM signatures published in 0.2.1 that later source changes replaced (nullable graph IRIs, new
 * constructor parameters). They are kept as hidden bridges so code compiled against 0.2.1 still
 * links; the mangled names are the ones the 0.2.1 compiler emitted.
 */
class SparqlAstBinaryCompatibilityTest {

    private val string = String::class.java
    private val list = List::class.java
    private val bool = Boolean::class.javaPrimitiveType!!
    private val int = Int::class.javaPrimitiveType!!
    private val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
    private val any = Any::class.java

    @Test
    fun `COPY MOVE and ADD keep their non-null 0_2_1 accessors and copy`() {
        val cases = listOf(
            CopyOperationAst(Iri("urn:s"), Iri("urn:d")) to CopyOperationAst(Iri("urn:s"), Iri("urn:y"), silent = true),
            MoveOperationAst(Iri("urn:s"), Iri("urn:d")) to MoveOperationAst(Iri("urn:s"), Iri("urn:y"), silent = true),
            AddOperationAst(Iri("urn:s"), Iri("urn:d")) to AddOperationAst(Iri("urn:s"), Iri("urn:y"), silent = true),
        )
        for ((operation, expected) in cases) {
            val type = operation.javaClass
            assertEquals("urn:s", type.getMethod("getSource-tqZU9bw").invoke(operation))
            assertEquals("urn:d", type.getMethod("getDestination-tqZU9bw").invoke(operation))
            assertEquals("urn:s", type.getMethod("component1-tqZU9bw").invoke(operation))
            assertEquals("urn:d", type.getMethod("component2-tqZU9bw").invoke(operation))
            assertEquals(expected, type.getMethod("copy-VXt0FlY", string, string, bool, list, list, string)
                .invoke(operation, "urn:s", "urn:y", true, emptyList<Iri>(), emptyList<Iri>(), null))
            // Defaults for everything but destination and silent (mask bits 0, 3, 4, 5).
            assertEquals(expected, type.getMethod("copy-VXt0FlY\$default", type, string, string, bool, list, list, string, int, any)
                .invoke(null, operation, null, "urn:y", true, null, null, null, 0b111001, null))
            assertNotNull(type.getConstructor(string, string, bool, list, list, string, marker))
        }
    }

    @Test
    fun `CLEAR and DROP keep their 0_2_1 constructors and copy`() {
        val clear = ClearOperationAst::class.java
        assertEquals(ClearOperationAst(Iri("urn:g"), silent = true),
            clear.getConstructor(string, bool, list, list, string, marker).newInstance("urn:g", true, emptyList<Iri>(), emptyList<Iri>(), null, null))
        assertEquals(ClearOperationAst(Iri("urn:g")),
            clear.getConstructor(string, bool, list, list, string, int, marker).newInstance("urn:g", false, null, null, null, 0b11110, null))
        assertEquals(ClearOperationAst(Iri("urn:h"), silent = true),
            clear.getMethod("copy-z3iZ69M", string, bool, list, list, string).invoke(ClearOperationAst(Iri("urn:g")), "urn:h", true, emptyList<Iri>(), emptyList<Iri>(), null))
        assertNotNull(clear.getMethod("copy-z3iZ69M\$default", clear, string, bool, list, list, string, int, any))

        val drop = DropOperationAst::class.java
        assertEquals(DropOperationAst(null, silent = true),
            drop.getConstructor(string, bool, list, list, string, marker).newInstance(null, true, emptyList<Iri>(), emptyList<Iri>(), null, null))
        assertNotNull(drop.getConstructor(string, bool, list, list, string, int, marker))
        assertEquals(DropOperationAst(Iri("urn:h")),
            drop.getMethod("copy-z3iZ69M", string, bool, list, list, string).invoke(DropOperationAst(), "urn:h", false, emptyList<Iri>(), emptyList<Iri>(), null))
        assertNotNull(drop.getMethod("copy-z3iZ69M\$default", drop, string, bool, list, list, string, int, any))
    }

    @Test
    fun `INSERT DATA DELETE DATA and DELETE-INSERT keep their 0_2_1 constructors and copy`() {
        val triple = TriplePatternAst(Var("s"), Iri("urn:p"), Literal("o"))
        for (type in listOf(InsertDataOperationAst::class.java, DeleteDataOperationAst::class.java)) {
            val built = type.getConstructor(list, list, list, string, marker).newInstance(listOf(triple), emptyList<Iri>(), emptyList<Iri>(), null, null)
            assertEquals(listOf(triple), type.getMethod("getData").invoke(built))
            assertNotNull(type.getConstructor(list, list, list, string, int, marker).newInstance(listOf(triple), null, null, null, 0b1110, null))
            assertEquals(built, type.getMethod("copy-HMI5rLE", list, list, list, string).invoke(built, listOf(triple), emptyList<Iri>(), emptyList<Iri>(), null))
            assertNotNull(type.getMethod("copy-HMI5rLE\$default", type, list, list, list, string, int, any))
        }
        val modify = ModifyOperationAst::class.java
        val graphPattern = GraphPatternAst::class.java
        assertEquals(ModifyOperationAst(delete = listOf(triple)),
            modify.getConstructor(list, list, graphPattern, list, list, string, marker)
                .newInstance(listOf(triple), emptyList<TriplePatternAst>(), null, emptyList<Iri>(), emptyList<Iri>(), null, null))
        assertEquals(ModifyOperationAst(insert = listOf(triple)),
            modify.getConstructor(list, list, graphPattern, list, list, string, int, marker)
                .newInstance(null, listOf(triple), null, null, null, null, 0b111101, null))
        assertEquals(ModifyOperationAst(insert = listOf(triple)),
            modify.getMethod("copy-wt97nKI", list, list, graphPattern, list, list, string)
                .invoke(ModifyOperationAst(), emptyList<TriplePatternAst>(), listOf(triple), null, emptyList<Iri>(), emptyList<Iri>(), null))
        assertNotNull(modify.getMethod("copy-wt97nKI\$default", modify, list, list, graphPattern, list, list, string, int, any))
    }

    @Test
    fun `aggregates keep their 0_2_1 constructor and copy`() {
        val type = AggregateExpressionAst::class.java
        val function = AggregateFunction::class.java
        val expression = ExpressionAst::class.java
        val x = TermExpressionAst(Var("x"))
        assertEquals(AggregateExpressionAst(AggregateFunction.SUM, x, true),
            type.getConstructor(function, expression, bool).newInstance(AggregateFunction.SUM, x, true))
        assertEquals(AggregateExpressionAst(AggregateFunction.SUM, x),
            type.getConstructor(function, expression, bool, int, marker).newInstance(AggregateFunction.SUM, x, true, 0b100, null))
        val count = AggregateExpressionAst(AggregateFunction.COUNT, x)
        assertEquals(AggregateExpressionAst(AggregateFunction.MAX, x, true),
            type.getMethod("copy", function, expression, bool).invoke(count, AggregateFunction.MAX, x, true))
        assertEquals(AggregateExpressionAst(AggregateFunction.MAX, x),
            type.getMethod("copy\$default", type, function, expression, bool, int, any).invoke(null, count, AggregateFunction.MAX, null, false, 0b110, null))
    }

    @Test
    fun `builders keep their 0_2_1 methods`() {
        val builder = UpdateBuilder::class.java
        val update = UpdateBuilder()
        builder.getMethod("copy-JZ4Kbmc", string, string, bool).invoke(update, "urn:a", "urn:b", false)
        builder.getMethod("move-JZ4Kbmc", string, string, bool).invoke(update, "urn:a", "urn:b", true)
        builder.getMethod("add-JZ4Kbmc", string, string, bool).invoke(update, "urn:a", "urn:b", false)
        builder.getMethod("copy-JZ4Kbmc\$default", builder, string, string, bool, int, any).invoke(null, update, "urn:c", "urn:d", false, 0b100, null)
        assertNotNull(builder.getMethod("move-JZ4Kbmc\$default", builder, string, string, bool, int, any))
        assertNotNull(builder.getMethod("add-JZ4Kbmc\$default", builder, string, string, bool, int, any))
        assertEquals(
            listOf(
                CopyOperationAst(Iri("urn:a"), Iri("urn:b")),
                MoveOperationAst(Iri("urn:a"), Iri("urn:b"), silent = true),
                AddOperationAst(Iri("urn:a"), Iri("urn:b")),
                CopyOperationAst(Iri("urn:c"), Iri("urn:d")),
            ),
            update.build().operations,
        )
        assertNotNull(PatternBuilder::class.java.getMethod("filter", Function0::class.java))
    }
}
