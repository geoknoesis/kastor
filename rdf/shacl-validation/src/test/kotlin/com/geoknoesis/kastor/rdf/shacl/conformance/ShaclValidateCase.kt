package com.geoknoesis.kastor.rdf.shacl.conformance

import java.nio.file.Path

/** One `sht:Validate` row after walking SHACL 1.2 W3C manifests (`mf:include`, `mf:entries`). */
data class ShaclValidateCase(
    /** RDF URI of the manifest entry (typically `file:.../case.ttl#case`). */
    val entryUri: String,
    val displayName: String,
    /** TTL file that contains the entry, action graphs, and expected report. */
    val manifestPath: Path,
    val approved: Boolean,
)
