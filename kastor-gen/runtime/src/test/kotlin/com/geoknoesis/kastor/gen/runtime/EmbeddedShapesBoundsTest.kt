package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfTerm
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [EmbeddedShapesBounds] refuses embedded shapes whose unfolded cost (as `jena-shacl` and `ShaclSail` evaluate them)
 * is too large, whatever construct makes them branch, and malformed lists, before an engine sees them. Each family
 * below doubles the cost at every level in a different way; every family is refused for its cost at depth 12 (about
 * `2^12`, past [EmbeddedShapesBounds.MAX_COST]) and admitted at depth 3.
 */
@OptIn(KastorGenInternalApi::class)
class EmbeddedShapesBoundsTest {
    private val prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <http://ex/> . " +
        "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
    private val data = "ex:a ex:p \"x\" .\n"

    private fun key(term: RdfTerm): String = when (term) {
        is Iri -> term.value
        is BlankNode -> "_:" + term.id
        else -> "\"$term"
    }

    private fun check(turtle: String) {
        val triples = Rdf.parse(prefixes + turtle, "TURTLE").getTriples()
        EmbeddedShapesBounds.requireWithin(triples.asSequence().map { Triple(key(it.subject), it.predicate.value, key(it.obj)) }, "Test")
    }

    private fun refused(turtle: String, reason: String) {
        val error = assertThrows<EmbeddedShapesRefusedException>(turtle.take(300)) { check(turtle) }
        assertTrue(error.message!!.contains(reason), error.message)
        assertTrue(error.message!!.contains("Test.trustingEmbeddedShapes()"), error.message)
    }

    /** One level of each doubling family: shape `i` references shape `i + 1` twice. */
    private val families: Map<String, (Int) -> String> = mapOf(
        "or" to { i -> "ex:S$i sh:or ( ex:S${i + 1} ex:S${i + 1} ) ." },
        "and" to { i -> "ex:S$i sh:and ( ex:S${i + 1} ex:S${i + 1} ) ." },
        "xone" to { i -> "ex:S$i sh:xone ( ex:S${i + 1} ex:S${i + 1} ) ." },
        "two sh:or values" to { i -> "ex:S$i sh:or ( ex:S${i + 1} ), ( ex:S${i + 1} ) ." },
        "IRI list nodes" to { i -> "ex:S$i sh:or ex:L${i}a . ex:L${i}a rdf:first ex:S${i + 1} ; rdf:rest ex:L${i}b . ex:L${i}b rdf:first ex:S${i + 1} ; rdf:rest rdf:nil ." },
        "node twice through wrappers" to { i -> "ex:S$i sh:node [ sh:node ex:S${i + 1} ], [ sh:node ex:S${i + 1} ] ." },
        "node and double negation" to { i -> "ex:S$i sh:node ex:S${i + 1} ; sh:not [ sh:not ex:S${i + 1} ] ." },
        "two property shapes" to { i -> "ex:S$i sh:property [ sh:path ex:p ; sh:node ex:S${i + 1} ], [ sh:path ex:q ; sh:node ex:S${i + 1} ] ." },
        "shared property shape" to { i -> "ex:S$i sh:property ex:P$i ; sh:node ex:P$i . ex:P$i sh:path ex:p ; sh:node ex:S${i + 1} ." },
        "qualified value shapes" to { i ->
            "ex:S$i sh:property [ sh:path ex:p ; sh:qualifiedValueShape ex:S${i + 1} ; sh:qualifiedMinCount 1 ], " +
                "[ sh:path ex:q ; sh:qualifiedValueShape ex:S${i + 1} ; sh:qualifiedMinCount 1 ] ."
        },
        "disjoint qualified siblings" to { i ->
            // Only the sibling makes it branch: the property shape with the reference is disjoint from the other.
            "ex:S$i sh:property [ sh:path ex:p ; sh:qualifiedValueShape [ sh:class ex:C ] ; sh:qualifiedMinCount 1 ; sh:qualifiedValueShapesDisjoint true ], " +
                "[ sh:path ex:q ; sh:qualifiedValueShape ex:S${i + 1} ; sh:qualifiedMinCount 1 ] ."
        },
        "or inside not" to { i -> "ex:S$i sh:not [ sh:or ( ex:S${i + 1} [ sh:not ex:S${i + 1} ] ) ] ." },
        "mixed" to { i ->
            when (i % 3) {
                0 -> "ex:S$i sh:or ( ex:S${i + 1} ex:S${i + 1} ) ."
                1 -> "ex:S$i sh:property [ sh:path ex:p ; sh:node ex:S${i + 1} ], [ sh:path ex:q ; sh:node ex:S${i + 1} ] ."
                else -> "ex:S$i sh:and ( ex:S${i + 1} [ sh:not ex:S${i + 1} ] ) ."
            }
        },
    )

    private fun chain(levels: Int, level: (Int) -> String): String =
        "ex:S0 a sh:NodeShape ; sh:targetNode ex:a .\n" + (0 until levels).joinToString("\n") { level(it) } +
            "\nex:S$levels sh:class ex:Nope .\n" + data

