package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.bnode
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidatorProvider
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeExtendedFeaturesTest {

    private val ex = "http://example.org/ns#"

    @Test
    fun `owl imports merges auxiliary ontology triples into shapes`() {
        val importedShapeName = Iri("${ex}ImportedShape")
        val importer =
            Rdf.graph {
                val root = Iri("${ex}ShapesRoot")
                root - RDF.type - SHACL.NodeShape
                root - OWL.imports - Iri("${ex}Ontology")
                root - SHACL.targetClass - Iri("${ex}Person")
                root - SHACL.`property` - Iri("${ex}PsImporter")
                Iri("${ex}PsImporter") - RDF.type - SHACL.PropertyShape
                Iri("${ex}PsImporter") - SHACL.path - Iri("${ex}name")
                Iri("${ex}PsImporter") - SHACL.minCount - 1
            }
        val importedOntology =
            Rdf.graph {
                importedShapeName - RDF.type - SHACL.NodeShape
                importedShapeName - SHACL.targetClass - Iri("${ex}Person")
                importedShapeName - SHACL.`property` - Iri("${ex}PsImported")
                Iri("${ex}PsImported") - RDF.type - SHACL.PropertyShape
                Iri("${ex}PsImported") - SHACL.path - Iri("${ex}age")
                Iri("${ex}PsImported") - SHACL.minCount - 1
            }
        val cfg =
            ValidationConfig(
                imports = ImportConfig(resolveOwlImports = true),
                dataset = DatasetValidationConfig(auxiliaryGraphs = mapOf(Iri("${ex}Ontology") to importedOntology)),
            )
        val validator = NativeShaclValidatorProvider().createValidator(cfg)
        val data =
            Rdf.graph {
                val p = Iri("${ex}p1")
                p - RDF.type - Iri("${ex}Person")
                p - Iri("${ex}name") - "Ann"
            }
        val report = validator.validate(data, importer)
        assertTrue(
            report.violations.any {
                it.constraint.constraintType == ConstraintType.MIN_COUNT &&
                    it.constraint.path?.contains("age") == true
            },
        )
    }

    @Test
    fun `shapesGraph pulls shapes from auxiliary named graph`() {
        val shapesGraphName = Iri("http://graphs.example/shapes")
        val shapeDoc =
            Rdf.graph {
                val shape = Iri("${ex}S")
                val ps = Iri("${ex}ps")
                shape - RDF.type - SHACL.NodeShape
                shape - SHACL.targetNode - Iri("${ex}a")
                shape - SHACL.`property` - ps
                ps - RDF.type - SHACL.PropertyShape
                ps - SHACL.path - Iri("${ex}p")
                ps - SHACL.minCount - 1
            }
        val data =
            Rdf.graph {
                val meta = bnode("meta")
                meta - SHACL.shapesGraph - shapesGraphName
                Iri("${ex}a") - RDF.type - Iri("${ex}T")
            }
        val cfg =
            ValidationConfig(
                dataset =
                    DatasetValidationConfig(
                        auxiliaryGraphs = mapOf(shapesGraphName to shapeDoc),
                        discoverShapesGraphFromData = true,
                    ),
            )
        val validator = NativeShaclValidatorProvider().createValidator(cfg)
        val emptyShapes = Rdf.graph { }
        val report = validator.validate(data, emptyShapes)
        assertTrue(report.violations.any { it.constraint.constraintType == ConstraintType.MIN_COUNT })
    }

    @Test
    fun `stale shapesGraphVersion throws when digest changes`() {
        val tag = "tag-" + UUID.randomUUID()
        val cfgBase =
            ValidationConfig(
                cache = CacheConfig(shapesGraphVersion = tag),
            )
        val validator = NativeShaclValidatorProvider().createValidator(cfgBase)
        val data = Rdf.graph { Iri("${ex}a") - RDF.type - Iri("${ex}T") }
        val shapes1 =
            Rdf.graph {
                val s = Iri("${ex}Shape1")
                val ps = Iri("${ex}ps1")
                s - RDF.type - SHACL.NodeShape
                s - SHACL.targetNode - Iri("${ex}a")
                s - SHACL.`property` - ps
                ps - RDF.type - SHACL.PropertyShape
                ps - SHACL.path - Iri("${ex}p")
                ps - SHACL.minCount - 1
            }
        validator.validate(data, shapes1)
        val shapes2 =
            Rdf.graph {
                val s = Iri("${ex}Shape2")
                val ps = Iri("${ex}ps2")
                s - RDF.type - SHACL.NodeShape
                s - SHACL.targetNode - Iri("${ex}a")
                s - SHACL.`property` - ps
                ps - RDF.type - SHACL.PropertyShape
                ps - SHACL.path - Iri("${ex}q")
                ps - SHACL.minCount - 99
            }
        assertThrows(StaleShapesGraphTagException::class.java) {
            validator.validate(data, shapes2)
        }
    }

    @Test
    fun `sparql constraint uses dollar-this binding for iri focus`() {
        val sq = bnode("sparql")
        val shapes =
            Rdf.graph {
                val shape = Iri("${ex}Shape")
                val ps = Iri("${ex}ps")
                shape - RDF.type - SHACL.NodeShape
                shape - SHACL.targetNode - Iri("${ex}a")
                shape - SHACL.`property` - ps
                ps - RDF.type - SHACL.PropertyShape
                ps - SHACL.path - Iri("${ex}p")
                ps - SHACL.sparql - sq
                sq - SHACL.select -
                    """
                    PREFIX ex: <${ex}>
                    SELECT ?x WHERE {
                      FILTER (ex:wrong = ${'$'}this)
                    }
                    """.trimIndent()
            }
        val data =
            Rdf.graph {
                Iri("${ex}a") - RDF.type - Iri("${ex}T")
                Iri("${ex}a") - Iri("${ex}p") - "v"
            }
        val validator = NativeShaclValidatorProvider().createValidator(ValidationConfig.default())
        val report = validator.validate(data, shapes)
        assertTrue(report.isValid)
    }

    @Test
    fun `closed shape yields CLOSED constraint type and RDF ClosedConstraintComponent`() {
        val shapes =
            Rdf.graph {
                val shape = Iri("${ex}S")
                val ps = Iri("${ex}ps")
                shape - RDF.type - SHACL.NodeShape
                shape - SHACL.targetNode - Iri("${ex}a")
                shape - SHACL.closed - true
                shape - SHACL.`property` - ps
                ps - RDF.type - SHACL.PropertyShape
                ps - SHACL.path - Iri("${ex}allowed")
            }
        val data =
            Rdf.graph {
                Iri("${ex}a") - Iri("${ex}allowed") - "ok"
                Iri("${ex}a") - Iri("${ex}extra") - "bad"
            }
        val validator = NativeShaclValidatorProvider().createValidator(ValidationConfig.default())
        val report = validator.validate(data, shapes)
        assertFalse(report.isValid)
        val v = report.violations.first { it.constraint.constraintType == ConstraintType.CLOSED }
        assertTrue(v.path?.singleOrNull() == Iri("${ex}extra"))
        assertTrue(v.value != null)
        val cc = Iri("http://www.w3.org/ns/shacl#ClosedConstraintComponent")
        assertTrue(report.toShaclValidationReportRdf().getTriples().any { it.predicate == SHACL.sourceConstraintComponent && it.obj == cc })
    }

    @Test
    fun `maxCount zero uses sole triple object as witness value`() {
        val shapes =
            Rdf.graph {
                val shape = Iri("${ex}S")
                val ps = Iri("${ex}ps")
                shape - RDF.type - SHACL.NodeShape
                shape - SHACL.targetNode - Iri("${ex}a")
                shape - SHACL.`property` - ps
                ps - RDF.type - SHACL.PropertyShape
                ps - SHACL.path - Iri("${ex}tag")
                ps - SHACL.maxCount - 0
            }
        val data =
            Rdf.graph {
                Iri("${ex}a") - Iri("${ex}tag") - "only"
            }
        val report =
            NativeShaclValidatorProvider().createValidator(ValidationConfig.default()).validate(data, shapes)
        assertFalse(report.isValid)
        val v = report.violations.first { it.constraint.constraintType == ConstraintType.MAX_COUNT }
        assertTrue(v.value is Literal)
        assertTrue((v.value as Literal).lexical == "only")
    }
}
