package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.FalseLiteral
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.shacl.providers.ProvisionalStash
import com.geoknoesis.kastor.rdf.shacl.providers.ProvisionalUndefined
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * SHACL 1.2 Node Expressions §7 (`sh:expression`, computed `sh:nodeByExpression`) and the full node expression
 * library at the validation positions `sh:values`, `sh:defaultValue` and `sh:targetNode`.
 */
class NodeExpressionConstraintTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .
        @prefix sparql: <http://www.w3.org/ns/sparql#> .
        @prefix ex: <http://example.com/ns#> .
    """.trimIndent()

    private fun g(ttl: String) = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)
    private fun ex(local: String) = Iri("http://example.com/ns#$local")
    private fun int(n: Int) = TypedLiteral(n.toString(), XSD.integer)

    private fun repository(provider: String): RdfRepository =
        when (provider) {
            "jena" -> JenaProvider().createRepository("memory", RdfConfig())
            else -> Rdf4jProvider().createRepository("memory", RdfConfig())
        }

    private fun validate(provider: String, data: String, shapes: String, config: ValidationConfig = ValidationConfig.default()) =
        NativeShaclValidator(config) { repository(provider) }.validate(g(data), g(shapes))

    /** The shapes graph validated against itself, as the W3C cases do. */
    private fun validateSelf(provider: String, ttl: String) = g(ttl).let { NativeShaclValidator(ValidationConfig.default()) { repository(provider) }.validate(it, it) }

    // --- sh:expression (§7.1) ---------------------------------------------------------------------------------------

    private val iban = """
        ex:AccountShape a sh:NodeShape ; sh:targetClass ex:Account ; sh:property ex:AccountShape-ibanNumber .
        ex:AccountShape-ibanNumber a sh:PropertyShape ;
            sh:path ex:ibanNumber ;
            sh:datatype xsd:string ;
            sh:message "IBAN numbers must start with the country code, in upper-case letters." ;
            sh:expression [
                sparql:strstarts (
                    [ shnex:var "value" ]
                    [ sparql:ucase ( [ shnex:pathValues ( ex:country ex:code ) ] ) ]
                )
            ] .
    """

    private val accounts = """
        ex:ValidGermanAccount a ex:Account ; ex:ibanNumber "DE123456..." ; ex:country ex:Germany .
        ex:InvalidGermanAccount a ex:Account ; ex:ibanNumber "DE987654..." ; ex:country ex:Estonia .
        ex:Estonia a ex:Country ; ex:code "ee" .
        ex:Germany a ex:Country ; ex:code "de" .
    """

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `spec example 29 checks IBAN numbers against the country code`(provider: String) {
        val report = validate(provider, accounts, iban)
        val v = report.violations.single()
        assertEquals(ex("InvalidGermanAccount"), v.focusNode)
        assertEquals(TypedLiteral("DE987654...", XSD.string), v.value)
        assertEquals(ConstraintType.EXPRESSION, v.constraint.constraintType)
        assertEquals(Iri(SHACL.namespace + "ExpressionConstraintComponent"), ConstraintType.EXPRESSION.toSourceConstraintComponentIri())
        assertTrue(v.sourceConstraint is BlankNode, "sh:sourceConstraint is the expression: ${v.sourceConstraint}")
        assertEquals(listOf("IBAN numbers must start with the country code, in upper-case letters."), v.resultMessages.map { it.lexical })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `W3C expression-001 sh expression false at a node shape`(provider: String) {
        val report = validateSelf(
            provider,
            """
            ex:InvalidInstance rdf:type ex:TestShape ; rdfs:label "Invalid instance" .
            ex:TestShape rdf:type rdfs:Class , sh:NodeShape ; rdfs:label "Test shape" ; sh:expression false .
            """,
        )
        val v = report.violations.single()
        assertEquals(ex("InvalidInstance"), v.focusNode)
        assertEquals(ex("InvalidInstance"), v.value)
        assertEquals(FalseLiteral, v.sourceConstraint)
        assertEquals(ConstraintType.EXPRESSION, v.constraint.constraintType)
        assertEquals("http://example.com/ns#TestShape", v.shapeUri)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `only exactly the term true passes an expression constraint`(provider: String) {
        // R5: "1"^^xsd:boolean is not the term true; ( true true ) and ( ) are not the list ( true ).
        val outputs = mapOf(
            "true" to true,
            "\"true\"^^xsd:boolean" to true,
            "\"1\"^^xsd:boolean" to false,
            "( true true )" to false,
            "[]" to false,
            "\"true\"" to false,
        )
        for ((expr, passes) in outputs) {
            val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:expression $expr ."
            val report = validate(provider, "ex:a ex:p 1 .", shapes)
            assertEquals(passes, report.isValid, "$expr: ${report.violations}")
            if (!passes) assertEquals(ConstraintType.EXPRESSION, report.violations.single().constraint.constraintType, expr)
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `an expression constraint gives one result per failing value node`(provider: String) {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; sh:expression [ sparql:less-than ( [ shnex:var "value" ] 5 ) ] ] .
        """
        val report = validate(provider, "ex:a ex:p 1 , 7 , 9 .", shapes)
        assertEquals(setOf<RdfTerm>(int(7), int(9)), report.violations.map { it.value }.toSet())
        assertTrue(report.violations.all { it.focusNode == ex("a") && it.constraint.constraintType == ConstraintType.EXPRESSION })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `the focus node of an expression constraint on a property shape is the shape's focus node`(provider: String) {
        // §7.1: evalExpr($expr, data graph, focusNode, {"value": v}).
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; sh:expression [ sparql:equals ( [ shnex:var "focusNode" ] ex:a ) ] ] .
        """
        assertTrue(validate(provider, "ex:a ex:p ex:b , ex:c .", shapes).isValid)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `an expression constraint works inside sh not and sh or`(provider: String) {
        val small = """[ sh:path ex:p ; sh:expression [ sparql:less-than ( [ shnex:var "value" ] 5 ) ] ]"""
        val not = "ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:not $small ."
        val notReport = validate(provider, "ex:a ex:p 1 . ex:b ex:p 9 .", not)
        assertEquals(listOf(ex("a")), notReport.violations.map { it.focusNode })
        assertEquals(ConstraintType.NOT, notReport.violations.single().constraint.constraintType)

        val or = "ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:or ( $small [ sh:path ex:p ; sh:hasValue 9 ] ) ."
        val orReport = validate(provider, "ex:a ex:p 1 . ex:b ex:p 7 .", or)
        assertEquals(listOf(ex("b")), orReport.violations.map { it.focusNode })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a failing expression constraint fails validation`(provider: String) {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:expression [ sparql:abs ( ( 1 2 ) ) ] ."
        assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:p 1 .", shapes) }
        // Also when the expression is only checked for conformance (inside sh:not).
        val nested = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:not [ sh:expression [ sparql:abs ( ( 1 2 ) ) ] ] ."
        assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:p 1 .", nested) }
    }

    /** The triples of [graph] reachable from [node] through blank-node objects (a deep copy's triples). */
    private fun deepCopyIn(graph: com.geoknoesis.kastor.rdf.RdfGraph, node: RdfTerm): List<com.geoknoesis.kastor.rdf.RdfTriple> {
        val bySubject = graph.getTriples().groupBy { it.subject }
        val out = ArrayList<com.geoknoesis.kastor.rdf.RdfTriple>()
        val seen = HashSet<RdfTerm>()
        val pending = ArrayDeque<RdfTerm>().apply { add(node) }
        while (pending.isNotEmpty()) {
            val n = pending.removeFirst()
            if (n !is BlankNode || !seen.add(n)) continue
            for (t in bySubject[n].orEmpty()) { out.add(t); pending.add(t.obj) }
        }
        return out
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `the source constraint of an expression result is a deep copy of the expression`(provider: String) {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; sh:expression [ sparql:less-than ( [ shnex:var "value" ] 5 ) ] ] .
        """
        val report = validate(provider, "ex:a ex:p 1 , 7 , 9 .", shapes)
        assertEquals(2, report.violations.size)
        val rdf = report.toShaclValidationReportRdf()
        val sources = rdf.getTriples().filter { it.predicate == SHACL.sourceConstraint }.map { it.obj }
        assertEquals(2, sources.size)
        for (source in sources) {
            assertTrue(source is BlankNode, "$source")
            val copy = deepCopyIn(rdf, source)
            // [ sparql:less-than ( [ shnex:var "value" ] 5 ) ]: the call, two list cells and the variable expression.
            val lessThan = copy.single { it.subject == source }
            assertEquals(Iri("http://www.w3.org/ns/sparql#less-than"), lessThan.predicate)
            val firstCell = lessThan.obj
            val varExpr = copy.single { it.subject == firstCell && it.predicate == com.geoknoesis.kastor.rdf.vocab.RDF.first }.obj
            assertEquals("value", (copy.single { it.subject == varExpr }.obj as com.geoknoesis.kastor.rdf.Literal).lexical)
            val secondCell = copy.single { it.subject == firstCell && it.predicate == com.geoknoesis.kastor.rdf.vocab.RDF.rest }.obj
            assertEquals(int(5), copy.single { it.subject == secondCell && it.predicate == com.geoknoesis.kastor.rdf.vocab.RDF.first }.obj)
            assertEquals(com.geoknoesis.kastor.rdf.vocab.RDF.nil, copy.single { it.subject == secondCell && it.predicate == com.geoknoesis.kastor.rdf.vocab.RDF.rest }.obj)
            assertEquals(6, copy.size)
        }
        // The report's own blank nodes are not reused for the copy.
        val resultNodes = rdf.getTriples().filter { it.predicate == SHACL.result }.map { it.obj }.toSet()
        assertTrue(sources.none { it in resultNodes })
    }

    @Test
    fun `deep copies of many expressions that share a large structure are written once`() {
        // 60 distinct expressions share _:count, whose list has 5,000 members (10,000 triples). Copied per root, that
        // would be 600,000 triples from a shapes graph of about 11,000; each blank node is described once instead.
        val members = (1..5_000).joinToString(" ")
        val shapes = buildString {
            append("_:count shnex:count ( $members ) .\n")
            append("ex:S a sh:NodeShape ; sh:targetNode ex:a")
            repeat(60) { append(" ;\n  sh:property [ sh:path ex:p ; sh:expression [ sparql:equals ( _:count -$it ) ] ]") }
            append(" .\n")
        }
        val started = System.nanoTime()
        // Validation copies nothing: the copies are written only by toShaclValidationReportRdf.
        val report = validate("jena", "ex:a ex:p 1 .", shapes)
        assertEquals(60, report.violations.size)
        assertTrue(report.violations.none { v -> v.context.values.any { it is List<*> } }, "no triples are held per result")
        val rdf = report.toShaclValidationReportRdf()
        assertTrue(System.nanoTime() - started < 60_000_000_000L)
        val sources = rdf.getTriples().filter { it.predicate == SHACL.sourceConstraint }.map { it.obj }.toSet()
        assertEquals(60, sources.size)
        // Each copy is complete (the call, two list cells, _:count and its list), and the shared part is written once.
        val written = HashSet<com.geoknoesis.kastor.rdf.RdfTriple>()
        for (source in sources) {
            val copy = deepCopyIn(rdf, source)
            assertEquals(10_006, copy.size)
            written.addAll(copy)
        }
        assertEquals(60 * 5 + 10_001, written.size)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `the deep copy of a cyclic blank node shape terminates`(provider: String) {
        // The shapes graph is its own data graph, so the expression can output the blank node shape itself; the shape
        // refers back to itself through a blank node property shape (a cycle of blank nodes).
        val ttl = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:nodeByExpression [ shnex:pathValues ex:shape ] .
            ex:a ex:shape _:s .
            _:s a sh:NodeShape ; sh:property _:p .
            _:p sh:path ex:next ; sh:node _:s ; sh:minCount 1 .
        """
        val report = validateSelf(provider, ttl)
        val v = report.violations.single { it.constraint.constraintType == ConstraintType.NODE_BY_EXPRESSION }
        assertTrue(v.sourceConstraint is BlankNode)
        val rdf = report.toShaclValidationReportRdf()
        val source = rdf.getTriples().single { it.predicate == SHACL.sourceConstraint }.obj
        val copy = deepCopyIn(rdf, source)
        // _:s a sh:NodeShape ; sh:property _:p . _:p sh:path ; sh:node _:s (the cycle, kept) ; sh:minCount.
        assertEquals(5, copy.size, copy.toString())
        val p = copy.single { it.subject == source && it.predicate == SHACL.`property` }.obj
        assertEquals(source, copy.single { it.subject == p && it.predicate == SHACL.node }.obj)
    }

    // --- sh:nodeByExpression (§7.2) ---------------------------------------------------------------------------------

    private val dataCubeShapes = """
        ex:dsd1-shape a sh:NodeShape ;
            sh:property [ sh:path ex:refTime ; sh:datatype xsd:date ; sh:minCount 1 ; sh:maxCount 1 ] ;
            sh:property [ sh:path ex:quantity ; sh:datatype xsd:integer ; sh:minCount 1 ; sh:maxCount 1 ; sh:minInclusive 0 ] .
        ex:ObservationShape a sh:NodeShape ;
            sh:targetClass ex:Observation ;
            sh:nodeByExpression [ shnex:pathValues ( ex:dataSet ex:structure ex:hasShape ) ] .
    """

    private val observations = """
        ex:dsd1 ex:hasShape ex:dsd1-shape .
        ex:dataset1 ex:structure ex:dsd1 .
        ex:o1 a ex:Observation ; ex:dataSet ex:dataset1 ; ex:refTime "2024-01-01"^^xsd:date ; ex:quantity 3 .
        ex:o2 a ex:Observation ; ex:dataSet ex:dataset1 ; ex:refTime "2024-01-02"^^xsd:date ; ex:quantity -1 .
        ex:o3 a ex:Observation .
    """

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `spec example 30 validates observations against the shape of their structure`(provider: String) {
        val report = validate(provider, observations, dataCubeShapes)
        val v = report.violations.single()
        assertEquals(ex("o2"), v.focusNode)
        assertEquals(ex("o2"), v.value)
        assertEquals(ConstraintType.NODE_BY_EXPRESSION, v.constraint.constraintType)
        assertEquals(ex("dsd1-shape"), v.sourceConstraint)
        assertEquals("http://example.com/ns#ObservationShape", v.shapeUri)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `computed nodeByExpression on a property shape checks every value node`(provider: String) {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; sh:nodeByExpression [ shnex:pathValues ex:shape ] ] .
            ex:Named a sh:NodeShape ; sh:property [ sh:path ex:name ; sh:minCount 1 ] .
        """
        val data = "ex:a ex:p ex:b , ex:c . ex:b ex:shape ex:Named ; ex:name \"b\" . ex:c ex:shape ex:Named ."
        val v = validate(provider, data, shapes).violations.single()
        assertEquals(ex("a"), v.focusNode)
        assertEquals(ex("c"), v.value)
        assertEquals(ConstraintType.NODE_BY_EXPRESSION, v.constraint.constraintType)
        assertEquals(ex("Named"), v.sourceConstraint)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a computed nodeByExpression output that is not a shape fails validation`(provider: String) {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:nodeByExpression [ shnex:pathValues ex:shape ] ."
        for (data in listOf("ex:a ex:shape ex:NotAShape .", "ex:a ex:shape \"literal\" .")) {
            assertThrows(ShaclValidationException::class.java, { validate(provider, data, shapes) }, data)
        }
        // No output nodes: nothing to conform to.
        assertTrue(validate(provider, "ex:a ex:p 1 .", shapes).isValid)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `computed nodeByExpression inside sh not is checked for conformance`(provider: String) {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:not [ sh:nodeByExpression [ shnex:pathValues ex:shape ] ] .
            ex:Named a sh:NodeShape ; sh:property [ sh:path ex:name ; sh:minCount 1 ] .
        """
        val data = "ex:a ex:shape ex:Named ; ex:name \"a\" . ex:b ex:shape ex:Named ."
        assertEquals(listOf(ex("a")), validate(provider, data, shapes).violations.map { it.focusNode })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `W3C nodeByExpression-001 a constant shape IRI behaves like sh node`(provider: String) {
        val report = validateSelf(
            provider,
            """
            ex:AssignedToShape a sh:NodeShape ;
              sh:property [ sh:path ex:email ; sh:maxCount 1 ; sh:minCount 1 ] ;
              sh:property [ sh:path ex:lastName ; sh:maxCount 1 ; sh:minCount 1 ] .
            ex:Issue a rdfs:Class , sh:NodeShape ; sh:property ex:Issue-assignedTo .
            ex:Issue-assignedTo sh:path ex:assignedTo ; sh:nodeByExpression ex:AssignedToShape .
            ex:Issue_1 a ex:Issue ; ex:assignedTo ex:Anon .
            ex:Issue_2 a ex:Issue ; ex:assignedTo ex:John .
            ex:John ex:email "john@doe.com" ; ex:lastName "Doe" .
            """,
        )
        val v = report.violations.single()
        assertEquals(ex("Issue_1"), v.focusNode)
        assertEquals(ex("AssignedToShape"), v.sourceConstraint)
    }

    // --- validation positions with the full library -----------------------------------------------------------------

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `failing sh values expression fails validation`(provider: String) {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:q ; sh:values [ sparql:abs ( ( 1 2 ) ) ] ] ."
        assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:p 1 .", shapes) }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `shnex at sh values and sh targetNode`(provider: String) {
        val values = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:q ; sh:values [ shnex:count [ shnex:pathValues ex:p ] ] ; sh:hasValue 3 ; sh:maxCount 1 ] .
        """
        assertTrue(validate(provider, "ex:a ex:p 1 , 2 , 3 .", values).isValid)
        assertEquals(ConstraintType.HAS_VALUE, validate(provider, "ex:a ex:p 1 , 2 .", values).violations.single().constraint.constraintType)

        val target = "ex:S a sh:NodeShape ; sh:targetNode [ shnex:instancesOf ex:C ] ; sh:property [ sh:path ex:p ; sh:minCount 1 ] ."
        val report = validate(provider, "ex:a a ex:C . ex:b a ex:D . ex:D rdfs:subClassOf ex:C . ex:c a ex:C ; ex:p 1 . ex:z ex:q 1 .", target)
        assertEquals(setOf<RdfTerm>(ex("a"), ex("b")), report.violations.map { it.focusNode }.toSet())
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `shnex default value`(provider: String) {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ;
              sh:property [ sh:path ex:q ; sh:defaultValue [ shnex:count [ shnex:pathValues ex:p ] ] ; sh:minInclusive 1 ] .
        """
        // ex:a has no ex:q, so its value node is the count of ex:p (0); ex:b has ex:q 5.
        val report = validate(provider, "ex:b ex:q 5 .", shapes)
        assertEquals(listOf(ex("a")), report.violations.map { it.focusNode })
        assertEquals(int(0), report.violations.single().value)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a shape referenced only by an expression is compiled`(provider: String) {
        // ex:Big is no shape of its own (no type, no target): only the expressions name it.
        val big = "ex:Big sh:minInclusive 10 ."
        val values = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:q ; sh:values [ shnex:filterShape ex:Big ; shnex:nodes ( 1 20 30 ) ] ; sh:minCount 2 ; sh:maxCount 2 ] .
            $big
        """
        assertTrue(validate(provider, "ex:a ex:p 1 .", values).isValid)

        val expression = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; sh:expression [ shnex:exists [ shnex:filterShape ex:Big ; shnex:nodes [ shnex:var "value" ] ] ] ] .
            $big
        """
        assertEquals(listOf<RdfTerm?>(int(3)), validate(provider, "ex:a ex:p 3 , 12 .", expression).violations.map { it.value })

        val nodeBy = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; sh:nodeByExpression [ shnex:if [ shnex:exists [ shnex:filterShape ex:Big ; shnex:nodes ( 1 ) ] ] ; shnex:else ex:Small ] ] .
            ex:Small sh:maxInclusive 5 .
            $big
        """
        assertEquals(listOf<RdfTerm?>(int(12)), validate(provider, "ex:a ex:p 3 , 12 .", nodeBy).violations.map { it.value })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `an expression constraint may validate against its own shape`(provider: String) {
        // ex:S is recursive through its sh:expression: each ex:next value must conform to ex:S, and have an ex:name.
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:name ; sh:minCount 1 ] ;
              sh:property [ sh:path ex:next ; sh:expression [ shnex:conformsToShape ( [ shnex:var "value" ] ex:S ) ] ] .
        """
        assertTrue(validate(provider, "ex:a ex:name 1 ; ex:next ex:b . ex:b ex:name 2 ; ex:next ex:c . ex:c ex:name 3 .", shapes).isValid)
        val report = validate(provider, "ex:a ex:name 1 ; ex:next ex:b . ex:b ex:name 2 ; ex:next ex:c .", shapes)
        assertEquals(listOf<RdfTerm?>(ex("b")), report.violations.map { it.value })
        // A cycle in the data: an expression is not known to be monotone, so its shape reads are negative ones and
        // a cycle through them is undefined recursion, which is a validation failure for an expression.
        assertThrows(ShaclValidationException::class.java) {
            validate(provider, "ex:a ex:name 1 ; ex:next ex:b . ex:b ex:name 2 ; ex:next ex:a .", shapes)
        }
    }

    // --- R8: candidate shapes of computed shape references ----------------------------------------------------------

    private val unrelatedBadShapes = """
        ex:Good a sh:NodeShape ; sh:property [ sh:path ex:name ; sh:minCount 1 ] .
        ex:Malformed sh:datatype "not a datatype IRI" .
        ex:Unsupported sh:nodeKind sh:IRI ; sh:expression [ ex:undeclaredFunction 1 ] .
    """

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `an unrelated malformed or unsupported shape does not break a computed shape reference`(provider: String) {
        val nodeBy = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:nodeByExpression [ shnex:pathValues ex:shape ] .\n$unrelatedBadShapes"
        val conformsTo = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:expression [ shnex:conformsToShape ( [ shnex:var "focusNode" ] [ shnex:pathValues ex:shape ] ) ] .
            $unrelatedBadShapes
        """
        for (shapes in listOf(nodeBy, conformsTo)) {
            // Unused: the bad shapes are never validated against.
            assertTrue(validate(provider, "ex:a ex:shape ex:Good ; ex:name \"a\" .", shapes).isValid, shapes)
            assertEquals(1, validate(provider, "ex:a ex:shape ex:Good .", shapes).violations.size, shapes)
            // Used: an evaluation failure, which is a validation failure.
            for (bad in listOf("Malformed", "Unsupported")) {
                val error = assertThrows(ShaclValidationException::class.java, { validate(provider, "ex:a ex:shape ex:$bad .", shapes) }, "$bad: $shapes")
                assertTrue(error.message.orEmpty().contains(bad), "$bad: ${error.message}")
            }
        }
    }

    @Test
    fun `an unsupported construct of a used candidate shape is a warning under IGNORE_WITH_WARNING`() {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:nodeByExpression [ shnex:pathValues ex:shape ] .\n$unrelatedBadShapes"
        val ignore = ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING)
        val report = validate("jena", "ex:a ex:shape ex:Unsupported .", shapes, ignore)
        assertTrue(report.isValid, report.violations.toString())
        val warning = report.warnings.map { it.message }.single { "Unsupported SHACL feature ignored" in it }
        assertTrue("sh:expression constraint on" in warning && "Unsupported" in warning, warning)
    }

    // --- recursion through node expressions (fix round 1) -----------------------------------------------------------

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a provisional undefined answer read by an expression is settled by the solver whatever the order`(provider: String) {
        // (a,S) reads (b,S) through sh:expression; (b,S) reads (a,S) through sh:not and fails its own sh:minCount.
        for (targets in listOf("ex:a , ex:b", "ex:b , ex:a")) {
            for (order in listOf(0, 1)) {
                val expression = "sh:property [ sh:path ex:next ; sh:expression [ shnex:conformsToShape ( [ shnex:var \"value\" ] ex:S ) ] ]"
                val not = "sh:property [ sh:path ex:prev ; sh:not ex:S ]"
                val parts = if (order == 0) "$expression ; $not" else "$not ; $expression"
                val shapes = "ex:S a sh:NodeShape ; sh:targetNode $targets ; sh:property [ sh:path ex:name ; sh:minCount 1 ] ; $parts ."
                val report = validate(provider, "ex:a ex:name 1 ; ex:next ex:b . ex:b ex:prev ex:a .", shapes)
                val found = report.violations.map { Triple(it.focusNode, it.constraint.constraintType, it.value) }.toSet()
                assertEquals(setOf(Triple(ex("a"), ConstraintType.EXPRESSION, ex("b")), Triple(ex("b"), ConstraintType.MIN_COUNT, null)), found, "$targets / $order")
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a shape read by an expression is a negative dependency`(provider: String) {
        // X conforms: Y fails its sh:minCount whatever X is, so not(conformsToShape(x, Y)) is true.
        val shapes = """
            ex:X a sh:NodeShape ; sh:targetNode ex:x ;
              sh:expression [ sparql:logical-not ( [ shnex:conformsToShape ( [ shnex:var "focusNode" ] ex:Y ) ] ) ] .
            ex:Y a sh:NodeShape ; sh:node ex:X ; sh:property [ sh:path ex:missing ; sh:minCount 1 ] .
        """
        val report = validate(provider, "ex:x ex:p 1 .", shapes)
        assertTrue(report.isValid, report.violations.toString())
        // With the sh:minCount satisfied, X conforms iff it does not: undefined recursion, a validation failure.
        assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:x ex:missing 1 .", shapes) }
    }

    @Test
    fun `recursion through a computed nodeByExpression and an expression stays bounded`() {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:nodeByExpression ( ex:T ) .
            ex:T sh:property [ sh:path ex:p ; sh:expression [ shnex:exists [ shnex:filterShape ex:S ; shnex:nodes [ shnex:var "value" ] ] ] ] .
        """
        // A data cycle: (a,S) needs (a,T), which needs (a,S) negatively: undefined, a validation failure.
        val error = assertThrows(ShaclValidationException::class.java) { validate("jena", "ex:a ex:p ex:a .", shapes) }
        assertTrue("cannot be decided" in error.message.orEmpty() || "undefined" in error.message.orEmpty(), error.message)
        // A chain ends: b has no ex:p, so it conforms to T and S, and so does a.
        assertTrue(validate("jena", "ex:a ex:p ex:b .", shapes).isValid)
        // A long chain is decided by the recursion solver ({S, T} is a component), not by nested checks: the last
        // node has no ex:p, so every node conforms. A stack overflow would be a ShaclValidationException instead.
        val chain = (0 until 300).joinToString(" ") { "ex:n$it ex:p ex:n${it + 1} ." } + " ex:a ex:p ex:n0 ."
        val report = validate("jena", chain, shapes)
        assertTrue(report.isValid, report.violations.toString())
    }

    /** `ex:f(x)`: some `ex:p` value of `x` conforms to `ex:S`, a function body SPARQL calls. */
    private val shapeReadingFunction = """
        ex:f a sh:ListParameterExpressionFunction ;
          sh:parameter [ sh:path shnex:arg0 ] ;
          sh:bodyExpression [ shnex:exists [ shnex:filterShape ex:S ; shnex:nodes [ shnex:pathValues ex:p ; shnex:focusNode [ shnex:arg 0 ] ] ] ] .
    """

    /** Shapes where `ex:S` holds for `x` iff `ex:f(x)` is false, through each way a shape runs SPARQL. */
    private val sparqlCallingShapes = listOf(
        // sh:sparql: a result (a violation) when f($this) holds.
        "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ sh:select '''SELECT ${'$'}this WHERE { FILTER (<http://example.com/ns#f>(${'$'}this)) }''' ] .",
        // A SPARQL-based constraint component.
        """
        ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:check ] ;
          sh:nodeValidator [ a sh:SPARQLSelectValidator ; sh:select '''SELECT ${'$'}this WHERE { FILTER (${'$'}check && <http://example.com/ns#f>(${'$'}this)) }''' ] .
        ex:S a sh:NodeShape ; sh:targetNode ex:a ; ex:check true .
        """,
        // A SPARQL node expression at sh:expression.
        "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:expression [ sh:sparqlExpr '''!<http://example.com/ns#f>(${'$'}this)''' ] .",
    )

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `paradoxical recursion through a SPARQL function body is undefined`(provider: String) {
        // ex:a ex:p ex:a: a conforms to S iff f(a) is false iff a does not conform to S. The function body's read of
        // S is a static dependency of every shape that runs SPARQL, so S is recursive and the solver finds the
        // answer undefined: a validation failure (R16), not a report cut off at the function call limit.
        for (shapes in sparqlCallingShapes) {
            val error = assertThrows(ShaclValidationException::class.java, { validate(provider, "ex:a ex:p ex:a .", shapeReadingFunction + shapes) }, shapes)
            assertTrue("cannot be decided" in error.message.orEmpty() || "undefined" in error.message.orEmpty(), error.message)
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `recursion through a SPARQL function body that ends is decided`(provider: String) {
        for (shapes in sparqlCallingShapes) {
            // b has no ex:p, so f(b) is false and b conforms; f(a) is then true and a does not.
            val report = validate(provider, "ex:a ex:p ex:b .", shapeReadingFunction + shapes)
            assertEquals(listOf(ex("a")), report.violations.map { it.focusNode }, shapes)
            // A longer chain alternates: n2 conforms, n1 does not, a does.
            assertTrue(validate(provider, "ex:a ex:p ex:n1 . ex:n1 ex:p ex:n2 .", shapeReadingFunction + shapes).isValid, shapes)
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a deadline that expires inside a function body of the last query fails the run`(provider: String) {
        // The budget expires while ex:f's body validates against ex:Aux, during the only (so the last) focus node of
        // the last shape (ex:Aux, a node shape without targets, is ordered first):
        // the provider turns it into a SPARQL error of the call, FILTER (!ex:f(...)) drops the row, and no later
        // check of the run would come. The query's own check on return reports the timeout instead of a report.
        val shapes = """
            ex:f a sh:ListParameterExpressionFunction ;
              sh:parameter [ sh:path shnex:arg0 ] ;
              sh:bodyExpression [ shnex:exists [ shnex:filterShape ex:Aux ; shnex:nodes [ shnex:arg 0 ] ] ] .
            ex:Aux a sh:NodeShape ; sh:nodeKind sh:IRI .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:sparql [ sh:select '''SELECT ${'$'}this WHERE { FILTER (!<http://example.com/ns#f>(${'$'}this)) }''' ] .
        """
        val config = ValidationConfig.default().copy(timeout = java.time.Duration.ofSeconds(1))
        // The clock stands still until it is first read inside a function body, then jumps past the deadline.
        var expired = false
        val clock = {
            if (!expired && Thread.currentThread().stackTrace.any { it.methodName.startsWith("runFunctionBody") }) expired = true
            if (expired) 1_000_000_000_000L else 0L
        }
        val validator = NativeShaclValidator(config) { repository(provider) }.also { it.budgetClock = clock }
        val error = assertThrows(ShaclValidationException::class.java) { validator.validate(g("ex:a ex:p 1 ."), g(shapes)) }
        assertTrue(expired, "the clock was never read inside the function body")
        assertTrue("timed out" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `an acyclic chain of shapes through expressions is bounded by maxRecursionDepth`() {
        // No shape is recursive, so only the shape depth carried through the evaluator bounds the nesting.
        val shapes = (0 until 5).joinToString("\n") {
            "ex:S$it a sh:NodeShape ; sh:expression [ shnex:conformsToShape ( [ shnex:var \"focusNode\" ] ex:S${it + 1} ) ] ."
        } + "\nex:S5 a sh:NodeShape ; sh:nodeKind sh:IRI .\nex:S0 sh:targetNode ex:a ."
        assertTrue(validate("jena", "ex:a ex:p 1 .", shapes).isValid)
        val error = assertThrows(ShaclValidationException::class.java) {
            validate("jena", "ex:a ex:p 1 .", shapes, ValidationConfig(maxRecursionDepth = 3))
        }
        assertTrue("nesting exceeded ValidationConfig.maxRecursionDepth=3" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a provisional answer stashed by a query that throws does not leak into the next query`() {
        val stash = ProvisionalStash()
        val p = ProvisionalUndefined(ex("a"), ex("S"))
        val q = ProvisionalUndefined(ex("b"), ex("S"))
        // The provider throws after a function body stashed: the stash is discarded with the query.
        assertThrows(IllegalStateException::class.java) { stash.scoped<Unit> { stash.stash(p); throw IllegalStateException("provider") } }
        assertEquals("next", stash.scoped { "next" })
        // A query that returns rethrows its own stashed answer.
        assertSame(p, assertThrows(ProvisionalUndefined::class.java) { stash.scoped { stash.stash(p); "x" } })
        // Nested queries keep their own: an inner query neither takes nor clobbers the outer one's.
        val outer = assertThrows(ProvisionalUndefined::class.java) {
            stash.scoped {
                stash.stash(p)
                assertEquals("inner", stash.scoped { "inner" })
                runCatching { stash.scoped<Unit> { stash.stash(q); throw IllegalStateException("inner provider") } }
                "outer"
            }
        }
        assertSame(p, outer)
        assertEquals("after", stash.scoped { "after" })
    }

    // --- still unsupported ------------------------------------------------------------------------------------------

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `an unsupported construct of a shape only a SPARQL-called function body names is reported`(provider: String) {
        // ex:T is untyped, has no target and is named by nothing but the body of ex:f, which the SPARQL constraint of
        // ex:S calls: it is still validated against, so its unsupported constructs must not be dropped silently.
        fun shapes(t: String) = """
            ex:f a sh:ListParameterExpressionFunction ;
              sh:parameter [ sh:path shnex:arg0 ] ;
              sh:bodyExpression [ shnex:exists [ shnex:filterShape ex:T ; shnex:nodes [ shnex:arg 0 ] ] ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:sparql [ sh:select '''SELECT ${'$'}this WHERE { FILTER (!<http://example.com/ns#f>(${'$'}this)) }''' ] .
            $t
        """
        val cases = mapOf(
            "ex:T sh:expression [ ex:unknown 1 ] ." to UnsupportedShaclFeature.NODE_EXPRESSION,
            "ex:T sh:nodeKind sh:IRI ; sh:deactivated [ ex:unknown 1 ] ." to UnsupportedShaclFeature.NODE_EXPRESSION,
            "ex:T sh:nodeKind sh:IRI ; sh:target [ a ex:CustomTarget ] ." to UnsupportedShaclFeature.CUSTOM_TARGET,
        )
        for ((t, feature) in cases) {
            val error = assertThrows(ShaclValidationException::class.java, { validate(provider, "ex:a ex:p 1 .", shapes(t)) }, t)
            val unsupported = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<UnsupportedShaclFeatureException>().firstOrNull()
            assertEquals(setOf(feature), unsupported?.features, "$t: ${error.message}")
        }
        // A body that computes its shape: every candidate shape is validated against, so the same holds for them.
        val computed = """
            ex:f a sh:ListParameterExpressionFunction ;
              sh:parameter [ sh:path shnex:arg0 ] ;
              sh:bodyExpression [ shnex:conformsToShape ( [ shnex:arg 0 ] [ shnex:pathValues ex:shape ; shnex:focusNode [ shnex:arg 0 ] ] ) ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:sparql [ sh:select '''SELECT ${'$'}this WHERE { FILTER (!<http://example.com/ns#f>(${'$'}this)) }''' ] .
            ex:T sh:nodeKind sh:IRI ; sh:expression [ ex:unknown 1 ] .
        """
        val ignore = ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING)
        val report = validate(provider, "ex:a ex:shape ex:T .", computed, ignore)
        assertTrue(report.warnings.any { "Unsupported SHACL feature ignored" in it.message && "ns#T>" in it.message }, report.warnings.toString())
        // By default, a used candidate with an unsupported construct fails the expression (R8); through the SPARQL
        // call that is a failure of the query, not an error that FILTER would turn into a conforming answer.
        val error = assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:shape ex:T .", computed) }
        assertTrue("ns#T>" in error.message.orEmpty() && "sh:expression constraint" in error.message.orEmpty(), error.message)
        // Unused (no ex:shape, so the call is a SPARQL error and FILTER drops the row), it fails nothing.
        assertTrue(validate(provider, "ex:a ex:p 1 .", computed).isValid)
    }

    // --- non-constant sh:deactivated (SHACL 1.2 Core §3.1.6) -----------------------------------------------------------

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a node shape is deactivated for the focus nodes whose expression is true`(provider: String) {
        // evalExpr(expr, data graph, focus node, {}): ex:a has ex:skip, so ex:S is deactivated for it only.
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:deactivated [ shnex:exists [ shnex:pathValues ex:skip ] ] ; sh:class ex:C ."
        assertEquals(listOf(ex("b")), validate(provider, "ex:a ex:skip 1 . ex:b ex:p 1 .", shapes).violations.map { it.focusNode })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `only exactly true deactivates a shape`(provider: String) {
        // The output of shnex:pathValues ex:flag: ( true ) deactivates; ( false ), ( ), ( "1"^^xsd:boolean ) and
        // two nodes do not.
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:deactivated [ shnex:pathValues ex:flag ] ; sh:class ex:C ."
        assertTrue(validate(provider, "ex:a ex:flag true .", shapes).isValid)
        for (data in listOf("ex:a ex:flag false .", "ex:a ex:p 1 .", "ex:a ex:flag \"1\"^^xsd:boolean .", "ex:a ex:flag true , \"true\" .")) {
            assertEquals(1, validate(provider, data, shapes).violations.size, data)
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a property shape is deactivated for the focus nodes whose expression is true`(provider: String) {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ;
              sh:property [ sh:path ex:p ; sh:minCount 2 ; sh:deactivated [ shnex:exists [ shnex:pathValues ex:skip ] ] ] .
        """
        assertEquals(listOf(ex("b")), validate(provider, "ex:a ex:skip 1 ; ex:p 1 . ex:b ex:p 1 .", shapes).violations.map { it.focusNode })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a shape deactivated for a node conforms in nested checks`(provider: String) {
        val shapes = """
            ex:T a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:node ex:S .
            ex:S a sh:NodeShape ; sh:deactivated [ shnex:exists [ shnex:pathValues ex:skip ] ] ; sh:class ex:C .
        """
        assertEquals(listOf(ex("b")), validate(provider, "ex:a ex:skip 1 . ex:b ex:p 1 .", shapes).violations.map { it.focusNode })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a SPARQL expression at sh deactivated`(provider: String) {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:deactivated [ sh:sparqlExpr '''EXISTS { ${'$'}this <http://example.com/ns#skip> ?x }''' ] ; sh:class ex:C ."
        assertEquals(listOf(ex("b")), validate(provider, "ex:a ex:skip 1 . ex:b ex:p 1 .", shapes).violations.map { it.focusNode })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a failing sh deactivated expression fails validation`(provider: String) {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:deactivated [ sparql:abs ( ( 1 2 ) ) ] ; sh:class ex:C ."
        assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:p 1 .", shapes) }
        val property = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:deactivated [ sparql:abs ( ( 1 2 ) ) ] ] ."
        assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:p 1 .", property) }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a shape read by sh deactivated is a negative dependency`(provider: String) {
        // ex:S is deactivated for x iff x does not conform to ex:S; active, it fails sh:class: x conforms iff it does
        // not, undefined recursion, a validation failure.
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:x ; sh:class ex:C ;
              sh:deactivated [ sparql:logical-not ( [ shnex:conformsToShape ( [ shnex:var "focusNode" ] ex:S ) ] ) ] .
        """
        assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:x ex:p 1 .", shapes) }
        // Decidable: deactivated iff x conforms to ex:Y, which reads ex:S back but fails sh:minCount on its own.
        val decidable = """
            ex:S a sh:NodeShape ; sh:targetNode ex:x ; sh:class ex:C ;
              sh:deactivated [ shnex:conformsToShape ( [ shnex:var "focusNode" ] ex:Y ) ] .
            ex:Y a sh:NodeShape ; sh:node ex:S ; sh:property [ sh:path ex:missing ; sh:minCount 1 ] .
        """
        assertEquals(1, validate(provider, "ex:x ex:p 1 .", decidable).violations.size)
    }

    @Test
    fun `non-constant sh deactivated on a reifier stays unsupported`() {
        // A reifier deactivating one constraint (SHACL 1.2 Core §3.1.6) with a node expression is not evaluated.
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:class ex:C {| sh:deactivated [ shnex:exists [ shnex:pathValues ex:p ] ] |} ."
        val error = assertThrows(ShaclValidationException::class.java) { validate("jena", "ex:a ex:p 1 .", shapes) }
        val unsupported = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<UnsupportedShaclFeatureException>().first()
        assertEquals(setOf(UnsupportedShaclFeature.NODE_EXPRESSION), unsupported.features)
    }

    @Test
    fun `sh deactivated that is no node expression stays unsupported`() {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:deactivated [ ex:undeclared 1 ] ; sh:class ex:C ."
        val error = assertThrows(ShaclValidationException::class.java) { validate("jena", "ex:a ex:p 1 .", shapes) }
        val unsupported = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<UnsupportedShaclFeatureException>().first()
        assertEquals(setOf(UnsupportedShaclFeature.NODE_EXPRESSION), unsupported.features)
    }

    @Test
    fun `a custom function must be a SHACL subclass of its expression class`() {
        // Appendix A (CustomListParameterFunction-syntax, CustomNamedParameterFunction-syntax): a SHACL instance of
        // sh:ListParameterExpressionFunction / sh:NamedParameterExpressionFunction and a SHACL subclass of
        // sh:ListParameterExpression / sh:NamedParameterExpression. Without the subclass, a call is no node expression.
        val list = "ex:f a sh:ListParameterExpressionFunction ; sh:bodyExpression [ shnex:arg 0 ] ."
        val named = "ex:G a sh:NamedParameterExpressionFunction ; sh:parameter [ sh:path ex:g ; sh:keyParameter true ] ; sh:bodyExpression true ."
        val calls = listOf(
            list to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:expression [ ex:f ( true ) ] .",
            named to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:expression [ ex:g 1 ] .",
        )
        for ((declaration, shape) in calls) {
            val error = assertThrows(ShaclValidationException::class.java, { validate("jena", "ex:a ex:p 1 .", declaration + " " + shape) }, declaration)
            val unsupported = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<UnsupportedShaclFeatureException>().first()
            assertEquals(setOf(UnsupportedShaclFeature.NODE_EXPRESSION), unsupported.features, declaration)
        }
        // With the subclass (directly or through another class), the call is evaluated.
        val listSub = list.replace(" ; sh:bodyExpression", " ; rdfs:subClassOf ex:MyExpressions ; sh:bodyExpression") +
            " ex:MyExpressions rdfs:subClassOf sh:ListParameterExpression ."
        assertTrue(validate("jena", "ex:a ex:p 1 .", listSub + " " + calls[0].second).isValid)
        val namedSub = named.replace(" ; sh:bodyExpression", " ; rdfs:subClassOf sh:NamedParameterExpression ; sh:bodyExpression")
        assertTrue(validate("jena", "ex:a ex:p 1 .", namedSub + " " + calls[1].second).isValid)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `sh targetWhere candidates are not narrowed by a shape deactivated per focus node`(provider: String) {
        // ex:n is no ex:C and no literal, but ex:W is deactivated for it, so it conforms to ex:W and is a target of ex:T.
        val skip = "sh:deactivated [ shnex:exists [ shnex:pathValues ex:skip ] ]"
        val target = "ex:T a sh:NodeShape ; sh:targetWhere ex:W ; sh:property [ sh:path ex:skip ; sh:maxCount 0 ] ."
        val data = "ex:n ex:skip 1 . ex:c a ex:C ; ex:q 2 ."
        for (membership in listOf("sh:class ex:C", "sh:datatype xsd:integer", "sh:nodeKind sh:Literal")) {
            val shapes = "$target ex:W a sh:NodeShape ; $membership ; $skip ."
            assertEquals(listOf(ex("n")), validate(provider, data, shapes).violations.map { it.focusNode }, membership)
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `sh uniqueValuesFor reports no result for a focus node the shape is deactivated for`(provider: String) {
        // ex:a and ex:b share their key; the shape is deactivated for ex:a, which still makes ex:b a duplicate.
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b , ex:c ; sh:uniqueValuesFor ( ex:id ) ;
              sh:deactivated [ shnex:exists [ shnex:pathValues ex:skip ] ] .
        """
        val data = "ex:a ex:id 1 ; ex:skip 1 . ex:b ex:id 1 . ex:c ex:id 2 ."
        val report = validate(provider, data, shapes)
        assertEquals(listOf(ex("b")), report.violations.map { it.focusNode })
        assertTrue(!report.isValid)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a SPARQL constraint is deactivated for the focus nodes whose expression is true`(provider: String) {
        // SHACL 1.2 SPARQL: evalExpr(expr, data graph, focus node, {}) producing true deactivates the constraint.
        val select = "sh:select '''SELECT ${'$'}this WHERE { FILTER NOT EXISTS { ${'$'}this <http://example.com/ns#ok> ?x } }'''"
        val skip = "sh:deactivated [ shnex:exists [ shnex:pathValues ex:skip ] ]"
        val data = "ex:a ex:skip 1 . ex:b ex:p 1 ."
        val node = "ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:sparql [ $select ; $skip ] ."
        assertEquals(listOf(ex("b")), validate(provider, data, node).violations.map { it.focusNode })
        val property = "ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ; sh:property [ sh:path ex:p ; sh:sparql [ $select ; $skip ] ] ."
        assertEquals(listOf(ex("b")), validate(provider, data, property).violations.map { it.focusNode })
        // Only exactly true deactivates; a failing expression fails validation.
        val flag = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ $select ; sh:deactivated [ shnex:pathValues ex:flag ] ] ."
        assertTrue(validate(provider, "ex:a ex:flag true .", flag).isValid)
        assertEquals(1, validate(provider, "ex:a ex:flag false .", flag).violations.size)
        val failing = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ $select ; sh:deactivated [ sparql:abs ( ( 1 2 ) ) ] ] ."
        assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:p 1 .", failing) }
        // A shape read by the expression is a dependency: decidable here (ex:Y fails sh:minCount on its own).
        val recursive = """
            ex:S a sh:NodeShape ; sh:targetNode ex:x ;
              sh:sparql [ $select ; sh:deactivated [ shnex:conformsToShape ( [ shnex:var "focusNode" ] ex:Y ) ] ] .
            ex:Y a sh:NodeShape ; sh:node ex:S ; sh:property [ sh:path ex:missing ; sh:minCount 1 ] .
        """
        assertEquals(1, validate(provider, "ex:x ex:p 1 .", recursive).violations.size)
    }

    @Test
    fun `a SPARQL constraint sh deactivated that is no node expression is unsupported`() {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ sh:select 'SELECT ?this WHERE { }' ; sh:deactivated [ ex:undeclared 1 ] ] ."
        val error = assertThrows(ShaclValidationException::class.java) { validate("jena", "ex:a ex:p 1 .", shapes) }
        val unsupported = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<UnsupportedShaclFeatureException>().first()
        assertEquals(setOf(UnsupportedShaclFeature.NODE_EXPRESSION), unsupported.features)
        val ignore = ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING)
        val report = validate("jena", "ex:a ex:p 1 .", shapes, ignore)
        assertTrue(report.warnings.any { "Unsupported SHACL feature ignored" in it.message && "sh:deactivated" in it.message }, report.warnings.toString())
    }

    @Test
    fun `a second sh deactivated value next to true is a compile error`() {
        // SHACL 1.2 Core §3.1.6: at most one value, also when one of them is the constant true.
        val forms = listOf(
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:deactivated true , [ shnex:exists [ shnex:pathValues ex:p ] ] ; sh:class ex:C .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:deactivated true , false ; sh:class ex:C .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:deactivated true , [ shnex:exists [ shnex:pathValues ex:p ] ] ] .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ sh:select 'SELECT ?this WHERE { }' ; sh:deactivated true , false ] .",
        )
        for (shapes in forms) {
            val error = assertThrows(ShaclValidationException::class.java, { validate("jena", "ex:a ex:p 1 .", shapes) }, shapes)
            val compile = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<ShapeCompileException>().firstOrNull()
            assertTrue(compile != null && "at most one value" in compile.message.orEmpty(), "$shapes: $error")
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a shape read by a property shape's sh deactivated is a negative dependency`(provider: String) {
        // ex:P is deactivated for x iff x does not conform to ex:S, which holds iff ex:P conforms: a paradox.
        val paradox = """
            ex:S a sh:NodeShape ; sh:targetNode ex:x ; sh:property ex:P .
            ex:P sh:path ex:p ; sh:maxCount 0 ;
              sh:deactivated [ sparql:logical-not ( [ shnex:conformsToShape ( [ shnex:var "focusNode" ] ex:S ) ] ) ] .
        """
        assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:x ex:p 1 .", paradox) }
        // Decidable: deactivated iff x conforms to ex:Y, which reads ex:S back but fails sh:minCount on its own.
        val decidable = """
            ex:S a sh:NodeShape ; sh:targetNode ex:x ; sh:property ex:P .
            ex:P sh:path ex:p ; sh:maxCount 0 ; sh:deactivated [ shnex:conformsToShape ( [ shnex:var "focusNode" ] ex:Y ) ] .
            ex:Y a sh:NodeShape ; sh:node ex:S ; sh:property [ sh:path ex:missing ; sh:minCount 1 ] .
        """
        assertEquals(1, validate(provider, "ex:x ex:p 1 .", decidable).violations.size)
    }

    @Test
    fun `constant sh deactivated is unchanged`() {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:deactivated true ; sh:class ex:C ."
        assertTrue(validate("jena", "ex:a ex:p 1 .", shapes).isValid)
    }
}