    @Test
    fun `every doubling family is refused deep and admitted shallow`() {
        for ((name, level) in families) {
            // Families with two levels of nesting per doubling reach the depth cap first.
            val error = assertThrows<EmbeddedShapesRefusedException>(name) { check(chain(20, level)) }
            assertTrue(error.message!!.contains("unfolded into a tree") || error.message!!.contains("levels deep"), error.message)
            refused(chain(12, level), "unfolded into a tree")
            admitted(name) { check(chain(3, level)) }
        }
    }

    @Test
    fun `the reviewer's probe is refused at every depth past the cap`() {
        val level = families.getValue("or")
        for (n in 11..26) refused(chain(n, level), "unfolded into a tree")
        admitted { check(chain(9, level)) }
    }

    @Test
    fun `untyped untargeted shapes count only when a targeted shape reaches them`() {
        // Unreachable: the engines evaluate no shape without a target, so this graph is not refused.
        val unreachable = (0 until 20).joinToString("\n") { families.getValue("or")(it) } + "\n" + data
        admitted { check(unreachable) }
        // A shape type alone makes a root.
        refused("ex:S0 a sh:NodeShape .\n" + unreachable, "unfolded into a tree")
        // So does any target.
        for (target in listOf("sh:targetClass ex:C", "sh:targetSubjectsOf ex:p", "sh:targetObjectsOf ex:p", "sh:targetNode ex:a")) {
            refused("ex:S0 $target .\n" + unreachable, "unfolded into a tree")
        }
    }

    @Test
    fun `many roots add up`() {
        val roots = (0 until 2_000).joinToString("\n") { "ex:R$it sh:targetNode ex:a ; sh:class ex:Nope ." }
        refused(roots + "\n" + data, "unfolded into a tree")
        admitted { check((0 until 100).joinToString("\n") { "ex:R$it sh:targetNode ex:a ; sh:class ex:Nope ." } + "\n" + data) }
    }

    @Test
    fun `nesting deeper than the cap is refused, without branching`() {
        val node: (Int) -> String = { i -> "ex:S$i sh:node ex:S${i + 1} ." }
        refused(chain(EmbeddedShapesBounds.MAX_DEPTH + 1, node), "levels deep")
        admitted { check(chain(EmbeddedShapesBounds.MAX_DEPTH - 1, node)) }
        val property: (Int) -> String = { i -> "ex:S$i sh:property [ sh:path ex:p ; sh:node ex:S${i + 1} ] ." }
        refused(chain(EmbeddedShapesBounds.MAX_DEPTH, property), "levels deep")
    }

    @Test
    fun `deep and cyclic paths are refused`() {
        var path = "ex:p"
        repeat(EmbeddedShapesBounds.MAX_DEPTH + 1) { path = "[ sh:inversePath $path ]" }
        refused("ex:S sh:targetNode ex:a ; sh:property [ sh:path $path ; sh:minCount 1 ] .\n$data", "levels deep")
        for (cycle in listOf(
            "_:x sh:inversePath _:x .",
            "_:x sh:zeroOrMorePath _:y . _:y sh:oneOrMorePath _:x .",
            "_:x sh:alternativePath ( _:x ex:p ) .",
            "_:x rdf:first _:x ; rdf:rest rdf:nil .",
        )) {
            refused("ex:S sh:targetNode ex:a ; sh:property [ sh:path _:x ; sh:minCount 1 ] .\n$cycle\n$data", "levels deep")
        }
        admitted { check("ex:S sh:targetNode ex:a ; sh:property [ sh:path ( ex:p [ sh:inversePath ex:q ] [ sh:alternativePath ( ex:r [ sh:zeroOrMorePath ex:s ] ) ] ) ; sh:minCount 1 ] .\n$data") }
    }

    @Test
    fun `recursive shapes close the cycle and are admitted`() {
        admitted { check("ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:node ex:S ] .\n$data") }
        admitted { check("ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:or ( ex:S ex:T ) . ex:T sh:not ex:S .\n$data") }
    }

    private fun values(n: Int) = (0 until n).joinToString(" ") { "\"v$it\"" }

