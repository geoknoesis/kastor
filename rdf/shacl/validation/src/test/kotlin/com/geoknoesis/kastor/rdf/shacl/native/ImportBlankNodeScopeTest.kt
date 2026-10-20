package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import com.geoknoesis.kastor.rdf.shacl.*
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ImportBlankNodeScopeTest {
    private val imported = Iri("urn:imported")
    private val rootName = Iri("urn:root")
    private val property = BlankNode("property")
    private val imports = ImportConfig(resolveOwlImports = true)

    @Test
    fun `multiple import aliases scan one graph once`() {
        val aliases = (0 until 20).map { Iri("urn:alias:$it") }
        val root = MemoryGraph(aliases.map { RdfTriple(rootName, OWL.imports, it) })
        val backing = MemoryGraph(shapes("urn:ImportedShape", "urn:b", "urn:age"))
        var scans = 0
        val graph = object : RdfGraph by backing {
            override fun getTriplesSequence(): Sequence<RdfTriple> {
                scans++
                return backing.getTriplesSequence()
            }
        }
        val expanded = OwlImportsExpander.expand(root, imports, aliases.associateWith { graph })
        assertEquals(root.size() + backing.size(), expanded.size())
        assertEquals(1, scans)
    }

    private fun shapes(shape: String, target: String, path: String) = listOf(
        RdfTriple(Iri(shape), RDF.type, SHACL.NodeShape),
        RdfTriple(Iri(shape), SHACL.targetNode, Iri(target)),
        RdfTriple(Iri(shape), SHACL.`property`, property),
        RdfTriple(property, SHACL.path, Iri(path)),
        RdfTriple(property, SHACL.minCount, int(1)),
    )

    @Test
    fun `imported targets and values shared with repository data keep their identities`() {
        MemoryRepository(RdfConfig()).use { repo ->
            val target = BlankNode("imported-target")
            val value = BlankNode("imported-value")
            val p = Iri("urn:p")
            repo.editGraph(rootName).addTriple(RdfTriple(rootName, OWL.imports, imported))
            repo.editGraph(imported).addTriples(listOf(
                RdfTriple(Iri("urn:Shape"), RDF.type, SHACL.NodeShape),
                RdfTriple(Iri("urn:Shape"), SHACL.targetNode, target),
                RdfTriple(Iri("urn:Shape"), SHACL.`property`, property),
                RdfTriple(property, SHACL.path, p),
                RdfTriple(property, SHACL.hasValue, value),
            ))
            repo.editDefaultGraph().addTriple(RdfTriple(target, p, value))
            val config = ValidationConfig(imports = imports, dataset = DatasetValidationConfig(
                auxiliaryGraphs = mapOf(imported to repo.getGraph(imported)),
            ))
            val validator = NativeShaclValidator(config)
            assertTrue(validator.validate(repo.defaultGraph, repo.getGraph(rootName)).isValid)
            assertTrue(validator.validate(repo.defaultGraph, repo.getGraph(rootName)).isValid)
            assertEquals(1, validator.cacheStatistics.entries)
            repo.editDefaultGraph().removeTriple(RdfTriple(target, p, value))
            repo.editDefaultGraph().addTriple(RdfTriple(target, p, string("wrong")))
            assertEquals(listOf(target), validator.validate(repo.defaultGraph, repo.getGraph(rootName)).violations.map { it.focusNode })
            assertEquals(1, validator.cacheStatistics.entries)
        }
    }

    @Test
    fun `imports and discovered shapes use distinct namespaces in the same validation`() {
        val root = MemoryGraph(shapes("urn:RootShape", "urn:a", "urn:name") + RdfTriple(rootName, OWL.imports, imported))
        val other = MemoryGraph(shapes("urn:ImportedShape", "urn:b", "urn:age"))
        val discoveredName = Iri("urn:discovered")
        val discovered = MemoryGraph(shapes("urn:DiscoveredShape", "urn:c", "urn:email"))
        val config = ValidationConfig(imports = imports, dataset = DatasetValidationConfig(
            discoverShapesGraphFromData = true, auxiliaryGraphs = mapOf(imported to other, discoveredName to discovered),
        ))
        val validator = NativeShaclValidator(config)
        val data = listOf(
            RdfTriple(Iri("urn:data"), SHACL.shapesGraph, discoveredName),
            RdfTriple(Iri("urn:a"), Iri("urn:name"), string("A")),
            RdfTriple(Iri("urn:b"), Iri("urn:age"), int(25)),
            RdfTriple(Iri("urn:c"), Iri("urn:email"), string("c@example.org")),
        )
        assertTrue(validator.validate(MemoryGraph(data), root).isValid)
        val missing = validator.validate(MemoryGraph(data.dropLast(1)), root)
        assertEquals(listOf(Iri("urn:c")), missing.violations.map { it.focusNode })
        assertEquals(1, validator.cacheStatistics.entries)
    }

    @Test
    fun `imported property shapes with identical local labels validate independently`() {
        val root = MemoryGraph(shapes("urn:RootShape", "urn:a", "urn:name") + RdfTriple(rootName, OWL.imports, imported))
        val other = MemoryGraph(shapes("urn:ImportedShape", "urn:b", "urn:age"))
        val config = ValidationConfig(imports = imports, dataset = DatasetValidationConfig(auxiliaryGraphs = mapOf(imported to other)))
        val validator = NativeShaclValidator(config)
        val data = MemoryGraph(listOf(
            RdfTriple(Iri("urn:a"), Iri("urn:name"), string("A")),
            RdfTriple(Iri("urn:b"), Iri("urn:age"), int(25)),
        ))
        assertTrue(validator.validate(data, root).isValid)
        assertTrue(validator.validate(data, root).isValid)
        assertEquals(1, validator.cacheStatistics.entries)
        assertTrue(validator.cacheStatistics.hits >= 1)
        val missing = validator.validate(MemoryGraph(data.getTriples().take(1)), root)
        assertFalse(missing.isValid)
        assertEquals(listOf(Iri("urn:b")), missing.violations.map { it.focusNode })
        assertEquals(listOf(listOf(Iri("urn:age"))), missing.violations.map { it.path })
        assertEquals(6, root.size())
        assertEquals(5, other.size())
    }

    @Test
    fun `root blank node targets and hasValue parameters retain their data identities`() {
        val target = BlankNode("target")
        val value = BlankNode("value")
        val link = Iri("urn:link")
        val shape = Iri("urn:RootShape")
        val root = MemoryGraph(listOf(
            RdfTriple(rootName, OWL.imports, imported),
            RdfTriple(shape, RDF.type, SHACL.NodeShape),
            RdfTriple(shape, SHACL.targetNode, target),
            RdfTriple(shape, SHACL.`property`, property),
            RdfTriple(property, SHACL.path, link),
            RdfTriple(property, SHACL.hasValue, value),
        ))
        val other = MemoryGraph(shapes("urn:ImportedShape", "urn:b", "urn:age"))
        val config = ValidationConfig(imports = imports, dataset = DatasetValidationConfig(auxiliaryGraphs = mapOf(imported to other)))
        val validator = NativeShaclValidator(config)
        assertTrue(validator.validate(MemoryGraph(listOf(
            RdfTriple(target, link, value), RdfTriple(Iri("urn:b"), Iri("urn:age"), int(25)),
        )), root).isValid)
        val invalid = validator.validate(MemoryGraph(listOf(
            RdfTriple(target, link, string("wrong")), RdfTriple(Iri("urn:b"), Iri("urn:age"), int(25)),
        )), root)
        assertEquals(listOf(target), invalid.violations.map { it.focusNode })
    }

    @Test
    fun `aliases and import cycles reuse a source namespace including nested triple terms`() {
        val p = Iri("urn:p")
        val nested = TripleTerm(RdfTriple(property, p, TripleTerm(RdfTriple(rootName, p, property))))
        val row = RdfTriple(property, p, nested)
        val alias = Iri("urn:alias")
        val root = MemoryGraph(listOf(row, RdfTriple(rootName, OWL.imports, imported), RdfTriple(rootName, OWL.imports, alias)))
        val other = MemoryGraph(listOf(row, RdfTriple(imported, OWL.imports, rootName)))
        val expanded = OwlImportsExpander.expand(root, imports, mapOf(imported to other, alias to other, rootName to root))
        val rows = expanded.find(predicate = p)
        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.subject }.distinct().size)
        rows.forEach { scoped ->
            val outer = (scoped.obj as TripleTerm).triple
            assertEquals(scoped.subject, outer.subject)
            assertEquals(scoped.subject, (outer.obj as TripleTerm).triple.obj)
        }
        assertEquals(5, expanded.size())
        assertThrows(ShaclValidationException::class.java) {
            OwlImportsExpander.expand(root, imports, mapOf(imported to other, alias to other, rootName to root), maxTriples = 4)
        }
    }
}
