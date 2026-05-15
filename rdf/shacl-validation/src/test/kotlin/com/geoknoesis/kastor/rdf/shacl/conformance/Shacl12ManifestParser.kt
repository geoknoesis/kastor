package com.geoknoesis.kastor.rdf.shacl.conformance

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.Resource
import org.apache.jena.rdf.model.Statement
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.RDFS

/**
 * Collects `sht:Validate` tests from W3C SHACL 1.2-style manifests (see
 * [data-shapes shacl12-test-suite](https://github.com/w3c/data-shapes/tree/gh-pages/shacl12-test-suite)).
 *
 * Uses Jena directly so the harness stays independent of the native SHACL engine under test.
 */
object Shacl12ManifestParser {

    private const val MF = "http://www.w3.org/2001/sw/DataAccess/tests/test-manifest#"
    private const val SHT = "http://www.w3.org/ns/shacl-test#"

    private val mfManifest = MF + "Manifest"
    private val mfInclude = MF + "include"
    private val mfEntries = MF + "entries"
    private val mfStatus = MF + "status"
    private val shtValidate = SHT + "Validate"
    private val shtApproved = SHT + "approved"

    fun collect(rootManifest: Path): List<ShaclValidateCase> {
        check(Files.isRegularFile(rootManifest)) { "manifest not found: $rootManifest" }
        val visited = mutableSetOf<Path>()
        val out = mutableListOf<ShaclValidateCase>()
        parseInto(rootManifest.toAbsolutePath().normalize(), visited, out)
        return out
    }

    private fun parseInto(manifest: Path, visited: MutableSet<Path>, out: MutableList<ShaclValidateCase>) {
        if (!visited.add(manifest)) return
        val baseUri = manifest.toUri().toString()
        val model =
            ModelFactory.createDefaultModel().also { m ->
                manifest.toFile().inputStream().use { stream ->
                    m.read(stream, baseUri, "TURTLE")
                }
            }

        val manifestType = model.createResource(mfManifest)
        val includesProp = model.createProperty(mfInclude)
        val entriesProp = model.createProperty(mfEntries)

        val roots = model.listResourcesWithProperty(RDF.type, manifestType).collectResources()
        for (root in roots) {
            for (stmt in root.listProperties(includesProp).collectStatements()) {
                val obj = stmt.`object`
                when {
                    obj.isURIResource -> {
                        val includedUri = obj.asResource().uri
                        uriToPath(includedUri, manifest)?.takeIf { Files.isRegularFile(it) }?.let {
                            parseInto(it, visited, out)
                        }
                    }
                    else ->
                        rdfListToResources(model, obj).forEach { node ->
                            if (!node.isURIResource) return@forEach
                            val includedUri = node.asResource().uri
                            uriToPath(includedUri, manifest)?.takeIf { Files.isRegularFile(it) }?.let {
                                parseInto(it, visited, out)
                            }
                        }
                }
            }

            for (stmt in root.listProperties(entriesProp).collectStatements()) {
                val obj = stmt.`object`
                when {
                    obj.isURIResource -> addValidateEntry(model, obj.asResource(), manifest, out)
                    else ->
                        rdfListToResources(model, obj).forEach { entry ->
                            addValidateEntry(model, entry, manifest, out)
                        }
                }
            }
        }
    }

    private fun addValidateEntry(
        model: Model,
        entry: Resource,
        manifest: Path,
        out: MutableList<ShaclValidateCase>,
    ) {
        val validateType = model.createResource(shtValidate)
        if (!entry.hasProperty(RDF.type, validateType)) return
        if (!entry.isURIResource) return
        val entryUri = entry.uri

        val statusProp = model.createProperty(mfStatus)
        val statusStmt = entry.getProperty(statusProp)
        val approved =
            statusStmt == null ||
                (statusStmt.`object`.isURIResource &&
                    statusStmt.`object`.asResource().uri == shtApproved)

        val labelStmt = entry.getProperty(RDFS.label)
        val displayName =
            when {
                labelStmt != null && labelStmt.`object`.isLiteral ->
                    labelStmt.`object`.asLiteral().lexicalForm
                else -> entryUri.substringAfterLast("#").ifEmpty { entryUri }
            }

        out.add(
            ShaclValidateCase(
                entryUri = entryUri,
                displayName = displayName,
                manifestPath = manifest,
                approved = approved,
            ),
        )
    }

    private fun rdfListToResources(model: Model, head: RDFNode): List<Resource> {
        if (!head.isResource) return emptyList()
        val out = mutableListOf<Resource>()
        var node: RDFNode = head
        val first = model.createProperty(RDF.first.uri)
        val rest = model.createProperty(RDF.rest.uri)
        while (node.isResource && node.asResource().uri != RDF.nil.uri && node != RDF.nil) {
            val cur = node.asResource()
            val firstStmt: Statement = cur.getProperty(first) ?: break
            if (firstStmt.`object`.isResource) {
                out.add(firstStmt.`object`.asResource())
            }
            val restStmt: Statement = cur.getProperty(rest) ?: break
            node = restStmt.`object`
        }
        return out
    }

    private fun uriToPath(iri: String, manifest: Path): Path? =
        try {
            val uri = URI.create(iri)
            val resolved = if (uri.isAbsolute) uri else manifest.toUri().resolve(uri)
            if (resolved.scheme == "file") {
                Path.of(resolved)
            } else {
                null
            }
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Resolve `sht:dataGraph` / `sht:shapesGraph` IRIs from W3C tests (often same Turtle document `<>`. */
    internal fun graphUriToPath(graphUri: String, manifest: Path): Path? = uriToPath(graphUri, manifest)
}
