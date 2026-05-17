# Native Kastor graph store (Parquet-backed)

| | |
|---|---|
| **Status** | Design proposal (pre-implementation). Behavior described here is **aspirational** until a `RdfProvider` ships; Kastor’s normative RDF API contracts remain **`rdf/core`** (`RdfRepository`, `RdfGraph`, datasets, SPARQL ports). |
| **Audience** | Kastor contributors, integrators choosing persistence layers |
| **Related** | README positioning (“not a triple store”) describes **today**; this document sketches a **future first-party** persistence option behind the existing provider seam, not a pivot away from Jena/RDF4J interoperability. |

Kastor’s core abstracts RDF access through **`RdfRepository`** and **`RdfGraph`** (see `com.geoknoesis.kastor.rdf`). Today, durable SPARQL and graph persistence typically flow through **Jena** or **RDF4J** providers. This document proposes a **native**, **embedded-friendly** persistence layer that stores RDF **quads** in **Apache Parquet**, optimized for analytic scans, bulk load, cloud object storage, and tight integration with the Kastor SPARQL stack—without abandoning bridge providers for deployments that already standardize on TDB2, NativeStore, or triple stores-as-a-service.

---

## Contents

**[1 Problem statement](#1-problem-statement)** · **[2 Goals](#2-goals)** · **[3 Non-goals (initial phases)](#3-non-goals-initial-phases)** · **[4 Architecture placement](#4-architecture-placement)** · **[5 Physical data model](#5-physical-data-model)** · **[6 Encoding RDF terms](#6-encoding-rdf-terms)** · **[7 Dataset layout & partitioning](#7-dataset-layout--partitioning)** · **[8 Indexes & pruning](#8-indexes--pruning)** · **[9 Mutability, consistency, transactions](#9-mutability-consistency-transactions)** · **[10 SPARQL integration](#10-sparql-integration)** · **[11 Operations & tooling](#11-operations--tooling)** · **[12 Security & reproducibility](#12-security--reproducibility)** · **[13 Module & rollout plan](#13-module--rollout-plan)** · **[14 Open questions](#14-open-questions)** · **[15 References](#15-references)**

---

## 1 Problem statement

1. **Analytic and lake-adjacent workloads.** Organizations increasingly land curated knowledge graphs **next to** tabular warehouses in Parquet/ORC/object storage. A Kastor-native Parquet representation enables **bulk validation** (SHACL), **training exports**, lineage-friendly snapshots, and **SQL/Spark/DuckDB** interoperability without bespoke ETL beyond standard Parquet tooling.
2. **Predictable JVM footprint.** A columnar embedded store offers **streaming scans** with **memory-bounded iterators**, which complements Kastor’s direction on native validation and large-graph profiles (see [`shacl-validation-architecture.md`](./shacl-validation-architecture.md)).
3. **Provider symmetry.** Users want **`Rdf.persistent`** to resolve to a solution that remains **Kotlin-first** while still supporting **narrow escape hatches** to Arrow/unsafe I/O where justified—not a parallel ad-hoc file format maintained only inside one module.

Without a cohesive design, ad-hoc “dump triples to Parquet” utilities risk **inconsistent term encoding**, **non-composable indexing**, **accidental RDF 1.2 / RDF-star divergence**, and **SPARQL plans** that bypass useful column pruning.

---

## 2 Goals

1. **`RdfRepository` implementation.** Expose graphs, mutations, transactions (as surfaced by **`ProviderCapabilities`**) and SPARQL query/update endpoints consistent with existing providers.
2. **Quad-native storage.** Persist **explicit graph identity** (`default` vs named graph IRI), suitable for datasets and SPARQL GRAPH semantics without materializing unrelated unions.
3. **Column pruning for graph patterns.** Map common BGP access paths (S?, P?, O?, G?) to **predicate pushdown**, **dictionary filters**, **min/max statistics**, and **zone-map style** predicates on sort keys.
4. **Bulk ingest & compaction.** Efficient **append** pathways and **bounded read amplification** via compaction strategies tuned for RDF’s long-tail predicate vocabulary.
5. **RDF 1.2 alignment.** Canonical handling of literals (including directional language/text direction where applicable), **triple terms**, and blank nodes compatible with Kastor’s **`RdfTriple`** / **`TripleTerm`** model.
6. **Portability.** A **documented on-disk layout** (schema version, partitioning rules, collation) reproducible across patch releases except when **major schema versions** bump.

---

## 3 Non-goals (initial phases)

- Replacing cluster **OLTP RDF databases** where sub-millisecond triple lookups and rich inference index maintenance are paramount.
- **Full W3C SPARQL entailment regimes** beyond what Kastor’s SPARQL layer already exposes.
- Arbitrary **`java.io.Serializable`** graph plugins not representable as RDF terms.
- **Kotlin/Native-first** Arrow/Parquet pipelines in Phase 1 (JVM `parquet-java` or Arrow adapters are acceptable first targets; multiplatform is a stretch goal once memory model and FFI story are stable).

---

## 4 Architecture placement

### 4.1 Clean Architecture boundaries

Following the layering established for SHACL and repository providers:

| Layer | Responsibility |
|------|----------------|
| **Domain terms** | `RdfTriple`, `RdfGraph`, datasets—unchanged contracts in `rdf/core`. |
| **Repository port** | `RdfRepository`, `Dataset`, `SparqlMutable` orchestration stays provider-agnostic. |
| **Parquet adapter** | Column readers/writers, schema evolution, compaction jobs, caching—**does not leak** through public APIs except configuration (`RdfConfig` options keys). |
| **SPARQL integration** | Query planning uses **statistics** (`ColumnChunkMetaData`), **dictionary IDs**, Bloom filters where available—not embedded Jena idioms.

### 4.2 Provider registration

Implement **`RdfProvider`** with stable `id` (e.g. `parquet` / `native-parquet`, TBD during API review). Capability flags should advertise:

- RDF version support (**1.2** aligned with roadmap),
- **`supportsTripleTerms`** honest signaling,
- transactional semantics (**snapshot isolation + write batching** baseline),
- **SPARQL** coverage consistent with `:rdf:sparql` expectations.

---

## 5 Physical data model

### 5.1 Core tables (logical)

**Primary quad table (`quads`)** — authoritative statement store.

Suggested logical columns:

| Column | Purpose |
|--------|---------|
| `g_id` | Graph key (dictionary id or sentinel for default graph policy) |
| `s_*` | Subject encoding (_kind, _id or inlined components) |
| `p_id` | Predicate IRI dictionary id |
| `o_*` | Object encoding (mirrors subject/literal variants) |

Optional **normalized companion columns** derived at ingest time for pruning (trade write amplification for scan speed):

| Column | Purpose |
|--------|---------|
| `type_id` | `rdf:type` object dictionary id **only when** predicate is `rdf:type` (speeds typed scans) |

### 5.2 Supporting tables

| Artifact | Purpose |
|----------|---------|
| **Term dictionary** (`terms_vN.parquet`) | Maps stable term ids ↔ lexical/normalized representations; partitioned by hash or prefix. |
| **BNode table** (`bnode_map_vN.parquet`) | Maps internal stable blank node ids ↔ scope (graph + originating doc) unless inline encoding suffices. |
| **Triple-term table** (`triple_terms_vN.parquet`) | Canonicalizes RDF-star quoted triples referenced from `s_*`/`o_*` so repeated stars do not inflate row width. |

### 5.3 File format knobs

- **Row group size** traded off between **predicate pushdown** granularity and metadata overhead (start with **128–512 MB** ingest targets on large batches; smaller for embedded).
- **Compression:** **ZSTD** default (balanced CPU/ratio); **Snappy** for latency-sensitive prototypes.
- **Dictionary encoding:** Maximize **`PLAIN_DICTIONARY` / dict pages** on `p_id` and repeated IRIs.
- **Sort order** inside row groups drives merge join and zonemap usefulness (see partitioning).

---

## 6 Encoding RDF terms

Encode terms so that **equality aligns with RDF 1.2** and hashing is stable across compaction.

**Representative scheme (conceptual)**

- **Kinds** small integer enums: IRI, literal (plain/rdf:langString with optional direction), XSD-typed literal, blank node id, quoted triple reference.
- **IRIs**: store **normalized lexical** (+ optional hashed `iri_id` BIGINT) consistent with **`Iri`** string rules in core.
- **Literals**: pair `(lexical, lang/dir flags, datatype_id)` plus **normalized forms** optional for collation (careful with xsd/dateTime canonicalization deferred to literals layer).
- **Blank nodes**: **internal stable BIGINT** identifiers scoped per snapshot with a side map for interchange round-trips.
- **Triple terms**: **`TripleTerm`** stored as **canonical row** in auxiliary table referencing three term ids (+ id for the quoted triple row itself).

Expose a **serialized term blob** variant only behind `StoreProfile.COMPACT_LEGACY` escape hatch—not the default—in order to preserve structured pushdown opportunities.

---

## 7 Dataset layout & partitioning

### 7.1 Directory convention

Suggested repository root (`location`):

```text
<repo>/
  META.json                     # schema version, collation, partitioning policy
  quads/graph=p0000/*.parquet
  quads/graph=p0001/*.parquet
  dict/terms/*.parquet
  dict/triple_terms/*.parquet
  index/pos_sketch/*.parquet    # optional materialized lookups
```

`META.json` pins **frozen decisions**: default graph sentinel, RDF version advertised, hashing algorithm for `iri_id`, sort key order.

### 7.2 Partition strategies (selectable presets)

| Preset | When it wins | Downsides |
|--------|--------------|-----------|
| **Predicate bucketed** (`hash(p_id) mod N`) | SPARQL with bound predicates—scans prune most files independent of graph. | Cross-predicate BGPs touch many buckets. |
| **Graph keyed** (`g_id` prefixes) | Data siloed into named graphs; dataset federation via mount. | Huge default graph hotspots. |
| **Composite** `(graph_shard, predicate_bucket)` | Production default for mixed workloads. | More planner complexity—metadata must expose both dimensions. |

**Sorted row groups recommendation:** **`(g_id, p_id, s_kind, s_key, o_kind, o_key)`** subject to benchmarking; alternative **graph-local OSP** clones can exist as derivative index files described below.

---

## 8 Indexes & pruning

1. **Min/max statistics** on dictionary-encoded columns for **static pruning** (`p_id = CONST`, bounded `iri_id BETWEEN`).
2. **Bloom filters per row-group** optional for **`s_key`/`o_key`** high-selectivity lookups.
3. **Materialized sort orders:**
   - **POS clone** subset for **`?s ?p o`** patterns prevalent in inference-light analytics.
   - **OPS clone** subset for **`s ?p ?o`** exploration when subject selectivity dominates.
   - Derivatives are treated as **index artifacts** regenerated during compaction—not second sources of truth.
4. **Graph membership manifest** summarizing **`listGraphs()`** cheaply (`graphs_manifest.parquet` updated on commit boundaries).

Expose index build as **explicit maintenance operations** surfaced through repository admin hooks (Gradle task / CLI `:rdf:cli`), not silent magic blocking writes.

---

## 9 Mutability, consistency, transactions

Parquet itself is immutable per file revision; emulate mutability via **snapshot directories** + **manifest**:

### 9.1 Baseline model

- **Snapshot isolation**: readers pin a **manifest UUID** during query planning; writes produce **new file fragments** appended to a WAL-style JSON line log or SQLite sidecar (**`<repo>/META/catalog.sqlite`** optional) resolving to Parquet fragment lists.
- **Commit steps:** stage row groups → fsync manifests → prune obsolete fragments after reader quiescence (reference counting hooks on `close()` surfaces).
- **Rollback:** discard unreferenced fragments GC task.

### 9.2 SPARQL UPDATE

Implement **`SparqlMutable.update`** via **streaming translation** → row-level tombstones (**delete bitmap sidecar parquet**) or copy-on-write shards (choose per profile). Initial phase may scope **bulk-friendly** subsets (DROP GRAPH, CLEAR, INSERT DATA, DELETE DATA) ahead of unrestricted arbitrary algebra rewrites—planner emits graph ops + delta rowsets.

---

## 10 SPARQL integration

1. **Statistics module** feeding Kastor’s optimizer: approximate **distinct counts** per `p_id`, correlated `graph` cardinality, NDV estimates for literals where cheap.
2. **Iterator model** aligning with **`SourceTrackedGraph`**: parquet-backed graphs advertise repository + graph name pointers so dataset execution can **`push FILTER`** and **`LIMIT`** into parquet scanners.
3. **Join ordering heuristics** adapted for columnar RDF: prioritize **equality on high-NDV** columns after predicates with **few matching row groups**.
4. **Optional Arrow bridge:** zero-copy handing of scan batches into micro-benchmark tooling (not mandatory for correctness).

Compatibility tests reuse **existing SPARQL conformance harness** expectations where supported (see `:rdf:conformance`), behind capability flags acknowledging staged UPDATE coverage.

---

## 11 Operations & tooling

- **Vacuum/compaction daemon** merging small fragments; metrics for **average fragment size**, read amplification warnings.
- **Offline integrity checks:** verify dictionary closure, orphaned triple references, RDF-star canonicalization symmetry.
- **Export/import:** RDF serializations map through shared term codec to avoid divergence with Jena RDF4J import paths (`rdf/providers/jena`, `rdf/providers/rdf4j`).

---

## 12 Security & reproducibility

### 12.1 Security

Treat repository roots as untrusted disk: validate manifest signatures if remote-synced bundles appear; forbid arbitrary serialized Java objects in sidecars.

### 12.2 Reproducible builds / datasets

Sorting + dictionary assignment must be deterministic for identical ingests (**stable hash seeds** documented); CI golden tests comparing **fingerprints** of serialized fragments for canonical corpora.

---

## 13 Module & rollout plan

Suggested Gradle modules (exact names adjustable):

| Phase | Artifact | Deliverable |
|-------|----------|-------------|
| **P0** | `rdf:parquet-io` | Column schemas, codecs, dictionary writers, deterministic ordering tests (**no SPARQL**). |
| **P1** | `rdf:parquet-store` | `RdfRepository` + graph views + ingest/update baseline + capability metadata. |
| **P2** | `rdf:parquet-store-sparql` (or merged) | Full planner integration & conformance subset gating + compaction tooling. |

Documentation site links: mirror feature-focused how-to docs under `docs/kastor/features/` once snapshots exist publicly.

---

## 14 Open questions

1. **Default graph sentinel** strategy: dedicate NULL vs reserved id vs hashed constant—must align across providers for federated UNION behavior.
2. **Tombstone retention** versus immediate rewrite for DELETE-heavy workloads.
3. **`ProviderCapabilities.supportsInference`**: emulate none initially vs optional RDFS subset materializations (explicitly discouraged until core reasoning story clarifies storage contracts).
4. **Cross-platform FFI path** (`parquet2`, `apache-arrow-rs`) feasibility vs shipping **JVM-only** first milestone.
5. **Interoperability** with future **Lakehouse catalogs** (`Iceberg`-style manifests) versus plain Parquet bundles.

---

## 15 References

- Apache Parquet specification & `parquet-format` thrift definitions (`https://parquet.apache.org/`)
- RDF 1.2 Concepts & Abstract Syntax (`https://www.w3.org/TR/rdf12-concepts/`)
- Existing Kastor design doc: [`shacl-validation-architecture.md`](./shacl-validation-architecture.md)
- RDF-star community practice for quoted triple identity (`https://w3c.github.io/rdf-star-wg-charter/` drafts as applicable)
