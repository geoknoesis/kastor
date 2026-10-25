package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassFactoryGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassWriterGenerator
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.gen.processor.testing.XSD_NS
import com.geoknoesis.kastor.gen.processor.testing.prop
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals

/**
 * The generated `replaceInGraph` is a lossless read-modify-write: terms whose value the record still holds keep
 * their language tag, datatype and lexical form, values beyond `sh:maxCount` of an unchanged field survive, terms
 * the record cannot represent are kept, and an ill-typed new value fails before the graph changes. The generated
 * reader checks a declared non-string datatype of a String member.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecordRoundTripTest {

    private val emptyContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())
    private lateinit var compiled: KotlinSourceCompiler.Result

    private val model = OntologyModel(
        listOf(
            ShaclShape(
                EX + "DocShape", EX + "Doc",
                listOf(
                    prop("title", datatype = null),
                    prop("age", datatype = "${XSD_NS}integer"),
                    prop("tags", datatype = null, maxCount = null),
                    prop("issued", datatype = "${XSD_NS}date"),
                    prop("modified", datatype = "${XSD_NS}dateTime"),
                    prop("homepage", datatype = null).copy(nodeKind = "http://www.w3.org/ns/shacl#IRI"),
                    prop("level", datatype = "${XSD_NS}unsignedByte"),
                    prop("count", datatype = "${XSD_NS}nonNegativeInteger"),
                    prop("motto", datatype = "http://www.w3.org/1999/02/22-rdf-syntax-ns#dirLangString"),
                ),
            )
        ),
        emptyContext,
    )

    private val probe = """
        package gen.roundtrip

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph
        import com.geoknoesis.kastor.rdf.vocab.XSD
        import java.math.BigInteger

        fun p(local: String) = Iri("https://example.test/" + local)
        val a = Iri("urn:a")

        fun graph(): MutableRdfGraph = MemoryGraph().apply {
            addTriple(RdfTriple(a, p("title"), LangString("Chat", "fr")))
            addTriple(RdfTriple(a, p("title"), LangString("Cat", "en")))
            addTriple(RdfTriple(a, p("age"), TypedLiteral("02", XSD.integer)))
            addTriple(RdfTriple(a, p("tags"), LangString("a", "en")))
            addTriple(RdfTriple(a, p("tags"), Literal("b")))
            addTriple(RdfTriple(a, p("issued"), TypedLiteral("2020-01-01+05:00", XSD.date)))
            addTriple(RdfTriple(a, p("homepage"), Iri("urn:home1")))
            addTriple(RdfTriple(a, p("homepage"), Iri("urn:home2")))
        }

        fun dump(g: RdfGraph): String = g.find(a).filter { it.predicate != com.geoknoesis.kastor.rdf.vocab.RDF.type }
            .map { t ->
                val o = t.obj
                t.predicate.value.substringAfterLast('/') + "=" + when (o) {
                    is LangString -> o.lexical + "@" + o.lang
                    is Literal -> o.lexical + "^^" + o.datatype.value.substringAfterLast('#')
                    is Iri -> o.value
                    else -> o.toString()
                }
            }.sorted().joinToString(",")

        /** Unchanged fields keep every original term; only the edited field changes. */
        fun unchanged(): String {
            val g = graph()
            val record = DocRecordFactory.from(DefaultRdfHandle(a, g, emptySet()))
            DocRecordFactory.replaceInGraph(g, record.copy(age = BigInteger.valueOf(3)), a)
            return dump(g)
        }

        /** Changed values replace the representable terms they came from; kept list values keep their terms. */
        fun changed(): String {
            val g = graph()
            val record = DocRecordFactory.from(DefaultRdfHandle(a, g, emptySet()))
            DocRecordFactory.replaceInGraph(g, record.copy(title = "Dog", tags = listOf("a", "c"), homepage = "urn:home3"), a)
            return dump(g)
        }

        /** A value of another datatype is ill typed for a String member that declares xsd:dateTime. */
        fun illTypedRead(): String {
            val g = graph().apply { addTriple(RdfTriple(a, p("modified"), TypedLiteral("2020", XSD.gYear))) }
            val strict = try { DocRecordFactory.from(DefaultRdfHandle(a, g, emptySet())).modified } catch (e: MaterializationException) { "rejected" }
            val skipped = MaterializationPolicy.withIllTypedValues(IllTypedValueHandling.SKIP) { DocRecordFactory.from(DefaultRdfHandle(a, g, emptySet())) }
            // The unrepresentable gYear value is kept next to the record's new value.
            DocRecordFactory.replaceInGraph(g, skipped.copy(modified = "2021-05-06T07:08:09Z"), a)
            return strict + "|" + skipped.modified + "|" + dump(g).split(',').filter { it.startsWith("modified=") }.joinToString(",")
        }

        /** An ill-typed new value fails before any change. */
        fun illTypedWrite(): String {
            val g = graph()
            val before = dump(g)
            val record = DocRecordFactory.from(DefaultRdfHandle(a, g, emptySet()))
            val outcome = try {
                DocRecordFactory.replaceInGraph(g, record.copy(title = "New", modified = "yesterday"), a); "written"
            } catch (e: IllegalArgumentException) { "rejected" }
            return outcome + "|" + (dump(g) == before)
        }

        /** Updates to a value the graph already holds beyond sh:maxCount are written and read back. */
        fun updateToExtraValue(): String {
            val g = graph()
            val record = DocRecordFactory.from(DefaultRdfHandle(a, g, emptySet()))
            DocRecordFactory.replaceInGraph(g, record.copy(title = "Cat", homepage = "urn:home2"), a)
            val reread = DocRecordFactory.from(DefaultRdfHandle(a, g, emptySet()))
            return record.title + "," + record.homepage + "->" + reread.title + "," + reread.homepage + "|" +
                dump(g).split(',').filter { it.startsWith("title=") || it.startsWith("homepage=") }.joinToString(",")
        }

        /** An rdf:dirLangString member is a LangString with its base direction, read and written back as is. */
        fun directional(): String {
            val g = graph()
            val record = DocRecordFactory.from(DefaultRdfHandle(a, g, emptySet()))
            DocRecordFactory.replaceInGraph(g, record.copy(motto = LangString("salam", "ar", Direction.RTL)), a)
            val motto = DocRecordFactory.from(DefaultRdfHandle(a, g, emptySet())).motto!!
            return motto.lexical + "@" + motto.lang + "--" + motto.direction + "^^" + motto.datatype.value.substringAfterLast('#')
        }

        /** Integer values outside the declared derived type are ill-typed on read, not a failure on the next write. */
        fun outOfRange(): String {
            fun read(term: RdfTerm, predicate: String) = graph().apply { addTriple(RdfTriple(a, p(predicate), term)) }.let { g ->
                try {
                    val r = DocRecordFactory.from(DefaultRdfHandle(a, g, emptySet()))
                    (r.level ?: r.count).toString()
                } catch (e: MaterializationException) { "rejected" }
            }
            return listOf(
                read(TypedLiteral("300", XSD.integer), "level"), read(TypedLiteral("-1", XSD.long), "level"),
                read(TypedLiteral("200", XSD.integer), "level"), read(TypedLiteral("-1", XSD.integer), "count"),
                read(TypedLiteral("7", XSD.int), "count"),
            ).joinToString(",")
        }
    """.trimIndent()

    @BeforeAll
    fun compile() {
        val logger = RecordingLogger()
        val pkg = "gen.roundtrip"
        val files = DataClassGenerator(logger, "Record", NestedMode.IRI_ONLY, false, ValidationAnnotations.NONE)
            .generateDataClasses(model, pkg).values +
            DataClassFactoryGenerator(logger, "Record", NestedMode.IRI_ONLY, DataClassWriterGenerator(logger, "Record", NestedMode.IRI_ONLY))
                .generateFactories(model, pkg).values
        compiled = KotlinSourceCompiler.compile(files, mapOf("gen/roundtrip/Probe.kt" to probe))
        compiled.assertOk()
    }

    private fun call(name: String): String =
        compiled.classLoader().loadClass("gen.roundtrip.ProbeKt").getMethod(name).invoke(null) as String

    @Test
    fun `unchanged fields keep language tags, datatypes, lexical forms and values beyond maxCount`() {
        assertEquals(
            "age=3^^integer,homepage=urn:home1,homepage=urn:home2,issued=2020-01-01+05:00^^date," +
                "tags=a@en,tags=b^^string,title=Cat@en,title=Chat@fr",
            call("unchanged"),
        )
    }

    @Test
    fun `changed fields replace the values they held and keep the original terms of retained list values`() {
        assertEquals(
            "age=02^^integer,homepage=urn:home3,issued=2020-01-01+05:00^^date,tags=a@en,tags=c^^string,title=Dog^^string",
            call("changed"),
        )
    }

    @Test
    fun `a declared non-string datatype is checked on read and an unrepresentable value is kept on write`() {
        assertEquals("rejected|null|modified=2020^^gYear,modified=2021-05-06T07:08:09Z^^dateTime", call("illTypedRead"))
    }

    @Test
    fun `a single-valued update to a value held beyond sh maxCount is written and read back`() {
        assertEquals("Chat,urn:home1->Cat,urn:home2|homepage=urn:home2,title=Cat@en", call("updateToExtraValue"))
    }

    @Test
    fun `an rdf dirLangString member round-trips its language and direction`() {
        assertEquals("salam@ar--RTL^^dirLangString", call("directional"))
    }

    @Test
    fun `integer values outside the declared derived type are ill-typed on read`() {
        assertEquals("rejected,rejected,200,rejected,7", call("outOfRange"))
    }

    @Test
    fun `an ill-typed new value is rejected before the graph changes`() {
        assertEquals("rejected|true", call("illTypedWrite"))
    }
}
