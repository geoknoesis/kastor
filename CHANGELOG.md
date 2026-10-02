# Changelog

All notable changes to Kastor are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project adheres
to semantic versioning where every breaking change bumps at least the minor
version while we are in 0.x. What counts as breaking (source, binary, behavioural),
the deprecation policy and the supported JDK/Kotlin versions are defined in the
[compatibility policy](docs/reference/release-contract.md#compatibility-policy).

## [Unreleased]

Version on `main`: `0.3.0-SNAPSHOT`. Nothing from this section has been published yet.

Tags: releases are tagged `vX.Y.Z`. The historical tag `0.2.1` (no `v` prefix) predates this convention
and is kept as is; it was never published to Maven Central.

### Changed (dependencies)

- Runtime dependencies seen by consumers: kotlinx-coroutines 1.11.0, kotlinx-serialization-json 1.11.0, KotlinPoet 2.4.0, SLF4J 2.0.20, RDF4J 5.3.2, clikt 5.1.0, ONNX Runtime 1.30.0 with DJL tokenizers 0.38.0 (`onto-quality-embed`), httpcore5 5.4.4 and lz4-java 1.12.0.
- Build only: Gradle 9.8.0, JUnit 6.1.3, dependency-analysis 3.19.2 (the `kotlin-metadata-jvm` buildscript pin is gone), JaCoCo 0.8.15.
- RDF4J 6 is not adopted: it requires Java 25 and removes the RDF-star `Triple` model (see `docs/reference/dependency-upgrade-plan.md`).

### Fixed (round eight)

#### Breaking changes

- **Modification stamps (`VersionedRdfGraph`):** the stamp now identifies the content the calling thread would read. A thread inside its own write transaction reads transaction-private stamps; other threads keep the committed stamp until the commit completes; a rollback does not change the committed stamp. The memory, RDF4J and Jena providers follow this contract (its eight rules are in the KDoc).
- **`rdf-core`:**
  - A declared prefix always wins in `QNameResolver` (a prefix named `data`, `urn` or `file` used to be ignored); built-in prefixes no longer capture well-known URI schemes such as `geo:` or `mailto:`.
  - `Rdf.repository { inference = true }` (or a variant the provider lacks) throws `RdfProviderException` instead of silently returning a store without it.
  - `parseFromInputStream`, `parseStreaming` and `parseDataset(repo, stream)` never close the caller's stream.
  - `TypedLiteral` and `Literal(x, rdf:langString)` without a language tag throw; `decimal(Float)` uses the float's shortest decimal form.
  - URL loading: the helper cap is `max(32, 4 x processors)` (`kastor.url.helperThreads`); abandoned work is interrupted; opted-in non-HTTP schemes run under the deadline; an interrupt surfaces as `InterruptedIOException`.
  - `rdf-testkit` isomorphism uses the bounded check and throws `GraphIsomorphismLimitException` at a limit.
- **`rdf-rdf4j`:**
  - `Rdf4jRepository` no longer implements `DescribesQueryDataset` (it was untrue for wrapped remote repositories), so `Dataset.describe` filters its results again.
  - `BlankNode("_:a")` and `BlankNode("a")` are one node, as in the Jena provider. Serialized blank-node labels with characters other than ASCII letters and digits are spelled differently.
  - The SHACL bridge fails (or warns, per `unsupportedFeatures`) on shapes that use features RDF4J's ShaclSail silently skips (`sh:xone`, `zeroOrMore`/`oneOrMore`/`zeroOrOne` paths, `sh:qualifiedValueShapesDisjoint`, SPARQL constraint components, other `sh:` properties it does not read). It throws `UnsupportedShaclOperationException`, honours `timeout`, and no longer truncates at ShaclSail's 1,000 results per constraint.
- **`rdf-jena`:**
  - `JenaBridge.getJenaModel` / `getJenaGraph` on a repository graph return a view whose writes go through the repository (stamps and inference views follow them); `begin`/`commit`/`abort` on it throw.
  - `urn:x-arq:DefaultGraph` is the default graph; the union graph is read-only; blank graphs created by `UPDATE` are skolemized as on RDF4J.
  - A failed inference iterator keeps failing instead of ending quietly.
  - `:rdf:conformance:test` fails without the corpus unless `-PconformanceAllowMissingData=true`.
- **`rdf-sparql`:**
  - The JSON decoder rejects raw control characters in strings, unpaired surrogates, non-string binding members, repeated members, variables not declared in `head`, and a language tag with a datatype other than `rdf:langString`.
  - Custom `Accept`, `Content-Type` and `Transfer-Encoding` headers are refused, as is an `Authorization` header together with `username` or URL credentials.
  - `SparqlEndpointConfig` has a new `malformedTerms` field (constructor and `copy` changed); `ExpressionAst` has three new subtypes; `getAs` throws for an unsupported type and checks datatype and range.
  - `kotlinx-serialization-json` is no longer a runtime dependency of `rdf-sparql`.
- **`rdf-shacl`:**
  - `[].]`, `[a--b]` and other classes XML Schema does not allow fail compilation instead of changing meaning; literal `sh:class` / `sh:nodeKind` values fail compilation.
  - Category escapes such as `\p{Lu}` stay case-sensitive under flag `i`. A float compared with a decimal bound is compared as float; `-0.0` equals `0`.
  - The pattern budget counts regex steps (`patternTimeout` at 50,000 steps per millisecond), with the wall clock as a backstop. `statistics.validatedResources` counts validated focus nodes.
- **`kastor-gen`:**
  - DSL setters no longer enforce `sh:hasValue` per call; `validate()` checks it over all values. Inherited and own `sh:pattern`, `sh:hasValue` and `sh:nodeKind` are all enforced. An empty `sh:in` rejects every value.
  - Classes named like default-imported Kotlin types get the suffix `Type`. A missing `ontologyPath` file is a KSP error. Regex translation follows the same rules as the SHACL validator.
  - The validation cache machinery (`GraphStateCache`, `HandleEqualGraph`) requires `\KastorGenInternalApi` opt-in and is out of the ABI dump; validators no longer throw `GraphStateCacheSaturatedException`.
- **`onto-quality` / `rdf-cli`:**
  - Structural detectors run on the asserted graph even with `--reasoner` (each bundled shape declares `oqsh:evaluatedOn`); P06 needs a cycle through a distinct class, so a bare `A rdfs:subClassOf A` is no longer reported.
  - P27, P34, P20, P40, P01 and deprecated-reference report the offending resource as focus node (it was `owl:Thing`), so their messages and refs change. Refs of findings with non-string literal values change.
  - Ratio metrics are always `xsd:decimal`; metrics count restrictions inside `owl:intersectionOf` / `owl:unionOf` lists.

Also binary-incompatible since round seven and not listed there: `UrlLoadOptions.copy` (7 parameters) and its 6-argument constructor, and `LlmExplanationConfig.copy` (11 parameters).

#### Added

- `rdf-sparql`: `MalformedTermPolicy.SKIP_ROW` (a result row with a term the RDF model refuses is dropped and counted in one warning); DSL support for `EXISTS` / `NOT EXISTS`, `IN` / `NOT IN`, unary minus, `!`, `GROUP BY` expressions, `SERVICE SILENT`, the empty prefix and path ranges; `asFlow` runs on an I/O dispatcher and is cancellable.
- `rdf-shacl`: a leading `(?i)`-style flag group, POSIX classes such as `[[:alpha:]]` and script names such as `\p{IsLatin}` compile with their usual meaning; `KastorShaclVocabulary.VERSION`.
- `rdf-core`: `kastor.url.helperThreads`, `kastor.url.helperAbandonGraceMillis`; `rdf-testkit` isomorphism overloads with `maxWork` / `timeout`.
- `kastor-gen`: JSON-LD keyword aliases, `\type: \vocab`, array containers.
- `rdf-cli`: `kastor-rdf diff --max-search-states`. `onto-quality`: `SecretRedaction`, `LlmProvider.honoursMaxOutputTokens`.

#### Fixed

- **Validation cache and transactions:** with the stamp contract above, a transaction that validates sees its own uncommitted writes, and no other thread is served a state cached from them. Tests run the cache against the memory, RDF4J and Jena providers from two threads.
- `rdf-rdf4j`: skolemization of blank graphs also runs when an update fails and never renames contexts that existed before; a blank node inside and outside a triple term stays one node across a serialization round trip; `DESCRIBE` of a resource bound to an RDF-star subject.
- `rdf-jena`: HermiT reports the real failure instead of relabelling it a timeout and includes serialization in the deadline; in-memory stores read stamps and graph handles without opening a transaction.
- `rdf-sparql`: the read watchdog cannot lose a wake-up when its thread is replaced and stops when the last repository closes; HTTP error bodies in messages are one printable line of at most 512 characters; service descriptions no longer invent endpoint URLs.
- `rdf-shacl`: failing focus nodes of recursive shapes are reported from the solver's own evaluation instead of a second pass; unresolved `owl:imports` produce a report warning; a deep `rdfs:subClassOf` chain under `sh:closed` no longer overflows the stack.
- `rdf-core`: the `GRAPH` rewrite no longer evaluates patterns with `FILTER`, `BIND`, `MINUS`, `VALUES` or sub-selects; variable names with combining characters are kept whole; a dataset over a repository without SPARQL support is materialized.
- `kastor-gen`: concurrent callers on a stale cache entry share one load; ontologies with anonymous class expressions generate; Jena-backed graphs are copied natively, so literals Kastor's term model rejects no longer fail validation.
- `onto-quality`: importance ranking no longer needs memory quadratic in the hierarchy (an estimate is used above 64 MiB of shared sets); a reply cut off at the token limit is reported as such without a repair call; short secrets are redacted only in credential positions; Ollama's missing output-token limit is announced.
- `rdf-cli`: the `diff` sample is deterministic and datasets share one deadline.
- Tests: URL-loading tests use socket-free connections and an injected resolver (about 100 loopback connections instead of about 640); timing-, GC- and thread-name-dependent tests across the modules use latches, injected clocks and per-instance seams.

### Fixed (round seven)

#### Breaking changes

- **`rdf-shacl` / `kastor-gen`:** in `sh:pattern`, `\w` matches `_` again (`\W` does not), as in the Java-regex engines shapes are usually written for. Java-only constructs that cannot be translated faithfully (`&&`, a nested `[` in a class, inline flag groups, unknown `\p{Is…}` blocks) are rejected when the shape is compiled.
- **`rdf-shacl`:**
  - A pattern that exceeds `patternTimeout`, or overflows the regex engine's stack, no longer aborts the run: the value gets an undecided result marked `ksh:PatternTimeout` or `ksh:PatternTooComplex`. `strictMode` keeps the exception.
  - A blank-node `sh:targetNode` that is not a node of the data graph fails under the default `unsupportedFeatures = FAIL` (it only warned). Sequence and alternative paths with fewer than two members raise `ShapeCompileException`.
  - Unsupported options of the `memory` alias throw `UnsupportedShaclOperationException`.
- **`rdf-rdf4j`:**
  - `DESCRIBE` returns the same bounded description as the Jena provider (outgoing statements and blank-node closure); incoming statements are no longer included.
  - Blank graph names created by `LOAD` or `INSERT` into `GRAPH ?g` are skolemized; blank-node contexts of a wrapped store are invisible to queries, updates and `listGraphs`.
  - Dataset export writes the reified form (one reifier per quoted triple) instead of raw store statements.
- **`rdf-jena`:** an inference view used outside its read transaction fails with `IllegalStateException`. A cancelled reader may wait up to 100 ms for its running step.
- **`rdf-core`:**
  - `Dataset.graph(name)` returns an empty graph for an unknown name (it returned the default graph).
  - URL loading no longer falls back to the calling thread when its helper threads are busy: it waits within the deadline, then fails with `RdfLoadTimeoutException`.
  - `modificationStamp` of memory graphs is read without a lock and throws after the repository is closed. The pre-`redirectPolicy` bridge constructors of `UrlLoadOptions` are removed.
- **`rdf-sparql` (endpoint):** header values with control characters, characters above U+00FF or surrounding whitespace are rejected when the config is built. The JSON decoder rejects Unicode-only whitespace, invalid UTF-8, repeated `results`/`bindings`/`boolean` members and non-object bindings.
- **`kastor-gen`:** a saturated validation cache waits up to 10 s, then throws `GraphStateCacheSaturatedException`. An empty `sh:in` (after intersecting parent and child shapes) rejects every value.
- **`onto-quality` / `rdf-cli`:** refs of blank-node findings changed once more (keys now depend on a node's own structure and owner chain). `kastor-rdf` exits 3 for I/O errors in the middle of a read (was 1). The distributions' start scripts are `bin/kastor-rdf` and `bin/onto-qa`.

#### Added

- `rdf-core`: `UrlLoadOptions.addressPolicy` (`UrlAddressPolicy`, with `PUBLIC_ADDRESSES`), asked about the first URL and every redirect; `RdfAddressRefusedException`; `DescribesQueryDataset`.
- `rdf-jena` / `rdf-rdf4j`: graph handles are equal per repository and graph name, and implement `VersionedRdfGraph` for stores Kastor creates and controls. Jena: `closeTimeout` / `closeGrace` options and `blankNodeIdOfSkolemGraph`.
- `rdf-shacl`: the `ksh:` vocabulary document (`kastor-shacl.ttl`), `ksh:warning` in RDF reports, `ValidationViolation.resultStatus`.
- `kastor-gen`: `ValidationContext.validateAll(data, focuses)`, `assumeImmutable`, `\RdfMaterializable` and the `kastor.gen.materializableTypes` option, `HandleEqualGraph`.
- `rdf-cli`: `kastor-rdf diff --timeout <seconds>`. `onto-qa`: `--llm-max-output-tokens`.
- Public API added in round six and not listed then: `GraphStateCache` (kastor-gen runtime), `RdfsAxioms` (reasoning), `KastorShaclVocabulary` and `UnsupportedShaclFeature.REIFIER_CONSTRAINT_ON_COMPLEX_PATH` (SHACL), `SparqlInitialBindings.validate`, `QualityFinding.stableMessage`, `ExplanationFailure.cause`, and the Gradle task properties `shaclCandidates` / `contextCandidates`.

#### Fixed

- `rdf-core`:
  - `UrlRedirectPolicy.PUBLIC_ADDRESSES` missed carrier-grade NAT, reserved and documentation ranges and IPv4 addresses embedded in IPv6; the classification is complete now and shared with the address policy.
  - Dataset queries with codepoint escapes inside strings run in place instead of being rejected or copied into memory.
  - `Dataset.describe` filters in one read transaction with one lookup per subject and graph, and not at all for the Jena and RDF4J providers.
  - URL loading uses one helper per stream instead of one hand-off per read.
- `rdf-rdf4j`:
  - A `WITH` update whose text merely contained the word "using" lost its named graphs; the clause is detected per operation from the parsed update.
  - A dataset export followed by an import no longer yields two reifiers per annotated triple.
  - The list of graph names is cached between writes instead of enumerated per query; oversized reifier ids are resolved from a per-repository index.
- `rdf-jena`: a cancelled step that had not touched the reasoner no longer discards the shared inference view, and reads that have not returned a result restart on a fresh view; concurrent `close()` calls all wait for the store; in-memory inference views survive commits that did not write their graphs.
- Reasoners: the axiom filter applies only to rule sets that produce those axioms.
- `rdf-sparql`: very large timeouts no longer overflow after the request was sent; literals bound into RDF 1.2 triple terms, reified triples and annotation blocks are rejected up front; ignored JSON members are skipped without allocation; a leading BOM is accepted; the read watchdog survives a failing `close()`; arithmetic over a comparison is bracketed.
- `rdf-shacl`: IRI-named list cells are accepted; report warnings are serialized; the W3C harness fails a case if Kastor reports an undecided result for it.
- `kastor-gen`: Jena repository graphs go through the validation cache; a changed graph replaces its cache entry instead of adding one; `\Rdf interface X : RdfBacked` compiles again; language tags in `sh:in` compare case-insensitively.
- `onto-quality`: blank-node keys are computed only for the components that findings touch (a graph with 300,000 blank nodes and one finding describes 6); IRIs in messages are no longer rewritten; the output path is checked before any LLM call; credentials in URLs and short API keys are redacted.
- `rdf-cli`: `diff` runs the isomorphism check once.

### Fixed (round six)

#### Breaking changes

- **`rdf-rdf4j`:**
  - SPARQL queries and updates follow Kastor's dataset contract, as the Jena provider does: outside `GRAPH`, patterns read the repository's default graph only. Previously RDF4J matched them against the union of all named graphs.
  - Blank-node graph names in TriG/N-Quads are skolemized on load to `urn:kastor:skolem:<load>:<blank node id>` (the Jena provider uses the same form now; it was `urn:kastor:skolem:<uuid>`).
  - An explicit `rdf:reifies` triple is always stored as a statement, so SPARQL sees it and it survives restarts and several repository instances over one store.
  - A triple-term initial binding that contains a blank node or a directional literal is rejected with `IllegalArgumentException`.
- **`rdf-jena`:**
  - Readers of an inference view whose step was cancelled or failed get `RdfInferenceException` instead of possibly incomplete results; the transaction continues on a fresh view.
  - `close()` waits up to 10 s for readers of inference views, then logs a warning and stops them.
  - `DESCRIBE` reads the default graph only, unless the query declares a dataset.
  - A literal bound to a variable in predicate, `GRAPH` or `SERVICE` position throws `IllegalArgumentException` (all providers).
- **Reasoners (Jena, RDF4J):** only triples entailed by the empty graph are dropped as axiomatic, so vocabulary-only inferences that follow from user-asserted schema are reported again.
- **`rdf-core`:**
  - `Dataset.describe` returns only triples of the dataset's graphs.
  - On a dataset with only the store's default graph, a query whose codepoint escapes spell a quote, backslash or line break is rejected with `IllegalArgumentException`.
  - `UrlLoadOptions` has a new `redirectPolicy` (`copy` is binary-incompatible).
- **`rdf-sparql` (endpoint):** `maxResultRowChars` defaults to 4 Mi chars (was 16 Mi), JSON nesting is capped at 128 levels, malformed JSON numbers and literals are rejected, and `SparqlEndpointConfig` has a new `strictContentType` field (constructor and `copy` changed).
- **`rdf-shacl`:**
  - `sh:pattern` follows XML Schema regular expressions more closely: `^` under flag `m` matches only after a line feed, `.` matches anything but line feed and carriage return, and `\d`, `\w`, `\s` are Unicode-aware. (Round seven restored `_` as a word character.)
  - A blank-node `sh:targetNode` that is not a node of the data graph is reported (unsupported node expression, or a warning) instead of being validated as an empty node.
  - Ill-formed RDF lists in shapes raise `ShapeCompileException`.
  - `sh:reificationRequired` failures are reported under `sh:ReifierShapeConstraintComponent`; Kastor no longer emits `sh:ReificationRequiredConstraintComponent`.
  - The `memory` validator delegates to the native engine. `ValidationConfig` has a new `patternTimeout` field (default 1 s per value; constructor and `copy` changed).
- **`kastor-gen`:**
  - `@Rdf` members of type `Short` or `Instant`, wrapper-name collisions and un-annotated abstract members are KSP errors.
  - `var` members read under the policy captured when the wrapper was created, like `val` members.
  - Registering a different factory class for the same interface throws unless `replace = true`.
  - `ShaclInValue` and `PropertyConstraints` gained a field; the Gradle task's `shaclFile`/`contextFile` are unset by default (use `shaclInput`/`contextInput`).
- **`onto-quality` / `rdf-cli`:**
  - Finding refs and `focusNode` keys of blank-node findings changed (they are now unique within a report).
  - `onto-qa`: unreadable input exits 5 (was 2); `--format turtle --explain` is a usage error; an output path equal to the input is refused without `--overwrite-input`.
  - `kastor-rdf`: JVM errors exit 3 (was 1); `diff` uses the core isomorphism check and its 60-second limit.

#### Fixed

- `rdf-cli`: `kastor-rdf` starts again (the build named a main class that did not exist). `onto-qa`'s Windows start script no longer exceeds the command-line limit.
- Both CLIs write stdout and stderr as UTF-8, and write output files atomically.
- `rdf-core`:
  - The `GRAPH` rewrite for default-graph-only datasets handles `GRAPH` directly after `}`, leaves `GRAPH` inside `SERVICE` alone, and its tokenizer no longer misses `GRAPH`/`FROM` after numbers, booleans or codepoint escapes.
  - Redirect `Location` values with an empty base path, spaces, `|` or non-ASCII characters resolve correctly.
  - `modificationStamp` reads are consistent between graph kinds.
- `rdf-jena`: a cancelled inference step now discards the shared view (the check was inverted); idle checks no longer pile up in the timer queue; inference models are cached per write transaction; skolem graph names need no lookup table.
- `rdf-rdf4j`: quoted triples with very large literals can be read (hash-based reifier ids); a reifier blank node used as an object round-trips; fewer full scans for `rdf:reifies` lookups; engine assertion errors surface as `RdfQueryException`.
- Reasoners: a failed thread start no longer leaks a concurrency permit.
- `rdf-sparql`: the header wait no longer relies on the JDK request timer (which covers the body from JDK 26); one header budget across redirects; `ORDER BY` with nested comparisons renders valid SPARQL; `(1AS ?x)` is tokenized correctly; result rows are decoded in one pass.
- `rdf-shacl`: `sh:targetNode` expressions in the `sparql:` namespace are recognised; failing reifiers are reported one result each, naming the reifier; reifier constraints on complex paths are reported as unsupported; undefined-recursion results carry `ksh:resultStatus ksh:UndefinedRecursion` in RDF reports; the DSL can express property paths.
- `kastor-gen`:
  - RDF4J and Jena validation share a cache that never holds its lock while reading the graph (it could deadlock with a repository transaction), matches graph handles by equality, keeps 16 graphs by default (`kastor.validation.{rdf4j,jena}.maxCachedGraphs`) and never evicts or waits on a graph in use.
  - Generated interfaces below a deactivated parent shape compile; `sh:in` compares full RDF terms (language tags and datatypes); the factory registry no longer pins class loaders.
- `onto-quality`: `--debug` prints the cause of a failed LLM request; LLM error text and prompt fields are capped; labels are tokenised per batch.
- Tests: two tests that never ran (their bodies returned a value) run now.

### Fixed (round five)

#### Breaking changes

- **`rdf-core` (URL loading):** redirects are followed by Kastor, not the JDK: at most 10 hops sharing one deadline, same scheme or `http`→`https` only (previously the JDK refused `http`→`https`). The deadline now also covers DNS lookup, connect and TLS. Timeouts caused by the deadline raise `RdfLoadTimeoutException`.
- **Initial bindings:** Jena now rejects the same queries RDF4J does (`IllegalArgumentException`): a bound variable assigned by `BIND`, `VALUES` or `AS`, one local to a sub-select that does not project it, and non-SELECT queries.
- **Reasoners:** Jena and RDF4J no longer report inferred triples made only of `rdf:`, `rdfs:`, `owl:` and `xsd:` terms (such as `xsd:integer a rdfs:Class`) unless `includeAxiomaticTriples = true`.
- **`rdf-shacl`:** in `sh:pattern`, `$` follows XPath: it matches only at the end of the value, or before a line feed with the `m` flag (so `"123\n"` no longer matches `^\d+$`).
- **`kastor-gen`:**
  - A different factory class registered for the same type from another class loader throws `IllegalStateException`.
  - `@Rdf` members typed `Set`, `Collection`, `Map`, arrays or `Sequence` fail generation with a KSP error.
  - `sh:minLength`/`sh:maxLength` count code points, not UTF-16 units, and `$` in `sh:pattern` follows XPath.
  - Nested `@Rdf` interfaces generate `Outer_InnerWrapper`.
- **`onto-quality`:**
  - The published OQuaRE formulas are emitted as `kmetrics:numberOfChildrenOquare`, `couplingBetweenObjectsOquare` and `tanglednessOquare`. The 0.2.x IRIs are deprecated and no longer emitted, so an IRI never silently changes meaning.
  - `--explain-dry-run` without `--explain` is a usage error (exit 4).
  - Finding refs and JSON `focusNode` for blank nodes are structural keys, stable across parses.
- **`rdf-cli`:** exit codes are 0 ok, 1 usage or input error, 2 diff mismatch, 3 runtime error. Extra arguments are rejected.

#### Added

- CycloneDX SBOMs (`-cyclonedx.json`, `-cyclonedx.xml`) are published for every module, and releases carry a build provenance attestation.
- `scripts/configure-github-release.sh` also turns on vulnerability alerts, Dependabot security updates, secret scanning with push protection and private vulnerability reporting (opt out with `--no-security-features`).
- The POM lists `developerConnection`, issue management and the developer URL.
- A 0.x compatibility policy in `docs/reference/release-contract.md`, linked from this file's header.
- Jena inference views have a configurable idle timeout (`viewIdleTimeoutMillis`, default 1 s).

#### Fixed

- `rdf-core`: URL loading uses at most 32 daemon helper threads and always disconnects; the eager base-IRI fallback no longer keeps a test-only counter.
- `rdf-shacl`: refinement of recursive components with negative dependencies is incremental (near-linear instead of quadratic).
- `rdf-jena`: `close()` on an inference repository no longer fails with `NoSuchElementException` when an idle inference view retires itself while `close()` collects the open views.
- `rdf-jena` / `rdf-rdf4j`: initial bindings for blank nodes, directional literals and triple terms follow the shared rewrite on RDF4J; reasoner permits are released only after an abandoned worker has cleaned up; closing a Jena repository ends its inference-view workers.
- `kastor-gen`: every `@Rdf` wrapper reader follows `MaterializationPolicy`; named-graph views of memory repositories carry modification stamps, so RDF4J validation skips digests for them.
- `onto-quality`: `--debug` prints the stack trace of a failed explanation; LLM prerequisites are checked before any model is loaded; `--explain-dry-run` needs neither the opt-in nor an API key.
- `rdf-cli`: runtime errors print one line, and Jena warnings reach stderr.
- README samples at the start of a section import everything they use, and CI checks them.
- Security: Jackson is 2.22.3 (GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54), and the Dokka tool classpath uses FreeMarker 2.3.35 (GHSA-27j2-h3m2-8237; build-time only, not published).
- Published metadata: `rdf-rdf4j` declared `at.yawk.lz4:lz4-java` and `kastor-gen-gradle-plugin` declared `kotlin-stdlib` without a version (the version came from the unpublished build platform), so neither resolved outside this build. Both now carry versions, and the staged-publication gate rejects any versionless dependency in a POM or Gradle module file.

### Fixed (round four)

#### Breaking changes

- **`rdf-core`:**
  - Valid blank node ids that start with `ux_` are written with one extra `_` (`ux_x` → `ux__x`), so labels are injective again. Such labels grow by one character per parse/serialize round trip; other valid labels are unchanged.
  - Graph isomorphism limits throw the new `GraphIsomorphismLimitException` (a subclass of `IllegalStateException`) with a `reason`.
  - A dataset whose only default graph is the store's own default graph rejects `GRAPH` usage it cannot analyse with `IllegalArgumentException`, instead of copying the store.
- **`rdf-sparql` (endpoint):**
  - `SparqlEndpointConfig` has a new `maxResultRowChars` field (default 16 Mi chars); code compiled against the old constructor or `copy` must be recompiled.
  - Redirects from `https` to `http` are always refused.
  - SELECT/ASK responses whose `Content-Type` is not JSON (or `text/plain` for ASK) raise `RdfQueryException`.
  - Endpoint URLs with a `#fragment` are rejected when the config is built.
- **`rdf-shacl`:**
  - Results for undecidable recursion keep the severity and component of their constraint (previously always `sh:Warning`) and are marked with `isUndefinedRecursion`. They block conformance whenever a failure of that constraint would.
  - `[ ex:fn ( … ) ]` is treated as a node expression only when `ex:fn` is a declared SHACL function or in the `shnex:` namespace.
- **`rdf-jena`:** blank-node graph names in TriG/N-Quads are skolemized to `urn:kastor:skolem:<uuid>` graph IRIs on every load path (previously they crashed some paths and were dropped on others).
- **`rdf-rdf4j`:** quoted-triple nesting deeper than 64 levels, or reifier ids longer than 1 MiB, fail strict reads and are skipped by lenient reads.
- **`kastor-gen`:**
  - Generated wrappers capture the ill-typed-value policy when they are created (`MaterializationPolicy.lazyWithCurrentPolicy`), so `withIllTypedValues` applies to properties read after the block.
  - Generated validation applies `sh:pattern`, `sh:minLength` and `sh:maxLength` to IRIs, reports blank nodes, compares `sh:in` as RDF terms, and DSL builders report values that cannot be compared with numeric bounds.

#### Fixed

- `rdf-core`:
  - `GRAPH` queries on a dataset with only the store's default graph run in place on the source store (the patterns are rewritten to match nothing) instead of copying the whole store into memory.
  - URL loading no longer allocates a buffer per read.
  - New optional `VersionedRdfGraph.modificationStamp`, implemented by in-memory graphs.
- `rdf-sparql`:
  - `streamingRequestTimeout` also bounds the wait for response headers.
  - A single oversized result value or row is capped (`maxResultRowChars`).
  - `SELECT*WHERE{` and `DISTINCT*{` in sub-selects are recognised when applying initial bindings.
  - `SparqlGraph.size()` reports out-of-range counts as `RdfQueryException`.
  - A warning is logged when a followed cross-origin redirect drops custom headers or credentials.
- `rdf-shacl`:
  - `sh:reifierShape` no longer requires a reifier unless `sh:reificationRequired true` is set.
  - The recursion solver recovers from reads it did not record instead of failing an internal check.
- `rdf-jena`:
  - Inference views stream results in chunks instead of draining every match under a lock; readers take turns, query timeouts and interrupts stop the reasoner's store reads, and named graphs are prepared only when queried.
  - Dataset loads buffer at most 10,000 triples across all graphs.
- `rdf-rdf4j`:
  - An explicit `rdf:reifies` triple survives removal of the annotations that implied it, and is not brought back after it was removed.
  - SPARQL updates force a quoted-subject rescan only when they can create quoted subjects, and the scan result is cached inside a transaction.
  - `size()` uses the store's count for lenient repositories created by a factory, until a SPARQL update runs.
  - RDFS inference commits run under a deadline and a concurrency cap, like the Jena and HermiT reasoners.
- `kastor-gen`:
  - RDF4J validation checks a graph's modification stamp before hashing its content, and caches up to 4 graphs with a lock each, so validating different graphs no longer serialises and repeated validation of an unchanged graph is cheap.
  - Deactivated shapes no longer contribute constraints when merged with active ones or inherited.
  - The Gradle plugin's file-replacement strategy is kept out of the configuration cache.

### Fixed (round three)

#### Breaking changes

- **`rdf-core`:**
  - A saturated `parseFromUrlAsync` executor now returns a failed future (`RejectedExecutionException`) instead of running the load on the caller's thread.
  - Graph isomorphism has a 60-second default time limit again. Pass `timeout = null` for no limit.
  - `Rdf.memory()` skips providers whose `memory` variant declares no SPARQL support.
  - `BlankNode.toString()` writes invalid ids as `ux_` followed by 4 hex digits per UTF-16 unit; valid labels are unchanged.
- **`rdf-sparql` (endpoint):**
  - An explicit per-call `timeout` now bounds the whole call (headers, body and row consumption).
  - When `requestTimeout` is null, `readTimeout` bounds the wait for response headers.
  - HTTP clients are shared across repositories, so close repositories when done.
- **`rdf-sparql-lang`:** these now throw instead of rendering invalid SPARQL:
  - `SelectQueryAst` with a negative LIMIT/OFFSET, `*` mixed with other projection items, or `*`/an empty projection together with GROUP BY or HAVING;
  - `ValuesPatternAst` rows whose length differs from the variable count;
  - an `AggregateExpressionAst` with a null `expression` other than COUNT, or a `separator` other than GROUP_CONCAT;
  - `using`/`usingNamed`/`with` on update operations other than DELETE/INSERT;
  - CLEAR/DROP with both a graph and a non-DEFAULT scope.

  Source-level changes: `ValuesPatternAst.values` is now `List<List<RdfTerm?>>`, and COPY/MOVE/ADD `source`/`destination` are `Iri?` (binary bridges to the 0.2.1 signatures are kept).
- **Build and release:**
  - `publish.yml` signs and uploads the exact artifacts staged by the release-readiness steps in the same run, and refuses to upload while a previous deployment for the tag is still live.
  - `scripts/configure-github-release.sh` requires `dependency-review` only when the Dependency graph is enabled, and gives admins bypass on `main` by default.

#### Fixed

- `rdf-core`:
  - Graph isomorphism on star-shaped graphs is near-linear; stars with 50,000 identical or distinguishable children now match quickly.
  - Dataset queries using `GRAPH` no longer see the source repository's named graphs.
  - Blank node labels are injective for lone surrogates and no longer grow on round trips.
  - URL loading enforces its total deadline across connect, headers and body.
  - Added `Rdf.parse(String, String, String?)`.
  - Providers without base-IRI streaming log a one-time warning instead of silently parsing eagerly.
- `rdf-sparql`:
  - `COUNT(*)` is no longer taken for `SELECT *` when applying initial bindings.
  - Bound projected variables are always rewritten, including those exposed through UNION/OPTIONAL sub-selects.
  - `\uXXXX` escapes and `\#` in prefixed names are tokenized correctly.
  - `addTriples`/`removeTriples` validate every batch before sending, and report partial progress on server failure.
  - Stream deadlines are tracked without a global scheduler.
  - Service descriptions use the core blank-node label encoding.
- CI:
  - The advisory `buildHealth` step reports failures as warnings and job-summary entries.
  - New checks keep the `kotlin-metadata-jvm` pin in step with the Kotlin version and README snippets in step with their imports.
  - `dependency-review` skips GitHub's review when the Dependency graph is unavailable.
  - GitHub Actions are upgraded (pinned by SHA), and the JMH plugin is 0.7.3.

#### Added

- `rdf-sparql-contract`: `com.geoknoesis.kastor.rdf.sparql.internal.SparqlInitialBindings`, shared Jena-style initial-binding substitution (non-API).
- `scripts/check-build-pins.py`, `scripts/check-readme-imports.py`, `scripts/regenerate-verification-metadata.sh`.

#### Ontology quality (`onto-quality-*`)

- **Breaking:**
  - The default base IRI for CLI input is `urn:onto-qa:input/<file name>` instead of the file URI, so finding IRIs and `findingRef` values no longer depend on the checkout location. Use `--base-iri` to override.
  - NOCOnto, CBOOnto and TMOnto now follow the published OQuaRE definitions (Duque-Ramos et al., 2016); their values and scores change. The previous formulas remain available as clearly labelled Kastor-adapted metrics (`owl.kastorAdapted` in JSON, `kastor-m:KastorAdaptedScoring` in Turtle).
  - Exit codes: a directory argument and an unavailable HermiT reasoner are usage errors (4). `benchmarks/shacl/era-cli` follows the 0/2/4/5 convention.
- **Fixed:**
  - CLI warnings are visible: user-facing warnings print sanitised on stderr, and the CLI ships an SLF4J binding (WARN, stderr).
  - `--explain` without the LLM environment flag warns, and fails with exit 3 under `--fail-on-explain-error`.
  - LLM circuit breaker: exhausted retries count toward the breaker, `insufficient_quota` and TLS/certificate errors are not retried, and HTTP status codes are read from more message formats.
  - Output sanitising: stderr messages are sanitised, Markdown escapes `@` to prevent email autolinks, and bidi controls are shown as visible escapes.
  - Similarity-limit errors say which limit was reached and suggest the matching option.
  - Cycle participants no longer rank as highly as roots in importance.
  - VoID `distinctObjectCount` counts RDF 1.2 triple terms.
  - OWL Micro and OWL RL profiles are distinguished by tests.
- **Added:** `--base-iri`, `--similarity-max-pairs`, `--debug` accepted after the subcommand, `OutputSanitizer`, `KastorAdaptedMetrics`, `SimilaritySearchBudgetExceededException.limit`.

#### SHACL (`rdf-shacl-validation`, `rdf-shacl-dsl`)

- **Breaking:**
  - Recursive shapes that depend on themselves only through monotone operators (`sh:node`, `sh:and`, `sh:property`, `sh:or`, `sh:someValue`, `sh:qualifiedMinCount`) now conform under a greatest fixpoint. Only cycles through `sh:not`, `sh:xone`, `sh:qualifiedMaxCount` or disjoint qualified siblings are undefined.
  - A `sh:targetWhere` that cannot be decided now produces a blocking `sh:Warning` result (or throws in strict mode) instead of an informational warning.
  - `ValidationConfig` gained `conformanceDisallows: Set<Iri>?`. This is source-compatible, but the constructor and `copy` have new binary signatures.
  - Deprecated DSL digit setters (`totalDigits`, `fractionDigits`) throw `UnsupportedOperationException` instead of doing nothing.
- **Fixed:**
  - Conformance uses three-valued logic, so results no longer depend on operand or constraint order.
  - Definite violations are kept when other values are undefined, and qualified counts use lower and upper bounds.
  - Dependencies for the recursion solver are recorded during evaluation; an unrecorded read restarts the solve instead of defaulting to "conforms".
  - Unsupported-feature detection only inspects nodes reachable from shapes, so `validate(g, g)` with blank-node targets no longer fails.
  - SHACL-SPARQL runs in place only when the dataset lists no named graphs; otherwise it uses a copy.
  - Fewer re-evaluations for recursive shapes, structured memo keys, and `sh:targetWhere` skips candidates excluded by `sh:class`, `sh:nodeKind` or `sh:datatype`.
  - `sh:lessThan` / `sh:lessThanOrEquals` sort values once when they are totally ordered.
  - The W3C harness requires the expected failure category for known deviations and `sht:Failure` cases, uses the engine's conformance for `conformanceDisallows` cases, and enforces required suites for manifest overrides.
- **Added:** `UnsupportedShaclFeatureException` (with `features`), the `UnsupportedShaclFeature` enum, `SparqlPreBindingRestrictionException`, and JMH workloads for monotone recursion and `sh:targetWhere`.

#### Code generator (`kastor-gen-*`)

- **Breaking:**
  - Processor model: `ShaclProperty` / `PropertyConstraints` numeric bounds are `BigDecimal` instead of `Double`.
  - Processor model additions:
    - `severity`, `message` and `deactivated` on `ShaclProperty`
    - `deactivated` on `ShaclShape`
    - `enumKind` and `typePackage` on `PropertyModel`
    - `PropertyType.TERM`
    - `RdfEnumKind`
  - Generated code: `sh:BlankNodeOrIRI` / `sh:BlankNode` members are typed `RdfResource` instead of `String`, so blank nodes are no longer dropped.
  - Generated readers throw `MaterializationException` by default for values of an unexpected term kind.
  - Generated EXTERNAL-mode validators are shared across wrapper types (`SharedValidators`; close with `close(type)` / `closeAll()`).
  - `NestedMode.IRI_ONLY` combined with `dataClassImplementsInterface = true` is rejected when a shape has object members, naming those members.
  - Deprecated (WARNING) in favour of `rdfLiteral` / `rdfLiteralOrNull` / `rdfLiterals`, because they silently drop ill-typed values:
    - `rdfString`, `rdfInt`, `rdfDouble`, `rdfBoolean`
    - their `OrNull` and list variants
- **Fixed:**
  - The RDF4J validation adapter reloads when content changes (order-independent SHA-256 triple digest, so hash collisions can no longer hide changes), and reloads are atomic.
  - Cardinality checks count values of every term kind, so literal `sh:in` enums no longer fail `sh:minCount` or miss `sh:maxCount`.
  - Hand-written `@Rdf` interfaces support these member types, read through the policy-aware decoders:
    - `Long`, `Float`, `BigInteger`, `BigDecimal`, `LocalDate`, `LangString`
    - Kotlin and generated `sh:in` enums
    - `Iri` and `RdfResource`
  - Missing required values on hand-written `@Rdf` interfaces throw `MaterializationException`.
  - Numeric bounds are compared exactly in wrapper validation, DSL setters and DSL `validate()`.
  - `sh:pattern` translation follows XPath:
    - flag `x` strips whitespace itself;
    - `\d`, `\w` and `\s` follow XPath definitions;
    - `q` ignores `m`/`s`/`x`;
    - character-class subtraction inside negated classes has the correct precedence.
  - Embedded validation honours `sh:severity`, `sh:message` and `sh:deactivated`.
  - The Gradle plugin retries locked file deletes and moves with backoff, keeps the manifest consistent until every move succeeds, handles cross-drive moves, and explains how to recover on failure.
  - Factory registration from a different class loader replaces the stale factory instead of failing class initialisation.
  - Generated DSL enum types are package-qualified correctly for packages with upper-case segments.
  - The runtime loads without SLF4J on the class path (loggers initialise lazily).
- **Added:**
  - `MaterializationPolicy.withIllTypedValues(handling) { … }` (thread-local override)
  - `MaterializationPolicy.unexpectedTerm`
  - `KastorGraphOps.getIriValues` / `getResourceValues`
  - `SharedValidators`

#### Providers and reasoning (`rdf-jena`, `rdf-rdf4j`, `rdf-*-reasoning`, `rdf-reasoning-hermit`)

- **Breaking:**
  - Graph reads on wrapped stores are strict by default. `JenaBridge.fromJenaModel` / `fromJenaGraph` and `Rdf4jRepository` throw `IllegalArgumentException` on statements Kastor cannot represent (e.g. a malformed language tag) unless lenient reads are opted into.
  - RDF4J reifier blank nodes use a reversible, checksummed id format: `kastor-star-<base64url(encoded triple)>-<8 hex digits of its SHA-256>`, so the id encodes its quoted triple. Ids with the `kastor-star-` prefix that do not decode and match the checksum are ordinary blank nodes.
  - `Rdf4jRepository.withSelectRows(query, bindings, timeout, consume)` now applies IRI and literal bindings by syntactic substitution shared with the Jena provider and the SPARQL endpoint adapter (`SparqlInitialBindings`), so results match Jena. Queries that assign a bound variable (`BIND ... AS`, `(expr AS ?v)`, `VALUES`), or use it locally inside a sub-select that does not project it, now throw `IllegalArgumentException`. Blank-node and triple-term bindings still use RDF4J's native binding.
  - Deprecated (WARNING): `rdfTripleFromRdf4j`, in favour of `rdfTriplesFromRdf4j`.
- **Fixed:**
  - RDF4J `NativeStore`: `hasTriple`, `find` and `remove` match language tags case-insensitively even when the value-id cache misses.
  - RDF4J `size()` uses the store's own count when quoted-triple subjects cannot exist or are tracked as absent. Otherwise it counts in one pass without materialising. Reifier lookups use store indexes instead of scans.
  - Writing the reified view back into an RDF-star store stores the RDF-star form again, so round trips add no duplicate statements.
  - Jena inference repositories no longer copy the RDFS closure onto the heap. A lazy inference graph is prepared once per graph and snapshot, and readers share it under a per-graph lock. Consumers never run under the lock.
  - Jena TDB2 inference views are keyed by the transaction's data version, so commits through another `JenaRepository` or TDB2 client on the same location invalidate them.
  - Abandoned Jena `openTripleStream` streams are closed by a `Cleaner`, which stops the parser thread.
  - Jena `parseDataset` streams into other transactional repositories in bounded batches.
  - Jena and RDF4J reasoners drop only the axiomatic triples, meaning the closure of the empty graph computed once per rule set. Real inferences about vocabulary terms, such as `rdfs:seeAlso rdfs:subPropertyOf ex:link`, are kept.
  - Jena rule preparation (`prepare()`) is bounded by the call's deadline. Abandoned preparations are capped, and callers that hit the cap get a "too many rule preparations in progress" error. The materialization threshold is checked right after preparation.
  - The RDF4J reasoner counts inferred statements against the threshold while reading them, and keeps `rdf:reifies` triples.
  - HermiT: OWL API loading and engine creation run together on the loader thread, bounded by the deadline. The watchdog starts before the engine exists and interrupts it as soon as it is created. In-flight loads, abandoned ones included, are capped at `MAX_IN_FLIGHT_LOADS`, and a call that cannot start a load before its deadline fails with "too many HermiT loads in progress".
  - Initial bindings applied by substitution (`SparqlInitialBindings`, used by the SPARQL endpoint adapter and now RDF4J) rewrite `BOUND(?v)` of a bound variable to `(true)` instead of the illegal `BOUND(<constant>)`, matching Jena.
  - The RDF4J and memory SHACL validators honour `ValidationConfig.conformanceDisallows` like the native engine (default: every severity except `sh:Debug` / `sh:Trace` makes the report non-conforming).
  - RDF 1.2 conformance harness:
    - Allowlist signatures keep the throwing frame for JDK exceptions.
    - `EVAL-MISMATCH` signatures include a fingerprint of the expected-vs-actual difference, with blank nodes anonymised.
    - The reference side keeps language tags exactly as written. Jena always rewrites tags such as `en-us` to `en-US`, so `lantag_with_subtag` (Turtle, TriG) is allowlisted as an upstream limitation.
- **Added:**
  - `rdfTriplesFromRdf4j(Statement): List<RdfTriple>` (full RDF 1.2 form, including `rdf:reifies` triples).
  - `Rdf4jRepository(Repository, Boolean, Boolean)` (`inference`, `lenientRead`) and the RDF4J provider option `lenientRead`.
  - `JenaBridge.fromJenaGraph(graph, strictRead)`.

### Fixed (re-audit)

#### Security

- `rdf-sparql`: redirects are handled manually.
  - 307/308 keep method and body; 301/302/303 are followed only for GET; a redirected POST is an error.
  - Cross-origin redirects are refused unless `followCrossOriginRedirects` is set, and custom headers and credentials are stripped when followed.
  - Reserved headers such as `Host` and `Content-Length` are rejected.
- `rdf-sparql-lang`: a `u`/`U` after a backslash in a literal is sent as `u`/`U`, so SPARQL 1.1 codepoint-escape pre-passes cannot alter literal text.
- `onto-quality`: ontology-derived text in Markdown reports is escaped, and control characters in text output appear as visible `\uXXXX`.

#### Breaking changes

- **`rdf-core`:**
  - `LangString.lang` keeps the tag as given; use `normalizedLang` for the lower-case form. Equality and hashing still ignore tag case.
  - A `Dataset` rejects queries that declare their own `FROM` / `FROM NAMED`.
- **`rdf-sparql-lang`:**
  - `ClearOperationAst` / `DropOperationAst` take a `GraphScope`, and COPY/MOVE/ADD source and destination are nullable (`null` = `DEFAULT`).
  - Invalid projections and blank-node label reuse across patterns are rejected.
  - `union {}` is deprecated; use `union({..},{..})` / `unionOf { branch {} }`.
- **`rdf-sparql`:**
  - The transport is `java.net.http.HttpClient`.
  - For streamed rows, `requestTimeout` covers only the wait for response headers; `streamingRequestTimeout` bounds the whole stream.
  - Non-standard service-description terms moved to `https://kastor.geoknoesis.com/ns/sparql#`.
- **`rdf-shacl-validation`:**
  - Unsupported SHACL features fail with an unsupported-feature error by default (`ValidationConfig.unsupportedFeatures`). This covers SPARQL constraint components, `sh:expression`, `sh:values` and SPARQL expressions used as targets.
  - `sh:uniqueValuesFor` reports one result per duplicate target.
- **`rdf-shacl-dsl`:** `totalDigits`, `fractionDigits`, `targetWhereSelect` and `targetWhereExpr` are deprecated at `ERROR` level.
- **`onto-quality-cli`:**
  - Exit codes are distinct: 0 ok, 1 findings, 2 parse error, 3 explanation failure, 4 usage/configuration error, 5 runtime error.
  - `--reasoner owl-micro` / `OntoQualityReasoningProfile.OWL_MICRO` now run Jena's OWL Micro rule reasoner (`ReasonerType.OWL_MICRO`) instead of the OWL rule reasoner; use `owl-rl` / `OWL_RL` for the previous behaviour.
  - NOCOnto, CBOOnto, DIT/LCOMOnto (paths now measured from `owl:Thing`) and TMOnto banding values change.

#### Fixed

- `rdf-core`:
  - File and URL parsing pass a base IRI, so relative IRIs resolve.
  - Isomorphism treats equal literals as equal (`TrueLiteral` vs `"true"^^xsd:boolean`, and tags differing only in case) and scales its work budget with graph size.
  - URL loading checks HTTP status, sends `Accept`, and applies a total timeout.
  - Memory rollback runs every undo action, and empty created graphs are listed.
  - Dataset parse failures no longer leak repositories.
  - The IRI scanner follows RFC 3987 `ucschar`.
  - `Rdf.memory()` honours provider priority.
- `rdf-sparql`:
  - Bound `withSelectRows` substitutes values Jena-style instead of appending `VALUES`, so aggregates, FILTER and sub-selects return correct results.
  - Triples connected through blank nodes are never split across batches.
  - Long GET URLs fall back to POST.
  - A stalled read is interrupted promptly, and UPDATE requests are never re-sent.
- `rdf-sparql-lang`: MINUS with a non-empty left side renders `{ left MINUS { right } }`.
- `rdf-shacl-validation`:
  - Recursive shapes are evaluated without a stack-depth limit (10,000-node chains validate).
  - Recursion through negation reports a warning instead of assuming conformance.
  - Property shapes with targets no longer apply their child shapes twice.
  - Closed shapes allow the paths of deactivated property shapes.
  - Paths use sets, respect the deadline and are capped (`maxPathValueNodes`).
  - `sh:targetWhere` also considers object nodes.
  - SPARQL pre-binding is provider-independent.
  - Language tags compare case-insensitively, and `"24:00:00"^^xsd:time` equals `00:00:00`.
  - More XSD lexical-form checks.
- `onto-quality`:
  - The CLI parses with the file URI as base IRI.
  - LLM calls are retried only on timeouts, 408/429/5xx and connection errors, with a total-duration bound and a circuit breaker.
  - Similarity limits scale with entity count.
  - A corrupted cached model is downloaded again once.
- CI: pull requests run the pinned W3C SHACL 1.2 suite (`w3cConformanceTest`) and execute every SHACL benchmark workload. The weekly Conformance workflow no longer tracks the unpinned upstream branch.

#### Added

- `rdf-core`:
  - `LangString.normalizedLang`
  - `baseIri` parse overloads and isomorphism limit overloads
  - `RdfHttpStatusException`, `RdfLoadTimeoutException`, `UrlLoadOptions.totalTimeoutMillis`
- `rdf-sparql` config options: `streamingRequestTimeout`, `maxGetUrlLength`, `maxBlankNodeComponentTriples`, `followCrossOriginRedirects`, `maxRedirects`.
- `rdf-shacl-validation`: `UnsupportedFeatureHandling`, `ValidationConfig.maxPathValueNodes`, and the `w3cConformanceTest` Gradle task.
- `onto-quality-cli` options: `--debug`, `--llm-max-duration`, `--similarity-max-work`, `--similarity-timeout`, `--similarity-mode exact|approximate`.
- Release tooling:
  - `scripts/configure-github-release.sh` applies the release environment, rulesets and Pages settings (dry run by default).
  - `publish.yml` polls Maven Central validation.

#### Code generator (`kastor-gen-*`)

- **Breaking:**
  - Ill-typed literal values now throw during materialisation by default. Use `MaterializationPolicy` with `IllTypedValueHandling` to skip them with a warning instead.
  - `OntoMapper.register` refuses a different factory for a type that is already registered, unless you pass `replace = true`.
  - `ValidationResult.orThrow()` fails only on `sh:Violation`. Use `orThrow(minimumSeverity)` to fail on warnings or info as well.
  - `xsd:decimal` values with an exponent are rejected.
  - `ValidationContext` now extends `AutoCloseable`, with a no-op default `close()`.
  - Generated embedded validation now enforces `sh:datatype`, `sh:nodeKind` and `sh:class`, and compares numeric bounds exactly.
- **Fixed:**
  - Shape inheritance generates compilable overrides. A restated path keeps the parent's member name and may narrow nullability. Refinements that Kotlin can't express as overrides (such as a list narrowed to a single value) are enforced only in validation. Conflicting parent member types fail at generation time.
  - A path used by several property shapes in one shape produces one member everywhere.
  - IRIs containing `%` no longer break the KSP `@Rdf` wrapper path, and missing values are never replaced by invented defaults.
  - `sh:pattern` XPath constructs (`\i`, `\c`, class subtraction, `\p{Is…}`) are translated. A still-invalid pattern fails generation. Regexes are compiled lazily, so a bad pattern can no longer break class loading.
  - KSP no longer reprocesses symbols it has already generated (no `FileAlreadyExistsException`). Relative `kastor.gen.resources` entries resolve against `kastor.gen.projectDir`.
  - Colliding `sh:in` enum constants get deterministic suffixes.
  - The RDF4J adapter reuses one repository, reloads only when the graph changes, and validates only the shapes targeting the focus node.
  - The Gradle plugin validates the whole output manifest before deleting anything, and stages new files before replacing old ones.
  - Projects with ontologies but without the Kotlin JVM or multiplatform plugin fail with a clear message.
  - Multiplatform projects wire the main compilation of every JVM target.
  - The JSON-LD context file is optional.
  - Generated DSL enum types are package-qualified.
  - XSD integer/decimal parsing follows the lexical rules, and dates with years above 9999 round-trip.
- **Added:** `MaterializationPolicy`, `IllTypedValueHandling`, `register(type, replace, factory)`, `orThrow(ShaclSeverity)`, and the KSP options `kastor.gen.projectDir` and `kastor.gen.resources.tracked`.

#### Providers and reasoning (`rdf-jena`, `rdf-rdf4j`, reasoning, testkit, CLI, conformance)

- **Breaking:**
  - `rdf-jena-reasoning` and `rdf-rdf4j-reasoning` now honour `ReasonerConfig`: Jena runs exactly the selected RDFS rules for a rule subset and rejects rule selections for its OWL reasoners, RDF4J rejects rule subsets its inferencer cannot apply, and both enforce `timeout` and `materializationThreshold`. Axiomatic `rdf:`/`rdfs:`/`owl:`/`xsd:` vocabulary triples are dropped from the inferred triples by default; set `parameters["includeAxiomaticTriples"] = true` to keep them.
  - RDF4J NativeStore variants no longer advertise triple-term / RDF-star support, and repositories report the capabilities of the variant they were created as.
  - `JenaBridge` throws `RdfFormatException.UnsupportedFormat` for unsupported formats instead of `IllegalArgumentException`.
  - Graphs wrapped with `JenaBridge.fromJenaModel(model)` read leniently: statements Kastor cannot represent are skipped with one warning. Use `fromJenaModel(model, strictRead = true)` for strict reads. Parsed graphs stay strict.
  - `JenaBridgeDemoKt` is no longer part of the `rdf-jena` artifact; the demo lives in `rdf/examples`.
  - Conformance allowlist rows need a fourth column: a regex for the expected failure signature. A test is skipped only when its failure matches.
- **Fixed:**
  - RDF4J reads map RDF-star quoted-triple subjects to a deterministic reifier blank node plus `rdf:reifies`, for store reads, CONSTRUCT/DESCRIBE, `parseGraph` and streaming parses, so one such statement no longer makes the whole graph unreadable.
  - The Jena inference cache is keyed by the read snapshot, so a reader can no longer cache stale inferences after a concurrent commit, and readers share the materialised closure without a lock.
  - The HermiT deadline is enforced: interrupts are repeated until the reasoner returns, every reasoner call checks the remaining budget, and ontology loading is only awaited until the deadline.
  - Conformance eval expectations are parsed by an independent Jena RIOT oracle and compared with the provider output as datasets, so a provider parser bug cannot cancel out on both sides.
  - RDF4J triple streams: `close()` from another thread wakes a blocked consumer, a `Cleaner` closes abandoned streams so their producer thread exits, and read-ahead is bounded (also for Jena).
  - SPARQL text with RDF 1.2 direction-tagged literals (`"x"@ar--rtl`) on RDF4J fails with an explicit `RdfQueryException`. CONSTRUCT/DESCRIBE failures are wrapped as `RdfQueryException`.
  - Jena graph serialisation supports the advertised TriG and N-Quads formats, `JenaBridge` file, URL and dataset loaders use the strict validating parser, and `parseDataset` streams into a Jena repository in one write transaction.
  - CLI `diff` compares datasets with one isomorphism across all graphs, so blank nodes shared between graphs must correspond.
  - The memory reasoner accepts valid XSD 1.1 lexical forms (`24:00:00`, years beyond 9999, collapsed whitespace).
- **Added:**
  - `ReasonerType.OWL_MICRO` (Jena's OWL Micro rule reasoner; other providers reject it).
  - `RdfDatasetIsomorphism` in `rdf-testkit`.
  - `JenaBridge.fromJenaModel(model, strictRead)`.
  - Jena and RDF4J providers override `openTripleStream(inputStream, format, baseIri)` and `parseStreaming(inputStream, format, baseIri)`, so relative IRIs resolve without materialising the graph.

### Breaking changes (Maven `artifactId`s)

Six artifacts that kept bare Gradle project names in 0.2.x now follow the `rdf-*` scheme used by every other RDF module. Gradle project paths are unchanged:

| Gradle project | Old `artifactId` | New `artifactId` |
|---|---|---|
| `:rdf:sparql-lang` | `sparql-lang` | `rdf-sparql-lang` |
| `:rdf:reasoning` | `reasoning` | `rdf-reasoning` |
| `:rdf:reasoning-hermit` | `reasoning-hermit` | `rdf-reasoning-hermit` |
| `:rdf:jena-reasoning` | `jena-reasoning` | `rdf-jena-reasoning` |
| `:rdf:rdf4j-reasoning` | `rdf4j-reasoning` | `rdf-rdf4j-reasoning` |
| `:rdf:shacl-validation` | `shacl-validation` | `rdf-shacl-validation` |

### Breaking changes (BOM)

- **`kastor-bom` now constrains Kastor modules only.** It no longer imports `jackson-bom`, `netty-bom` or `opentelemetry-bom`, and no longer publishes security version floors (Guava, commons-beanutils, HttpComponents, apache-mime4j, libthrift, jsoup, commons-lang3, lz4-java). Previously every Kastor module forced these onto consumers through its published metadata; for example, Netty 4.1 users were upgraded to 4.2. The build still applies them internally through the non-published `gradle/build-platform`. Declare your own floors if you relied on them ([release contract](docs/reference/release-contract.md)).
- **`kastor-bom` now includes the ontology-quality artifacts:** `onto-quality`, `onto-quality-metrics`, `onto-quality-embed`, `onto-quality-llm-koog`, `onto-quality-cli`. `:bom:verifyBomCoverage` fails the build when a published module is missing.

### Changed (toolchain and build)

- Kotlin 2.3.21 → **2.4.20** and KSP → **2.3.12**. Consumers need a compatible Kotlin compiler.
- Apache Jena 6.1.0 → **6.2.0**.
- Dependency locking for every configuration (`gradle.lockfile`, `settings-gradle.lockfile`) and SHA-256 dependency verification (`gradle/verification-metadata.xml`). `./gradlew resolveAndLockAll --write-locks` refreshes locks.
- Kotlin ABI validation (`checkKotlinAbi`, baselines under `*/api/`) and Dokka HTML documentation jars for published modules.
- The version is defined once in `gradle.properties`; release tags use `vX.Y.Z`.
- Repositories are declared only in `settings.gradle.kts` (`FAIL_ON_PROJECT_REPOS`); `mavenLocal()` was removed from the build.
- Test JVMs are capped (`kastor.test.maxHeap`, default `1g`; `kastor.test.maxParallelForks`, default `1`), and per-module JaCoCo line-coverage floors are enforced by `check`.
- Publishing: `centralBundle` builds a signed Maven Central Portal bundle and refuses unsigned or `-SNAPSHOT` releases. The tag-triggered `publish.yml` uploads it after release readiness passes.
- CI: actions pinned to commit SHAs, least-privilege `permissions`, a macOS job, executed-test floors plus a committed skip allowlist, the pinned conformance corpora fetched by `scripts/fetch-conformance-data.py` (the unused git submodule declaration was removed), and PR dependency audits scoped to newly introduced coordinates.

### Changed

- `rdf:jena`: truly streaming triple parsing via Jena `AsyncParser`.
- `rdf:rdf4j`: thread-safe per-operation connections and streaming serialization.

### Fixed (audit remediation)

#### Code generator (`kastor-gen-*`)

- **Breaking:**
  - `OntoMapper.registry` is private; use `OntoMapper.register` / `unregister` / `isRegistered` / `registeredTypes`. Code generated by earlier versions must be regenerated.
  - Literal type mapping: `xsd:integer` → `BigInteger`, `xsd:decimal` → `BigDecimal`, `xsd:float` → `Float`, `xsd:date` → `LocalDate`, `rdf:langString` → `LangString`; `xsd:dateTime`/`time`/`duration` stay `String`. Writers always emit the declared datatype.
  - `@Rdf.dataClassSuffix` defaults to `"Record"`; an empty suffix is rejected when interfaces are generated too.
  - Gradle plugin: `OntologyConfig` optional settings are nullable and `interfacePackage` is required. The plugin publishes as `kastor-gen-gradle-plugin` (plugin id `com.geoknoesis.kastor.gen` unchanged) and is prepared for the Gradle Plugin Portal.
  - Processor model classes gained fields (`ShaclShape.parentClasses`, `patternFlags`, `PropertyBuilderModel.datatype`/`isIriValued`).
- **Fixed:**
  - `validation-jena` / `validation-rdf4j` were stubs that only checked `foaf:name`; they now run Jena SHACL / RDF4J ShaclSail against a separate shapes graph and map real report entries.
  - Generated code failed to compile for `sh:hasValue`, `sh:pattern`, `sh:in` values containing `"`/`$`/`%`, keyword or capitalised/hyphenated `sh:name`s, `%` or `/*` in descriptions, and `sh:maxCount 0`.
  - A SHACL syntax error no longer silently deletes generated sources: the task fails before touching output. Blank-node shapes are supported and skipped constructs are reported.
  - Name collisions (including case-only file-name collisions) fail with the conflicting IRIs; stale output is removed before writing, so case-only renames survive on Windows/macOS.
  - Cyclic nested `DATA_CLASS` materialisation raises `MaterializationException` instead of overflowing the stack; materialisation errors are no longer swallowed.
  - The vocabulary generator emits full IRIs for out-of-namespace terms, deterministically ordered.
  - KSP resolves ontology resources from the annotated file's source set (`kastor.gen.resources` adds directories); SHACL inheritance becomes superinterfaces.
- **Added:** `XsdLiterals` runtime helpers, `GenerationNames`, shapes-taking validation adapter constructors and `fromTurtle`; the processor tests compile and run generated code.

#### Cross-module additions

- `rdf-core`: streaming `addTriples`/`removeTriples` overloads for `Sequence`/`Iterable` (Jena and RDF4J write them in one transaction); `Rdf.parseFromInputStream` streams to the selected provider instead of buffering; `LiteralValidation`; `ProviderCapabilities.supportsBaseDirection`; `SHACL.declare`/`prefixProperty`/`namespaceProperty`.

#### Security

- `rdf-core`: `Rdf.parseFromUrl`, `parseFromUrlAsync` and `parseDatasetFromUrl` load only `http`/`https` URLs by default (other schemes such as `file:` or `jar:` must be allowed through `UrlLoadOptions.allowedSchemes`) and cap response bodies at 64 MiB (`UrlLoadOptions.maxBytes`), failing with `RdfInputTooLargeException`.
- `rdf-sparql-lang`: literals are always escaped when rendered (query injection fix). Variable names, aliases, prefixes, function names and `VERSION` values are validated against the SPARQL grammar.
- `rdf-sparql`: only `http`/`https` endpoint URLs are accepted, and credentials are never printed by `SparqlEndpointConfig.toString()`.
- `onto-quality-llm-koog`: ontology and finding text is sent to the model as escaped JSON data, and LLM output is Markdown-escaped in reports (no links, images or raw HTML).

#### Breaking changes

- **`rdf-core`:**
  - `Rdf.parseFromUrl*` gained a trailing `options: UrlLoadOptions` parameter (source-compatible, binary-incompatible).
  - `LangString`, `TrueLiteral` and `FalseLiteral` are no longer data classes (`LangString` keeps `copy` and `componentN`).
  - `MemoryGraph`'s lock-sharing constructor is internal.
  - Language tags are validated and normalised to lower case (`normalizeLanguageTag`).
  - Boolean literals preserve their lexical form: only `"true"`/`"false"` map to `TrueLiteral`/`FalseLiteral`; use `Literal.booleanValue()` to interpret `"1"`/`"0"`.
  - Temporal `toLiteral()` always includes seconds (`"10:15:00"`).
  - DSL blank-node labels are opaque (`b_<run>_<n>`) and unique across DSL calls; the DSL `triples` property is read-only.
  - The `memory` provider reports RDF 1.2 with triple terms.
  - Provider selection orders by `RdfProvider.priority` (memory −100, RDF4J 40, Jena 50). An explicit `providerId` whose provider, variant or requirements do not match throws `IllegalArgumentException` instead of falling back.
  - `Vocabularies.getTermsByPrefix` / `getTermsByNamespace` were removed.
  - `MemoryRepository.getGraph` returns a live graph handle.
- **`rdf-sparql-lang` AST:**
  - `AggregateExpressionAst.expression` is nullable (`COUNT(*)`) and gains `separator`.
  - `InsertDataOperationAst` / `DeleteDataOperationAst` gain `graphData`.
  - `ModifyOperationAst` gains `deleteGraphs` / `insertGraphs`.
  - Constructors and `copy` signatures changed.
- **`rdf-sparql-lang` rendered output:**
  - `VERSION "1.2"` is now quoted.
  - Updates are joined with `;` and ordered `WITH … DELETE … INSERT … USING … WHERE`.
  - `HAVING` renders `HAVING (expr)`, UNION renders `{ } UNION { }`, and `minus { }` always renders `MINUS { }`.
  - Property paths are parenthesised by precedence.
  - `FROM` now follows the query form.
  - `quotedTriple` renders the reified triple `<< s p o >>`, and `rdf:reifies` is written as a full IRI.
  - `startsWith`/`endsWith` render `STRSTARTS`/`STRENDS`. The two-argument `hasLang`/`hasLangdir` render `LANGMATCHES(LANG(x), "tag")` / `LANGDIR(x) = "dir"`. `dateTime`/`date`/`time` render XSD casts.
  - Now rejected: `USING`/`WITH` on `INSERT DATA`/`DELETE DATA`/`DELETE WHERE`, `{n,m}` path ranges, negated property sets containing anything other than IRIs or `^IRI`, standalone `<<( )>>` patterns, `GRAPH` inside CONSTRUCT templates, and negative `LIMIT`/`OFFSET`.
  - `random()`, zero-argument `timezone()` and `decodeForUri()` are deprecated with level ERROR.
- **`rdf-shacl-validation`:**
  - `ValidationReport.isValid` now follows `sh:conforms`: it is `false` when any result of severity Violation, Warning, Info or a custom severity exists. Use the new `hasViolations` for the previous severity-filtered check.
  - `ValidationViolation` gains `resultPathNode`, `resultPathTriples`, `resultMessages` and `sourceConstraint`. Its constructor and `copy` are binary-incompatible.
- **`rdf-shacl-dsl`:**
  - `sparqlAsk` is removed (deprecated with level ERROR).
  - Numeric bound properties (`minInclusive`, …) are typed `Number?`.
  - Single-valued setters replace the previous value instead of appending.
  - Block forms `and { }` / `or { }` / `xone { }` create a single list operand; use `andShapes` / `orShapes` / `xoneShapes` for several operands.
- **Providers:** `parseGraph` rejects TriG / N-Quads with `RdfFormatException` (use dataset parsing). With no base IRI, relative IRIs are a parse error in both Jena and RDF4J, and Jena parsing is strict.
- **Reasoning:** the Jena reasoner no longer accepts `ReasonerType.OWL_EL` (it supports `RDFS`, `OWL_RL` and `CUSTOM` only). The RDF4J reasoner supports `RDFS` only.
- **onto-quality:**
  - `ExplainedQualityReport` gains `failures`, and `LlmExplanationConfig` gains `requestTimeout`, `maxRetries` and `retryBackoff` (constructor/`copy` signatures changed).
  - `OnnxEmbeddingModel.fromMiniLm(cacheRoot, maxTokens)` has a new signature.
  - The OQuaRE metric formulas were revised, so metric values change.

#### Fixed

- **rdf-core:**
  - Blank-node label collisions between separate DSL calls.
  - `FROM`/`FROM NAMED` placement when querying datasets, so queries mixing the default graph and named graphs return correct results.
  - A parse fallback that silently produced an empty graph.
  - Orphaned named-graph handles in the memory repository.
  - Literal lexical fidelity.
  - Graph isomorphism on long RDF lists.
  - `equivalentTo` for language-tagged literals.
  - Quadratic CBD closure computation.
- **SPARQL DSL:**
  - `LOAD <src> INTO GRAPH <g>` renders correctly.
  - `graph(g) { }` is supported in `INSERT DATA`, `DELETE DATA` and update templates.
  - `VALUES` rows accept `UNDEF` (`null`).
  - `replaceAll` renders `REPLACE` and is deprecated.
  - `timezone(expr)` renders `TIMEZONE`.
  - The single-argument `hasLang(x)` / `hasLangdir(x)` render SPARQL 1.2 `hasLANG` / `hasLANGDIR`.
- **SPARQL endpoint:**
  - Capabilities truthfully report SPARQL 1.1, no RDF-star and no federation.
  - Connections are reused.
  - `removeTriples` issues one `ASK … VALUES` plus one `DELETE DATA` per batch (non-atomic).
  - Blank nodes are accepted on add: labels are re-issued and blank-node data is never split across batches. Remove/find/has with blank nodes fail with guidance to use `DELETE WHERE`.
  - Exceptions thrown by `withSelectRows` consumers propagate unchanged.
  - The `httpclient5` dependency was removed.
- **SHACL validation:**
  - Property shapes default to `sh:Violation` (no inheritance).
  - Multi-valued parameters produce one constraint each; a repeated single-valued parameter is a compile error.
  - Literals can conform to nested shapes.
  - Deactivated or empty shapes accept every node.
  - Classes that are also shapes act as implicit class targets.
  - Comparisons are datatype-aware (SPARQL operator mapping), and XSD lexical forms are checked.
  - `sh:pattern` supports the `q` and `x` flags and is compiled at shape compile time.
  - Reports carry `sh:message` with language, full result paths, spec-conformant `sh:value`, one `sh:closed` result per triple, and `sh:sourceConstraint`.
- **SHACL-SPARQL:**
  - `sh:prefixes`/`sh:declare` and `$PATH` are supported, and `$this`, `$currentShape` and `$shapesGraph` are pre-bound.
  - Each solution row produces one result; message templates and `sh:deactivated` are honoured.
  - Queries run over the data graph.
  - Without `rdf-jena` or `rdf-rdf4j` on the runtime classpath, validation fails with a `ShaclValidationException` naming them, and capability flags reflect engine availability.
- **W3C SHACL 1.2 suite:** 154 of 163 cases pass. The 9 known deviations (4 SPARQL-based constraint components, 2 SHACL 1.2 node expressions, 3 SPARQL node expressions) are listed in `W3cKnownDeviations`.
- **SHACL DSL:**
  - `and`/`or`/`xone` and `ignoredProperties` emit RDF lists.
  - `prefixes { }` in SPARQL constraints emits `sh:declare [ sh:prefix ; sh:namespace ]`.
  - Blank-node labels are collision-free.
  - `languageDirection` and `totalDigits`/`fractionDigits` are deprecated (warning); the latter are not enforced by the native validator.
- **RDF4J SHACL validator:** validity is taken from `sh:conforms` before the violation cap, and `validateResource` validates the whole graph filtered by focus node.
- **Jena / RDF4J providers:**
  - Literal lexical forms are preserved exactly (`"007"^^xsd:integer`); only the exact `"true"`/`"false"` become boolean singletons.
  - RDF4J 5.3.1 represents `"x"@ar--rtl` as the language tag `ar--rtl`, so SPARQL `LANG()` returns `ar--rtl` there.
  - Jena inference models are cached per graph, and reads on inference repositories are serialized.
  - Batch writes run in one transaction.
- **Reasoning:**
  - Consistency checks are real (Jena `validate()`).
  - The memory reasoner applies RDFS rules 2/3/5/7/9/11 to a fixpoint.
  - The HermiT deadline is enforced by a watchdog `interrupt()`.
- **RDF CLI:** `diff` is dataset-aware for quad formats, and unknown file extensions are an error.
- **onto-quality:**
  - Metrics are computed on the asserted graph even with `--reasoner`.
  - The `OWL_MICRO` profile uses the Jena `OWL_RL` rule reasoner.
  - Revised OQuaRE formulas (RFCOnto, NOC/CBO/RFC, CROnto, TMOnto, RROnto/INROnto, LCOMOnto) are documented in `tools/onto-quality/library/README.md`.
  - `pipeline` writes the intermediate file only with `--keep-intermediate`.
  - Options are validated before the model loads.
  - Embedding tokenization truncates at `maxTokens` with dynamic padding, and blank-node similarity keys are rejected.

#### Added

- `rdf-core`:
  - `UrlLoadOptions` and `RdfInputTooLargeException`.
  - `RdfProvider.priority`, `RdfProvider.supportsInputFormat` and `supportsOutputFormat`.
  - `Literal.booleanValue()` and `normalizeLanguageTag`.
  - `TripleBuilderDsl`, the shared base of `GraphDsl` and `TripleDsl`.
- `rdf-sparql-lang`: `countAll()`, `groupConcat(expr, separator)` and `UNDEF` in `VALUES`.
- `rdf-sparql`:
  - `SparqlEndpointConfig` with custom headers, HTTP Basic auth (also from URL credentials), `GET` / form `POST` queries and a separate update URL.
  - An overall `requestTimeout` (default 5 min).
  - `maxResponseBytes` for buffered calls and an optional `maxStreamedResponseBytes` for `withSelectRows`.
  - `insertBatchSize` (default 5000).
  - Provider options `header.<Name>`, `username`, `password`, `queryMethod`, `updateMethod`, `updateLocation`, `requestTimeoutMillis`, `maxStreamedResponseBytes` and `insertBatchSize`.
- `rdf-shacl-validation`: `ValidationReport.hasViolations`.
- `rdf-shacl-dsl`: `andShapes`, `orShapes` and `xoneShapes`.
- `rdf-jena`: `JenaBridge.copyToJenaModel`. `toJenaModel` on repository graphs now returns a detached copy.
- `rdf-rdf4j`: interop functions `rdfTermFromRdf4j`, `rdf4jValueOf`, `rdf4jResourceOf`, `rdfTripleFromRdf4j` and `rdf4jStatementOf`.
- Reasoning:
  - Deterministic provider selection via `priority()` (memory −100, RDF4J 40, Jena 50, HermiT 100).
  - `ReasonerConfig.forType`.
  - HermiT also serves `OWL_DL`, and HermiT/`OWL_DL` configurations default to `streamingMode = false`.
- onto-quality CLI:
  - Exit codes 0 / 1 (findings or usage error) / 2 (parse error) / 3 (explanation failure with `--fail-on-explain-error`).
  - `--llm-timeout`, `--llm-retries` and `--input-format` (plus extension detection).
  - Bounded `--explain-max` (1–500) and `--explain-batch` (1–100).
  - JSON output via kotlinx.serialization, including `llmExplanationFailures`.
- onto-quality LLM: per-request timeout, bounded retries with backoff, and partial results with per-batch failure records (`ExplainedQualityReport.failures`).
- onto-quality embeddings: `SimilaritySearchBudgetExceededException`.
- RDF 1.2 conformance: Jena 1038 executed / 32 unapproved skipped / 0 failed. RDF4J 1038 executed / 187 skipped (155 allowlisted upstream Rio gaps in `conformance-allowlist.tsv` + 32 unapproved) / 0 failed.

#### Build, CI and documentation

- Build, CI and documentation: README samples now match the current DSL and are compiled via `examples/hello-world`. Install docs require JDK 21, show `0.3.0-SNAPSHOT` and state that artifacts are not yet published.

## [0.2.1] - 2026-06-27

Tagged as `0.2.1` (without the `v` prefix used by other tags).

### Added

- **`kastor-gen`: Write-path support (`generateWriteSupport`).** `@Rdf` now accepts
  `generateWriteSupport = true` alongside `generateDataClass = true`. When enabled, the
  generated factory `object` gains a `toTriples(record, subject): List<RdfTriple>` function
  that serializes a data-class snapshot back to RDF triples — the exact mirror of the `from()`
  read path. Behavior by `nestedMode`: `INTERFACE` extracts the backing node via `RdfBacked`;
  `IRI_ONLY` emits `Iri(string)` directly; `DATA_CLASS` skips nested object properties (no
  subject IRI available) with a comment and a KSP warning. `rdf:type` is always emitted.
  Runtime utilities `MutableRdfGraph.replaceValues(subject, predicate, triples)` and
  `MutableRdfGraph.replaceResource(subject, triples)` provide fine-grained and full-resource
  update helpers for callers integrating `toTriples` output into a live graph.

- **`kastor-gen`: Immutable data-class generation from SHACL shapes.** `@Rdf` accepts
  `generateDataClass = true` alongside the existing `generateInterfaces`/`generateWrappers`
  flags. When enabled, the processor emits an eagerly-loaded Kotlin `data class` (read-model
  snapshot) and a companion factory `object` that registers itself in `OntoMapper`.
  New annotation fields: `dataClassSuffix` (name suffix, e.g. `"Record"` → `PersonRecord`),
  `dataClassImplementsInterface` (structural alignment with the generated interface),
  `nestedMode` (`INTERFACE` | `DATA_CLASS` | `IRI_ONLY`, controls typing of `sh:class`
  object properties inside the data class). The `RdfProjection` marker interface
  distinguishes snapshot instances from live `RdfBacked` wrappers at the type level.
  `OntoMapper` now auto-discovers `*Factory` classes alongside `*Wrapper` classes on first use.

- **`kastor-gen`: SHACL-native enum generation.** `sh:in` value sets produce sealed `Known`/`Unknown` enum types. Wrappers read them via `from()`, the instance DSL has type-safe setters, and data classes support them for reads and writes.
- **`rdf4j-reasoning`:** materializes real RDFS inference.

### Fixed

- **Security:** `rdf-core` IRI validation rejects RFC 3987-illegal characters (an injection vector into serialized output). Residual SPARQL-injection paths were closed in the SPARQL endpoint adapter and the `sparql-lang` renderer, and the HTTP client was hardened.
- `sparql-lang` emits grammar-valid `ORDER BY`. SHACL `sh:select` no longer lets `$this` pre-binding clobber the projection.
- `rdf:jena` preserves language direction and triple terms in SPARQL results. `rdf:rdf4j` scopes graph operations to their context, closes cursors, and deduplicates/rolls back dataset IO.
- `rdf-core`: graph-isomorphism fidelity, datatype handling, thread safety and lazy-sequence lifetime.
- `kastor-gen`: generated `validate()` compiles (keyword escaping, `sh:in` escaping, IRI-typed values). `sh:pattern` uses SPARQL `REGEX` find semantics. Bootstrap-classloader types materialize. The Gradle plugin generates interfaces without validation annotations.

### Changed (build)

- Gradle wrapper upgraded from 8.13 to 9.5.1.
- Apache Jena upgraded from 5.6.0 to 6.1.0. `rdf/providers/jena` promotes `jena-libs`, `jena-arq` and `jena-tdb2` from `implementation` to `api`, so consumers can resolve Jena types without an extra dependency.
- The whole build (toolchain and daemon) runs on **JDK 21** (was 17); JDK auto-download is disabled.
- RDF4J upstream RDF 1.2 gaps are reported as skipped conformance tests instead of failures.

## [0.2.0] - 2026-05-17

### Changed (repository layout)

- Related modules now live under **domain folders** on disk (`rdf/sparql/`, `rdf/providers/`, `rdf/reasoning/`, `rdf/shacl/`, `tools/onto-quality/`, `benchmarks/shacl/`). **Gradle project paths (`:rdf:jena`, …) are unchanged**; root [`settings.gradle.kts`](settings.gradle.kts) sets `projectDir` where needed. See [**Physical repository layout** in the architecture doc](docs/kastor/concepts/architecture.md#physical-repository-layout).
- **Docs alignment:** published **`artifactId`** for **``:rdf:shacl-validation`** is **`shacl-validation`** (README tables that said **`rdf-shacl-validation`** were corrected). SHACL benchmark runners and design docs now reference **`benchmarks/shacl/jmh/…`** on disk; a **duplicate** bench tree that mirrored **`jmh/`** next to it was removed.

### Breaking changes (SPARQL modules)

- **`rdf:core` no longer contains the SPARQL AST/DSL** (`com.geoknoesis.kastor.rdf.sparql`),
  **`ShaclDsl`**, **`Rdf.shacl`**, or **`SelectBuilder.addCommonPrefixes`**.
  Add **`sparql-lang`** (Gradle **`:rdf:sparql-lang`**) when you use **`select {}`**, **`SparqlRenderer`**, flows/helpers under **`sparql`**, or **`addCommonPrefixes`**.
  Add **`shacl-dsl`** (Maven **`rdf-shacl-dsl`**, Gradle **`:rdf:shacl-dsl`**) when you use **`shacl {}`** or **`Rdf.shacl`** — it **`api`**-depends on **`sparql-lang`** for SPARQL-shaped constraints.
  String-marker types (**`SparqlSelectQuery`**, **`UpdateQuery`**, …) remain available transitively via **`rdf-sparql-contract`**
  (**Maven `artifactId`**, Gradle **`:rdf:sparql-contract`**) when you depend on **`rdf-core`**.

### Breaking changes (Maven `artifactId`s)

Published artifacts now use **`artifactId`** names that match the documentation (**`rdf-core`**, **`rdf-jena`**, **`rdf-rdf4j`**, **`rdf-sparql`**, **`rdf-sparql-contract`**, **`rdf-shacl-dsl`**, **`kastor-gen-runtime`**, **`kastor-gen-processor`**, …) instead of bare Gradle **`project.name`** values (**`core`**, **`jena`**, **`runtime`**, …). *(Six modules — `sparql-lang`, `reasoning`, `reasoning-hermit`, `jena-reasoning`, `rdf4j-reasoning`, `shacl-validation` — kept bare names in 0.2.x; they are renamed to `rdf-*` in the next release. See [Unreleased].)* Update Maven POMs or external Gradle builds that pinned the old IDs. **`:rdf:rdf4j`** and **`:rdf:sparql`** now declare **`maven-publish`** so they publish under **`rdf-rdf4j`** and **`rdf-sparql`** respectively.

### Breaking changes (adapter classpath)

- **`rdf:jena` and `rdf:rdf4j` no longer depend on `rdf:reasoning`.** Jena- and
  RDF4J-backed **`RdfReasonerProvider`** implementations (SPI + direct types)
  live in **`jena-reasoning`** and **`rdf4j-reasoning`**. Add those artifacts
  when you use **`JenaReasonerProvider`**, **`Rdf4jReasonerProvider`**, or
  **`ReasonerRegistry`** discovery for those ids—or consume **`kastor-bom`**, which
  pins them alongside the store adapters. See
  [Repository architecture — Dependency profiles](docs/kastor/concepts/architecture.md#dependency-profiles-gradle).

### Breaking changes (RDF 1.2 adoption)

Kastor's data model is now [W3C RDF 1.2](https://www.w3.org/TR/rdf12-concepts/)
end-to-end. RDF 1.2 is *not* fully backwards compatible with the previous RDF-star
based model that 0.1.x exposed.

- **`TripleTerm` is no longer a `RdfResource`.** It now implements `RdfTerm`
  only. Triple terms in RDF 1.2 are object-position-only and carry no assertional
  force. Any code that put a `TripleTerm` in subject position will not compile;
  see the migration guide for replacements using `rdf:reifies`.
- **`LangString` gained an optional `direction: Direction?` field.** When
  `direction != null`, `LangString.datatype` is `rdf:dirLangString` (RDF 1.2's
  new directional language string datatype); otherwise `rdf:langString` as before.
- **Quoted-triple syntax changed.** Serialization, `toString()`, and the SPARQL
  renderer now emit `<<( s p o )>>` (with parentheses, RDF 1.2 spec) instead of
  the legacy `<<s p o>>` RDF-star syntax. Parsers continue to accept both because
  Jena/RDF4J's parsers do.
- **Old reification vocabulary deprecated.** `rdf:Statement`, `rdf:subject`,
  `rdf:predicate`, `rdf:object` are still in `RDF` but marked `@Deprecated`. The
  RDF 1.2 idiomatic replacement is `rdf:reifies` paired with a triple term.
- **`SparqlAst` triple patterns:** `QuotedTriplePatternAst` and
  `RdfStarTriplePatternAst` are deprecated; new code should use
  `TripleTermPatternAst` (object-position only) and `ReifierPatternAst`. The
  deprecated types still render to RDF 1.2 syntax for one minor cycle.
- **Provider capabilities** gained `rdfVersion: String` and
  `supportsTripleTerms: Boolean`. `JenaProvider` and `Rdf4jProvider` advertise
  `1.2` / `true`; `MemoryRepositoryProvider` advertises `1.1` / `false`.

### Added

- `Direction { LTR, RTL }` enum and directional-language helpers
  (`lang(text, "ar", Direction.RTL)`, `Literal.invoke(value, lang, dir)`).
- `RDF.dirLangString`, `RDF.reifies`, `RDF.TripleTerm`, `RDF.reifier`,
  `RDF.HTML`, `RDF.JSON`, `RDF.CompoundLiteral` vocabulary constants.
- `reifies(triple) { ... }` DSL builder for attaching metadata to a triple via
  the RDF 1.2 reifier pattern.
- SPARQL 1.2 built-ins `LANGDIR`, `STRLANGDIR` registered alongside the
  existing `TRIPLE` / `SUBJECT` / `PREDICATE` / `OBJECT` / `isTRIPLE`.
- `RdfFormat` aliases for the 1.2 format flavours (`TURTLE-1.2`, `TRIG-1.2`,
  `N-TRIPLES-1.2`, `N-QUADS-1.2`, plus the legacy `TURTLESTAR`).
- `Rdf12Test` suites in `:rdf:jena` and `:rdf:rdf4j` covering parse/serialize
  round-trips for triple terms, directional strings, `rdf:reifies` reifiers, and
  SPARQL `ASK` patterns over triple terms.
- `kastor-gen` codegen now emits `LangString(value, lang, dir)` for SHACL
  properties whose `sh:datatype` is `rdf:dirLangString`.

### Changed

- **`rdf-shacl-dsl`** module (**`:rdf:shacl-dsl`**, Maven **`rdf-shacl-dsl`**): **`ShaclDsl`**, **`shacl {}`**, and **`Rdf.shacl`** moved from **`sparql-lang`**; **`shacl-dsl`** **`api`**-depends on **`sparql-lang`** for SPARQL-shaped constraints. **`rdf:shacl-validation`** is unchanged and does not depend on **`shacl-dsl`**.
- The SPARQL renderer emits `<<( s p o )>>` (RDF 1.2) and `"text"@lang--ltr` /
  `--rtl` for directional language literals.
- Jena bridge uses `NodeFactory.createTripleTerm(...)` (Jena 5.4+ API);
  RDF4J bridge prefers `ValueFactory.createTripleTerm(...)` and falls back to
  `createTriple(...)` on older RDF4J builds.

### Migration

Read [docs/kastor/guides/migrating-to-rdf-1.2.md](docs/kastor/guides/migrating-to-rdf-1.2.md)
before upgrading.

## [0.1.0]

Initial release.

[Unreleased]: https://github.com/geoknoesis/kastor/compare/0.2.1...HEAD
[0.2.1]: https://github.com/geoknoesis/kastor/compare/v0.2.0...0.2.1
[0.2.0]: https://github.com/geoknoesis/kastor/releases/tag/v0.2.0
