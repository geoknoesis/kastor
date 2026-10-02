package com.geoknoesis.kastor.gen.processor.parsers

import com.geoknoesis.kastor.gen.processor.internal.core.OntologyProcessor
import com.geoknoesis.kastor.gen.processor.internal.core.OntologyProcessorProvider
import com.geoknoesis.kastor.gen.processor.internal.parsers.OntologyExtractor
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.KspRunner
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * OWL ontologies describe classes with anonymous class expressions (`owl:unionOf`, `owl:Restriction`, ...). They are
 * not generation targets: they are skipped, never put into the model as a class without an IRI, and what was skipped
 * is reported at info level.
 */
class OntologyAnonymousClassTest {

    private val ontology = """
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix ex: <https://example.test/> .

        ex:Agent a owl:Class .
        ex:Person a owl:Class ;
            rdfs:subClassOf ex:Agent ;
            rdfs:subClassOf [ a owl:Restriction ; owl:onProperty ex:name ; owl:minCardinality "1"^^xsd:nonNegativeInteger ] .
        ex:Organization a owl:Class ; rdfs:subClassOf ex:Agent .
        ex:name a owl:DatatypeProperty ;
            rdfs:domain [ a owl:Class ; owl:unionOf ( ex:Person ex:Organization ) ] .
        ex:Pet a rdfs:Class ; rdfs:subClassOf [ a owl:Class ; owl:complementOf ex:Agent ] .
    """.trimIndent()

    @Test
    fun `anonymous class expressions are skipped and reported, named classes are all extracted`() {
        val logger = RecordingLogger()
        val classes = OntologyExtractor(logger).extractClassesFromContent(ontology)

        assertEquals(emptyList<String>(), logger.errors, "an anonymous class is not an error")
        assertEquals(
            listOf("Agent", "Organization", "Person", "Pet"),
            classes.map { it.className }.sorted(),
            "every named class is extracted, whatever anonymous classes the ontology declares",
        )
        val byName = classes.associateBy { it.className }
        assertEquals(listOf("https://example.test/Agent"), byName.getValue("Person").superClasses, "the restriction is not a superclass to generate")
        assertEquals(emptyList<String>(), byName.getValue("Pet").superClasses)
        assertEquals(listOf("https://example.test/Agent"), byName.getValue("Organization").superClasses)

        val skipped = logger.infos.filter { "kipped" in it }
        assertTrue(
            skipped.any { it == "Skipped 2 anonymous class expression(s) (owl:unionOf, owl:Restriction, ...): only named classes are generation targets" },
            skipped.toString(),
        )
        assertTrue(
            skipped.any { it == "Skipped 1 anonymous superclass expression(s) of https://example.test/Person" },
            skipped.toString(),
        )
        assertTrue(skipped.any { it == "Skipped 1 anonymous superclass expression(s) of https://example.test/Pet" }, skipped.toString())
    }

    @Test
    fun `a literal superclass is skipped like an anonymous one`() {
        val logger = RecordingLogger()
        val classes = OntologyExtractor(logger).extractClassesFromContent(
            """
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix ex: <https://example.test/> .
            ex:Thing a owl:Class ; rdfs:subClassOf "not a class" , ex:Root .
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), logger.errors)
        assertEquals(listOf("https://example.test/Root"), classes.single().superClasses)
    }

    @TempDir
    lateinit var resources: File

    @Test
    fun `KSP generates the instance DSL for an ontology with unionOf and restrictions`() {
        File(resources, "people.owl.ttl").writeText(ontology)
        File(resources, "people.shacl.ttl").writeText(
            """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <https://example.test/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
                sh:property [ sh:path ex:name ; sh:name "name" ; sh:datatype xsd:string ; sh:minCount 1 ; sh:maxCount 1 ] .
            """.trimIndent(),
        )
        val marker = """
            @file:Rdf(shacl = "people.shacl.ttl", ontologyPath = "people.owl.ttl", generateDsl = true, dslName = "people",
                generateInterfaces = false, generateWrappers = false)

            package gen.onto

            import com.geoknoesis.kastor.gen.annotations.Rdf
        """.trimIndent()

        val result = KspRunner.run(
            OntologyProcessorProvider(),
            mapOf("gen/onto/Marker.kt" to marker),
            mapOf(
                OntologyProcessor.RESOURCES_OPTION to resources.absolutePath,
                OntologyProcessor.RESOURCES_TRACKED_OPTION to "true",
            ),
        )
        assertEquals(emptyList<String>(), result.errors, "anonymous classes must not surface as a KSP error")
        assertEquals(KotlinSymbolProcessing.ExitCode.OK, result.exitCode)
        assertTrue(result.infos.any { it == "Parsed 4 ontology classes" }, result.infos.filter { "ontology" in it }.toString())
        assertTrue(
            result.infos.any { it.startsWith("Skipped 2 anonymous class expression(s)") },
            result.infos.filter { "kipped" in it }.toString(),
        )
        assertTrue(result.generated.isNotEmpty(), "the DSL is generated")
        KotlinSourceCompiler.compile(emptyList(), result.sources).assertOk()
    }
}
