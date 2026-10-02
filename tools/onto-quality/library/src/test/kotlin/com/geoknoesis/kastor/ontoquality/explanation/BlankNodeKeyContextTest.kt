package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.ontoquality.QualityCategory
import com.geoknoesis.kastor.ontoquality.QualityChecker
import com.geoknoesis.kastor.ontoquality.QualityFinding
import com.geoknoesis.kastor.ontoquality.QualityReport
import com.geoknoesis.kastor.ontoquality.QualityTier
import com.geoknoesis.kastor.ontoquality.catalog.ShapeCatalog
import com.geoknoesis.kastor.ontoquality.catalog.ShapeMetadata
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Blank-node keys identify a node by its content **and** its context, uniquely within a graph. */
class BlankNodeKeyContextTest {
    private val shapes =
        """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix ex: <http://example.org/shapes#> .
        ex:RestrictionNeedsSome a sh:NodeShape ;
            sh:targetClass owl:Restriction ;
            sh:property [ sh:path owl:someValuesFrom ; sh:minCount 1 ; sh:severity sh:Warning ; sh:message "restriction without someValuesFrom" ] .
        """.trimIndent()

    private val catalog =
        object : ShapeCatalog {
            override val id = "test"
            override val name = "test"
            override val version = "1"
            override val shapeMetadata: Map<String, ShapeMetadata> = emptyMap()

            override fun loadShapesGraph(): RdfGraph = Rdf.parse(shapes, "TURTLE")
        }

    private val prefixes =
        """
        @prefix : <http://example.org/r#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        :p a owl:ObjectProperty .
        """.trimIndent()

    private fun checker(): QualityChecker = QualityChecker.builder(ShaclValidation.validator()).addCatalog(catalog).build()

    private fun check(body: String): QualityReport = checker().check(Rdf.parse("$prefixes\n$body", "TURTLE"))

    private fun refs(report: QualityReport): List<String> = report.findings.map { FindingRef.from(it).hexSha256 }

    private fun focusKeys(report: QualityReport): List<String> =
        report.findings.map { f -> f.blankNodeKeys.getValue(f.violation.focusNode as BlankNode) }

    private fun e(local: String): Iri = Iri("http://example.org/k#$local")

    private fun allKeys(graph: RdfGraph): Map<BlankNode, String> {
        val nodes = graph.getTriples().flatMap { listOf(it.subject, it.obj) }.filterIsInstance<BlankNode>().toSet()
        return BlankNodeKeys.compute(graph, nodes)
    }

    @Test
    fun `the same restriction nested in a list under two classes gets two keys and two refs`() {
        val ontology =
            """
            :A a owl:Class ; owl:equivalentClass [ a owl:Class ; owl:intersectionOf ( :Base [ a owl:Restriction ; owl:onProperty :p ; owl:allValuesFrom :C ] ) ] .
            :B a owl:Class ; owl:equivalentClass [ a owl:Class ; owl:intersectionOf ( :Base [ a owl:Restriction ; owl:onProperty :p ; owl:allValuesFrom :C ] ) ] .
            """.trimIndent()
        val first = check(ontology)
        val second = check(ontology)
        assertEquals(2, first.findings.size, first.describeText())
        assertEquals(2, refs(first).toSet().size, "findings on the two restrictions share a ref")
        assertEquals(2, focusKeys(first).toSet().size, "the two restrictions share a key")
        assertEquals(refs(first).toSet(), refs(second).toSet(), "refs change between two parses")
        assertEquals(focusKeys(first).toSet(), focusKeys(second).toSet())
    }

    @Test
    fun `identical restrictions under one class get unique refs that are the same on every parse`() {
        val ontology =
            """
            :A a owl:Class ;
               rdfs:subClassOf [ a owl:Restriction ; owl:onProperty :p ; owl:allValuesFrom :C ] ,
                               [ a owl:Restriction ; owl:onProperty :p ; owl:allValuesFrom :C ] ,
                               [ a owl:Restriction ; owl:onProperty :p ; owl:allValuesFrom :C ] .
            """.trimIndent()
        val first = check(ontology)
        val second = check(ontology)
        assertEquals(3, first.findings.size, first.describeText())
        assertEquals(3, refs(first).toSet().size, "refs are not unique within the report")
        assertEquals(refs(first).toSet(), refs(second).toSet())
        assertTrue(first.findings.all { it.category == QualityCategory.UNCATEGORIZED && it.tier == QualityTier.STRUCTURAL })
    }

    @Test
    fun `lists are described in full, whatever their length`() {
        fun list(last: Int): String = (1..39).joinToString(" ") + " $last"
        val graph =
            Rdf.parse(
                "@prefix : <http://example.org/k#> .\n:s :p [ :items ( ${list(40)} ) ] , [ :items ( ${list(41)} ) ] .\n",
                "TURTLE",
            )
        val holders = graph.getTriples().filter { it.predicate == e("p") }.map { it.obj as BlankNode }
        assertEquals(2, holders.size)
        val keys = BlankNodeKeys.compute(graph, holders.toSet())
        assertNotEquals(keys.getValue(holders[0]), keys.getValue(holders[1]), "lists differing in their 40th member share a key")
        assertTrue(keys.values.none { it.contains('-') }, "distinct lists need no ordinal: $keys")
    }

