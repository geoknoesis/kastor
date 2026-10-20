package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.catalog.ShapeCatalog
import com.geoknoesis.kastor.ontoquality.catalog.ShapeMetadata
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidatorProvider
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CatalogBlankNodeScopeTest {
    private fun catalog(id: String, target: Iri, path: Iri): ShapeCatalog {
        val property = BlankNode("property")
        val graph = MemoryGraph(listOf(
            RdfTriple(Iri("urn:shape:$id"), RDF.type, SHACL.NodeShape),
            RdfTriple(Iri("urn:shape:$id"), SHACL.targetNode, target),
            RdfTriple(Iri("urn:shape:$id"), SHACL.`property`, property),
            RdfTriple(property, SHACL.path, path),
            RdfTriple(property, SHACL.minCount, int(1)),
        ))
        return object : ShapeCatalog {
            override val id = id
            override val name = id
            override val version = "1"
            override val shapeMetadata = mapOf(property.toString() to ShapeMetadata(
                QualityCategory.OWL_METADATA, PitfallReference.Oops(if (id == "left") "P09" else "P11"), QualityTier.STRUCTURAL,
            ))
            override fun loadShapesGraph(): RdfGraph = graph
        }
    }

    @Test
    fun `catalog property shapes cannot acquire another catalogs path`() {
        val a = Iri("urn:a")
        val b = Iri("urn:b")
        val name = Iri("urn:name")
        val age = Iri("urn:age")
        val left = catalog("left", a, name)
        val right = catalog("right", b, age)
        val merged = QualityChecker.mergeCatalogShapes(listOf(left, right, left))
        assertEquals(10, merged.size())
        assertEquals(2, merged.find(predicate = SHACL.path).map { it.subject }.distinct().size)
        val validator = NativeShaclValidatorProvider().createValidator(ValidationConfig())
        val data = MemoryGraph(listOf(RdfTriple(a, name, string("A")), RdfTriple(b, age, int(25))))
        assertTrue(validator.validate(data, merged).isValid)
        val report = validator.validate(MemoryGraph(data.getTriples().take(1)), merged)
        assertEquals(listOf(b), report.violations.map { it.focusNode })
        val checker = QualityChecker.builder(validator).addCatalogs(listOf(left, right)).build()
        val findings = checker.check(MemoryGraph(data.getTriples().take(1))).findings
        assertEquals(listOf(PitfallReference.Oops("P11")), findings.map { it.pitfall })
        val emptyFindings = checker.check(MemoryGraph()).findings
        assertEquals(setOf(PitfallReference.Oops("P09"), PitfallReference.Oops("P11")), emptyFindings.map { it.pitfall }.toSet())
        assertEquals(5, left.loadShapesGraph().size())
        assertEquals(5, right.loadShapesGraph().size())
    }
}
