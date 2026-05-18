---
title: Code quality & evaluation
description: How Kastor measures and protects quality—automated tests, W3C conformance, dependency analysis, and supply-chain checks.
---

# Code quality & evaluation

{% include version-banner.md %}

“Quality” here means **correctness under test**, **standards conformance where applicable**, **maintainable Gradle boundaries**, and **safe dependency updates**—not a single synthetic score. This page summarizes **how** those dimensions are evaluated in this repository.

## Automated testing

| Layer | What runs | Role |
|-------|-----------|------|
| **Unit / integration** | `./gradlew test -x :rdf:conformance:test` (default CI) | Module tests across `rdf/*`, `kastor-gen`, `tools`, `examples` as applicable. |
| **RDF 1.2 smoke** | `conformanceSmokeTest` | Fast harness check without the full W3C corpus checkout. |
| **Full RDF 1.2 syntax suites** | `:rdf:conformance:test` with submodule | Large corpus; weekly/manual workflow and local runs per [**CONTRIBUTING**](https://github.com/geoknoesis/kastor/blob/main/CONTRIBUTING.md). |
| **SHACL** | `:rdf:shacl-validation:test` | Always runs a bundled W3C subset; extended upstream tree optional locally. |

Failure of these tasks on `main` is treated as a **release blocker** for anything that claims standards compliance in the same area.

## Standards conformance

- **RDF 1.2** — Providers are driven against official syntax manifests; see [RDF 1.2 conformance](../concepts/rdf-1.2-conformance.md).
- **SHACL 1.2** — Documented on [SHACL validation](../features/shacl-validation.md); tests and optional full corpora complement releases.

Conformance is **machine-checked** against published test corpora where the project wires them—not only prose claims in documentation.

## Gradle hygiene

The root build uses the [**Dependency Analysis Gradle plugin**](https://github.com/autonomousapps/dependency-analysis-android-gradle-plugin) on production libraries. Run **`./gradlew buildHealth`** for unused-dependency advice and alignment with intended `api` vs `implementation` edges (see [Repository architecture](../concepts/architecture.md#dependency-hygiene-automated)). CI runs `buildHealth` as part of the automation described in [**CONTRIBUTING**](https://github.com/geoknoesis/kastor/blob/main/CONTRIBUTING.md).

## Supply chain & automation

| Mechanism | Purpose |
|-----------|---------|
| **Gradle wrapper validation** | Confirms wrapper JAR integrity when the wrapper changes. |
| **Dependency review (PRs)** | Surfaces newly introduced dependencies (requires GitHub Dependency graph). |
| **Dependabot** | Periodic updates for Actions and Gradle ecosystems. |

Details and workflow names are listed under **Automation reference** in [**CONTRIBUTING**](https://github.com/geoknoesis/kastor/blob/main/CONTRIBUTING.md).

## API & design review (human-in-the-loop)

Beyond automation, the project uses:

- **Documented API principles** — [API design principles](../api/api-design-principles.md) and module boundaries in [architecture](../concepts/architecture.md).
- **Design discussions** — Larger changes are expected to tie back to [**Discussions**](https://github.com/geoknoesis/kastor/discussions) or issues so tradeoffs are searchable.

## Ontology & data quality (optional product surface)

For governed vocabularies and catalogues, **onto-quality** adds SHACL-based pitfall detection and optional semantic tiers—orthogonal to JVM unit tests but part of **semantic** quality for ontology authors. See [Ontology quality](../features/ontology-quality.md) and [How to check ontology quality](../guides/how-to-ontology-quality.md).

## Related

- [Implementation status](status.md)
- [Roadmap & plans](roadmap.md)