    @Test
    fun `keys do not depend on triple order, even for very large nodes`() {
        val node = BlankNode("big")
        val triples =
            listOf(RdfTriple(e("s"), e("p"), node)) + (1..12_000).map { RdfTriple(node, e("member"), e("m$it")) }
        val forward = BlankNodeKeys.compute(MemoryGraph(triples), setOf(node))
        val backward = BlankNodeKeys.compute(MemoryGraph(triples.reversed()), setOf(node))
        assertEquals(forward, backward)
    }

    @Test
    fun `triple terms containing blank nodes are keyed without their labels`() {
        fun graph(x: String, y: String): Pair<RdfGraph, BlankNode> {
            val holder = BlankNode(x)
            val inner = BlankNode(y)
            val quoted = TripleTerm(RdfTriple(inner, e("q"), Literal("v", XSD.string)))
            return MemoryGraph(
                listOf(
                    RdfTriple(e("s"), e("p"), holder),
                    RdfTriple(holder, e("says"), quoted),
                    RdfTriple(inner, e("r"), Literal("1", XSD.string)),
                ),
            ) to holder
        }
        val (g1, n1) = graph("a1", "a2")
        val (g2, n2) = graph("zz9", "yy8")
        assertEquals(BlankNodeKeys.compute(g1, setOf(n1)).getValue(n1), BlankNodeKeys.compute(g2, setOf(n2)).getValue(n2))
    }

    @Test
    fun `literal text cannot imitate the description of other triples`() {
        val xsdString = "http://www.w3.org/2001/XMLSchema#string"
        val one = BlankNode("one")
        val two = BlankNode("two")
        val forged = "a\"^^<$xsdString>;<${e("q").value}> \"b"
        val graph =
            MemoryGraph(
                listOf(
                    RdfTriple(e("s"), e("p"), one),
                    RdfTriple(e("s"), e("p"), two),
                    RdfTriple(one, e("p"), Literal(forged, XSD.string)),
                    RdfTriple(two, e("p"), Literal("a", XSD.string)),
                    RdfTriple(two, e("q"), Literal("b", XSD.string)),
                ),
            )
        val keys = BlankNodeKeys.compute(graph, setOf(one, two))
        assertNotEquals(keys.getValue(one).substringBefore('-'), keys.getValue(two).substringBefore('-'))
    }

    @Test
    fun `cyclic and shared lists are keyed without looping`() {
        val a = BlankNode("a")
        val b = BlankNode("b")
        val h = BlankNode("h")
        val graph =
            MemoryGraph(
                listOf(
                    RdfTriple(e("s"), e("p"), h),
                    RdfTriple(h, RDF.first, e("x")),
                    RdfTriple(h, RDF.rest, a),
                    RdfTriple(a, RDF.first, e("y")),
                    RdfTriple(a, RDF.rest, b),
                    RdfTriple(b, RDF.first, e("z")),
                    RdfTriple(b, RDF.rest, a),
                ),
            )
        val keys = allKeys(graph)
        assertEquals(3, keys.values.toSet().size, keys.toString())
    }

    @Test
    fun `blank node labels interpolated in a message do not reach the ref`() {
        fun finding(label: String, message: String): QualityFinding {
            val node = BlankNode(label)
            val violation =
                ValidationViolation(
                    severity = ViolationSeverity.WARNING,
                    constraint = ShaclConstraint(ConstraintType.MIN_COUNT, severity = ViolationSeverity.WARNING),
                    focusNode = node,
                    message = message,
                )
            return QualityFinding(violation, QualityCategory.UNCATEGORIZED, null, QualityTier.STRUCTURAL, mapOf(node to "_:k0123"))
        }
        val first = finding("b42x7", "Node _:b42x7 (b42x7) lacks a filler; b42x7y is another word")
        val second = finding("genid99", "Node _:genid99 (genid99) lacks a filler; b42x7y is another word")
        // Only the reference is replaced; the bare label is ordinary text (no SHACL engine writes a blank node that way).
        assertEquals("Node _:k0123 (b42x7) lacks a filler; b42x7y is another word", first.stableMessage)
        assertEquals(first.stableMessage.replace("(b42x7)", "(genid99)"), second.stableMessage)
        fun referenceOnly(f: QualityFinding): QualityFinding =
            f.copy(violation = f.violation.copy(message = "Node ${f.violation.focusNode} lacks a filler"))
        assertEquals(FindingRef.from(referenceOnly(first)), FindingRef.from(referenceOnly(second)))
        val plain: RdfTerm = Iri("http://example.org/A")
        val onIri = QualityFinding(first.violation.copy(focusNode = plain), QualityCategory.UNCATEGORIZED, null, QualityTier.STRUCTURAL)
        assertEquals(first.violation.message, onIri.stableMessage)
    }

    @Test
    fun `an explanation failure carries its cause outside its value`() {
        val cause = java.io.IOException("HTTP 401")
        val failure = ExplanationFailure(emptyList(), "LLM request failed", cause)
        assertEquals(cause, failure.cause)
        assertEquals(ExplanationFailure(emptyList(), "LLM request failed"), failure)
        assertTrue(!failure.toString().contains("IOException"), failure.toString())
    }
}
