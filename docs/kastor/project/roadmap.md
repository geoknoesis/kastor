---
title: Roadmap & plans
description: Thematic priorities and how roadmap discussions happen in the Kastor project (GitHub, docs, releases).
---

# Roadmap & plans

{% include version-banner.md %}

Kastor does not promise fixed delivery dates on this page. **Direction** is driven by standards track (RDF 1.2, SHACL 1.2), Kotlin ergonomics, and real-world catalogues (DCAT, SKOS, ontology quality). Use this page for **themes**; use GitHub for **scheduling and ownership**.

## Near-term themes

- **Standards alignment** — Keep RDF 1.2 syntax suites and SHACL corpora green as upstream tests evolve; document gaps in [RDF 1.2 conformance](../concepts/rdf-1.2-conformance.md) and [SHACL validation](../features/shacl-validation.md).
- **Provider ergonomics** — Clear dependency profiles (core, SPARQL DSL, SHACL DSL, reasoning split); fewer surprises when switching Jena, RDF4J, or SPARQL endpoints ([architecture](../concepts/architecture.md)).
- **Kastor Gen** — Gradle/KSP stability, incremental builds, and ontology-driven codegen ([Kastor Gen](../../kastor-gen/) section).
- **Ontology quality** — SHACL catalogues, optional semantic/LLM tiers, and CLI/library alignment ([ontology quality](../features/ontology-quality.md)).
- **Documentation** — Diátaxis structure (tutorials, how-tos, explanations, reference); keep installation and migration notes tied to [CHANGELOG](https://github.com/geoknoesis/kastor/blob/main/CHANGELOG.md).

## Medium-term themes

- **Reasoning & validation** — HermiT/Jena pathways and materialization before SHACL where needed ([reasoning](../features/reasoning.md), design notes in the repo).
- **Performance & benchmarks** — SHACL/native engine and cross-engine matrices where benchmarks are wired ([benchmark design](../design/shacl-native-engine-benchmark.md) when published).
- **Ecosystem** — Android/KMP guidance, remote SPARQL deployments, and examples that match current BOM coordinates.

## How planning is tracked

| Channel | Use for |
|---------|---------|
| [**Discussions**](https://github.com/geoknoesis/kastor/discussions) | Roadmap questions, design sketches, release themes. |
| [**Issues**](https://github.com/geoknoesis/kastor/issues) | Actionable bugs, features, and doc fixes. |
| **Releases / tags** | Cut versions; see [CHANGELOG](https://github.com/geoknoesis/kastor/blob/main/CHANGELOG.md). |

## Related

- [Implementation status](status.md) — what is already implemented and covered by CI.
- [Code quality](code-quality.md) — gates and metrics we rely on.
