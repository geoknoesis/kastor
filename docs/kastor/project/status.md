---
title: Implementation status
description: High-level snapshot of major Kastor components and how they are validated (CI, tests, conformance).
---

# Implementation status

{% include version-banner.md %}

This is a **high-level** snapshot for the docs site, not an exhaustive module list. For Gradle graph details, see [Repository architecture](../concepts/architecture.md). Release truth is [**CHANGELOG**](https://github.com/geoknoesis/kastor/blob/main/CHANGELOG.md) and published artifacts (see [Installation](../getting-started/installation.md)).

Statuses use the following meanings:

- **Shipped** — In published artifacts and intended for production use.
- **Shipped (optional)** — Separate coordinates or classpath; opt-in modules.
- **CI exercised** — Regular automated runs (full or smoke), as noted.

## RDF API & language surface

| Area | Status | Notes |
|------|--------|-------|
| **RDF 1.2 core model** (`rdf-core`, contracts) | Shipped | Kotlin API aligned with RDF 1.2 concepts; SPI-oriented design. |
| **String / marker SPARQL** (`rdf-sparql-contract` → core) | Shipped | Query/update markers without pulling the full Kotlin SPARQL DSL. |
| **Kotlin SPARQL DSL** (`sparql-lang`) | Shipped | AST, renderer, `select {}`, flows; **separate** Gradle module from `rdf:core` (0.2+). |
| **Graph / compact DSL** | Shipped | Core vocabulary-agnostic triple DSL. |
| **SHACL shapes DSL** (`rdf-shacl-dsl`) | Shipped | Depends on `sparql-lang` for embedded SPARQL constraints. |

## Stores & access

| Area | Status | Notes |
|------|--------|-------|
| **Memory / test graphs** | Shipped | In-process use cases. |
| **Apache Jena adapter** | Shipped | Repository wiring, TDB2 path, etc. |
| **Eclipse RDF4J adapter** | Shipped | Native store / repository variants. |
| **Remote SPARQL** (`rdf-sparql`) | Shipped | HTTP endpoints; no Jena/RDF4J required at compile time for the module itself. |
| **Reasoning facade** (`rdf:reasoning`) | Shipped (optional) | Materialization / OWL DL checks in advanced flows. |
| **Jena / RDF4J reasoner providers** | Shipped (optional) | **Not** transitive through store adapters; add `jena-reasoning` / `rdf4j-reasoning` or BOM. |

## Validation & quality

| Area | Status | Notes |
|------|--------|-------|
| **SHACL 1.2 validation** (`shacl-validation`) | Shipped | Native engine path; see [feature overview](../features/shacl-validation.md). |
| **Ontology quality** (`onto-quality` tools) | Shipped (tiers) | SHACL catalogues, metrics, optional LLM/embedding tiers; see [ontology quality](../features/ontology-quality.md). |

## Codegen

| Area | Status | Notes |
|------|--------|-------|
| **Kastor Gen** (Gradle plugin, KSP, runtime) | Shipped | [Kastor Gen](../../kastor-gen/) docs; ontology-driven Kotlin. |

## Conformance & automation

| Area | Status | Notes |
|------|--------|--------|
| **RDF 1.2 syntax harness** | Shipped; **CI**: smoke + optional full | Submodule + full corpus in workflow; local smoke without submodule in default CI path. See [RDF 1.2 conformance](../concepts/rdf-1.2-conformance.md) and [**CONTRIBUTING**](https://github.com/geoknoesis/kastor/blob/main/CONTRIBUTING.md). |
| **SHACL W3C tests** | CI (tiered) | Module tests + extended workflow where configured. |

## Related

- [Roadmap & plans](roadmap.md)
- [Code quality](code-quality.md)