    @Test
    fun `long and malformed lists are refused`() {
        for (parameter in listOf("sh:in", "sh:languageIn", "sh:ignoredProperties")) {
            refused("ex:S sh:targetNode ex:a ; $parameter ( ${values(EmbeddedShapesBounds.MAX_LIST_LENGTH + 1)} ) .\n$data", "more than ${EmbeddedShapesBounds.MAX_LIST_LENGTH} members")
        }
        admitted { check("ex:S sh:targetNode ex:a ; sh:in ( ${values(EmbeddedShapesBounds.MAX_LIST_LENGTH)} ) .\n$data") }
        val operands = { n: Int -> (0 until n).joinToString(" ") { "[ sh:class ex:C$it ]" } }
        for (logical in listOf("sh:or", "sh:and", "sh:xone")) {
            refused("ex:S sh:targetNode ex:a ; $logical ( ${operands(EmbeddedShapesBounds.MAX_OPERANDS + 1)} ) .\n$data", "more than ${EmbeddedShapesBounds.MAX_OPERANDS} members")
            admitted { check("ex:S sh:targetNode ex:a ; $logical ( ${operands(EmbeddedShapesBounds.MAX_OPERANDS)} ) .\n$data") }
        }
        val malformed = mapOf(
            "_:l rdf:first ex:T ; rdf:rest _:l ." to "cyclic",
            "_:l rdf:first ex:T ; rdf:rest _:m . _:m rdf:first ex:T ; rdf:rest _:l ." to "cyclic",
            "_:l rdf:first ex:T ; rdf:rest _:m, _:n . _:m rdf:first ex:T ; rdf:rest rdf:nil . _:n rdf:first ex:T ; rdf:rest rdf:nil ." to "exactly one",
            "_:l rdf:first ex:T, ex:U ; rdf:rest rdf:nil ." to "exactly one",
            "_:l rdf:first ex:T ." to "exactly one",
            "_:l rdf:rest rdf:nil ." to "exactly one",
            "_:l rdf:first ex:T ; rdf:rest ex:end ." to "exactly one",
        )
        for ((list, reason) in malformed) {
            for (parameter in listOf("sh:or", "sh:in", "sh:ignoredProperties")) {
                refused("ex:S sh:targetNode ex:a ; $parameter _:l .\n$list\nex:T sh:class ex:Nope .\n$data", reason)
            }
        }
    }

    @Test
    fun `a shared list is charged at every use`() {
        val n = EmbeddedShapesBounds.MAX_LIST_LENGTH
        val list = (0 until n).joinToString("\n") { "_:n$it rdf:first \"v$it\" ; rdf:rest ${if (it == n - 1) "rdf:nil" else "_:n${it + 1}"} ." }
        val users = { count: Int -> (0 until count).joinToString("\n") { "ex:S$it sh:targetNode ex:a ; sh:in _:n0 ." } }
        refused(users(6) + "\n" + list + "\n" + data, "unfolded into a tree")
        admitted { check(users(4) + "\n" + list + "\n" + data) }
    }

    @Test
    fun `too many SHACL statements are refused, data statements are not counted`() {
        val many = (0 until EmbeddedShapesBounds.MAX_SHAPE_TRIPLES + 1).joinToString("\n") { "ex:X$it sh:name \"n\" ." }
        refused(many + "\n" + data, "statements in the SHACL namespace")
        val dataOnly = (0 until EmbeddedShapesBounds.MAX_SHAPE_TRIPLES + 1).joinToString("\n") { "ex:X$it ex:p \"n\" ." }
        admitted { check("ex:S sh:targetNode ex:a ; sh:class ex:C .\n$dataOnly") }
    }

    @Test
    fun `realistic shapes are admitted`() {
        val shapes = """
            ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ; sh:closed true ; sh:ignoredProperties ( rdf:type ) ;
              sh:property [ sh:path ex:name ; sh:datatype <http://www.w3.org/2001/XMLSchema#string> ; sh:minCount 1 ; sh:maxCount 1 ; sh:name "name" ] ;
              sh:property [ sh:path ex:email ; sh:nodeKind sh:Literal ; sh:maxLength 200 ] ;
              sh:property [ sh:path ex:address ; sh:node ex:AddressShape ; sh:maxCount 3 ] ;
              sh:property [ sh:path ex:knows ; sh:class ex:Person ; sh:node ex:PersonShape ] ;
              sh:property [ sh:path ex:status ; sh:in ( "active" "inactive" "pending" ) ] ;
              sh:or ( [ sh:path ex:phone ; sh:minCount 1 ] [ sh:path ex:email ; sh:minCount 1 ] ) .
            ex:AddressShape a sh:NodeShape ;
              sh:property [ sh:path ex:street ; sh:minCount 1 ] ;
              sh:property [ sh:path ex:country ; sh:in ( ${(0 until 250).joinToString(" ") { "\"C$it\"" }} ) ] ;
              sh:property [ sh:path ( ex:geo [ sh:alternativePath ( ex:lat ex:lon ) ] ) ; sh:datatype <http://www.w3.org/2001/XMLSchema#decimal> ] .
            ex:OrgShape a sh:NodeShape ; sh:targetClass ex:Org ;
              sh:property [ sh:path ex:member ; sh:qualifiedValueShape ex:PersonShape ; sh:qualifiedMinCount 1 ; sh:qualifiedValueShapesDisjoint true ] ;
              sh:property [ sh:path ex:member ; sh:qualifiedValueShape [ sh:class ex:Bot ] ; sh:qualifiedMaxCount 2 ] ;
              sh:property [ sh:path ex:address ; sh:node ex:AddressShape ] .
        """.trimIndent()
        admitted { check(shapes + "\n" + data) }
    }
}

/** Runs [block], failing with [message] and the cause when it throws (kotlin.test; JUnit's inline helper pulls opentest4j in). */
private inline fun admitted(message: String = "expected no exception", block: () -> Unit) {
    try { block() } catch (e: Exception) { kotlin.test.fail(message, e) }
}
