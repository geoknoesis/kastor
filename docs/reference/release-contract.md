# Release contract and verification

Kastor is an experimental Kotlin/JVM library targeting JDK 21. Changes in this release affect graph lifecycle, generated type names, and failure handling. Recompile consumers and regenerate ontology sources when upgrading. API baselines establish the contract going forward; they do not establish binary compatibility with earlier published jars.

| Capability | Bundled memory | Jena | RDF4J | HTTP SPARQL |
|---|---|---|---|---|
| Local transaction rollback | Yes, snapshot under a repository lock | Yes, backend transaction | Yes, backend transaction | Unsupported; throws |
| Read transaction rejects mutation | Yes | Yes | Yes | Unsupported; throws |
| SPARQL | No | Yes | Yes | Endpoint-dependent |
| Inference | No | RDFS variants | RDFS variants | Endpoint-dependent, not advertised |
| Persistence | No | TDB2 variants | Native variants | Endpoint-managed |
| Scoped SELECT cursor | Eager fallback where supported | Yes | Yes | Incremental JSON rows |
| Bound and timed SELECT | Unsupported | Yes | Yes, seconds resolution | Unsupported |
| RDF triple terms | In-memory values | Backend/format-dependent | Backend/format-dependent | Explicitly rejected by this adapter |

`createGraph` returns an idempotent graph view. Empty named graphs are not portably durable: use `hasGraph`/`listGraphs` to discover graphs containing statements. Graph views obtained from local repositories reject operations after the repository closes. Nested transactions join the outer transaction; catching an inner exception inside the outer block does not create a savepoint. Close repositories outside active transactions. `clear` on a graph affects only that graph; repository `clear` affects the dataset. Dataset imports append transactionally.

Use `withSelectRows(query) { rows -> ... }` or `withConstructTriples(query) { triples -> ... }` for scoped consumption. Do not return the sequence from the callback: backend resources close when the callback returns. Existing `select`, `construct`, and `describe` APIs collect results before returning. For parsing, use `Rdf.openTripleStream(input, format).use { triples -> ... }`; the scoped parser owns and closes the supplied input. The legacy sequence parser is eager so abandoning an ordinary sequence cannot retain a parser.

The HTTP adapter limits responses to 32 MiB by default and has finite connect/read timeouts. Increase the constructor limit explicitly for larger trusted results. A connected blank-node component must be passed in one `addTriples` call; single-triple blank-node insertion and blank-node constant deletion are rejected because labels do not identify existing remote nodes across requests. Use an explicit SPARQL `DELETE WHERE` pattern for remote blank-node deletion. Multi-request operations do not provide atomic read/modify/write semantics.

Native SHACL validates a requested focus node against the full data graph. `maxViolations` caps stored report entries while validity considers every evaluated severity; `violationsTruncated` reports omitted results. Timeouts are cooperative through traversal, regex input, and backend query execution. They are not a hard process deadline for arbitrary third-party code. Combined graph limits include expanded/imported shapes. Compiled shapes and version tags have a validator-local LRU capacity of 64. `ShapeCacheControl` exposes cache statistics and explicit invalidation. Evicted tags no longer retain stale-version detection history. Native parallel/streaming options throw when requested. Convenience presets use supported options. Core validation constructs a query dataset only when a SPARQL constraint needs it.

HermiT separates consistency, classification, and materialization. It disposes engine resources on exit and applies engine task timeouts and bounded materialization/serialization. Its memory setting is an admission/output budget, not a JVM heap quota. For hard memory or wall-clock isolation, run reasoning in a separately limited process. Incremental reasoning, result caching, custom rules, and streaming are explicitly unsupported in this adapter.

Generated domain classes use JSON-LD type aliases consistently. Each ontology owns a separate output directory and manifest; regeneration deletes obsolete files listed in that manifest. Separate interface/wrapper packages are supported. Properties whose target has no generated shape fall back to IRI strings. Generated delegates and extras are memoized snapshots after first access; materialize a fresh wrapper after changing data. The concurrent factory registry uses last-write-wins registration for compatibility; applications should register a single factory per interface and remove class-loader-specific entries when unloading plugins.

Similarity search uses an exact metric tree and validates finite, consistently sized, normalized vectors. Pruning depends on dimension and distribution; dense result sets remain quadratic. No approximate recall claim or production throughput improvement is implied without workload measurements. Isomorphism uses compact refinement plus exact blank-node matching and throws after its configurable search-state budget on difficult symmetric graphs.

Model downloads are pinned to an immutable upstream revision, hash-verified where expected hashes are supplied, and published atomically under a cache lock. Embedding inference and close share a lock; the model owns its tokenizer and ONNX session. The default URL loader admits four workers and 64 queued requests, returning an exceptional future on overload. Cancelling a future closes active HTTP I/O.

## Local release checks

1. Install JDK 21. Run `./gradlew check conformanceSmokeTest` with `KASTOR_SKIP_EMBEDDING_TESTS=1` and `KASTOR_SKIP_OPENAI_LLM_TESTS=1` for deterministic offline-service tests.
2. Run `./gradlew jacocoTestReport`; XML and HTML reports are under each module's build reports directory. Coverage is diagnostic; no arbitrary percentage threshold is enforced.
3. Run `./gradlew publishAllPublicationsToStagingRepository`. It writes only `build/release-repository`. Published Kotlin modules include Dokka HTML in their documentation jars and shared POM metadata. Set `KASTOR_SIGNING_KEY` and `KASTOR_SIGNING_PASSWORD` to sign staging artifacts. No public repository credentials or upload destination are assumed.
4. Check the staged BOM and jars from an independent consumer. The Gradle plugin TestKit suite additionally compiles/runs generated interfaces and exercises input changes and shape removal.
5. Review API changes before deliberately updating checked-in baselines with `./gradlew updateKotlinAbi`. `checkKotlinAbi` is part of module checks. Dependency changes require reviewed lockfile updates (`--write-locks`) and verification metadata updates (`--write-verification-metadata sha256`). Checksums protect against changed artifacts; they do not certify dependency safety.
6. Run the pinned full conformance workflow before a public release. It verifies minimum executed counts. Known backend limitations are reported as skipped tests and must remain visible in release notes. CI runs JDK 21 on Linux and Windows; external embedding/LLM integrations remain opt-in.

TDB memory-mapped files can remain undeletable on Windows until JVM exit, so persistent test directories are cleaned by the enclosing CI runner lifecycle. See the [Jena TDB FAQ](https://jena.apache.org/documentation/tdb/faqs.html). ABI and documentation configuration follows the [Kotlin compatibility guide](https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html) and [Dokka guide](https://kotlinlang.org/docs/dokka-gradle.html).
