---
title: Kastor vs other RDF libraries
description: How Kastor relates to Apache Jena, Eclipse RDF4J, rdflib, and similar stacks—language, layering, and when to choose what.
---

# Kastor vs other RDF APIs

{% include version-banner.md %}

This page is an **Explanation**: how Kastor fits next to popular RDF toolkits, without assuming you already use Kotlin. For **side-by-side Kotlin code** against Jena and RDF4J, see [Kastor vs alternatives (code comparisons)](comparisons.md). For **why adopt Kastor** in a project, see [Benefits](benefits.md).

## What Kastor is (in one paragraph)

**Kastor** is a **Kotlin-native RDF API** with a compact graph DSL, Kotlin SPARQL builders, SHACL 1.2 validation, optional **Kastor Gen** (KSP) for ontology-driven types, and **pluggable providers**. On the JVM it can drive **Apache Jena** or **Eclipse RDF4J** (and in-memory or remote **SPARQL** backends) through a **single application-facing API**. It is not a triplestore product by itself; persistence and protocol details live in the provider you configure.

## At a glance

| Library / stack | Primary language | Typical role | vs Kastor |
|-----------------|------------------|--------------|-----------|
| **Apache Jena** | Java (usable from Kotlin) | RDF model, SPARQL (ARQ), reasoning, TDB2, Fuseki | Kastor can **wrap** Jena as a provider; you still have access to Jena types when needed. |
| **Eclipse RDF4J** | Java (usable from Kotlin) | Repository API, many stores, SPARQL | Same pattern: Kastor can **wrap** RDF4J as a provider. |
| **rdflib** | Python | Graph API, parsers/serializers, SPARQL over HTTP | **Different runtime** from Kastor; comparable **concepts** (terms, graphs, SPARQL), not direct API interop. |
| **Oxigraph** | Rust (bindings elsewhere) | Embedded or service store + SPARQL | Kastor does not embed it; you can use a **SPARQL** endpoint if one is exposed. |
| **Native RDF4J / Jena in app code** | Java | Direct use of `Model`, `RepositoryConnection`, etc. | Verbose, provider-specific code; Kastor aims to **replace that surface** in Kotlin while keeping the same engines underneath. |

## Apache Jena

[Jena](https://jena.apache.org/) is the de facto **Java** RDF framework: `Model`, `Dataset`, ARQ for SPARQL, RIOT for I/O, optional TDB2 and Fuseki. Strengths: breadth, long track record, large ecosystem.

**Relationship to Kastor:** Kotlin code that would otherwise call Jena directly can call Kastor instead; Kastor’s Jena integration maps between Kastor’s terms/graph APIs and Jena’s. You adopt Kastor for **Kotlin ergonomics**, **SPARQL DSL**, **unified configuration**, and **SHACL**—not because Jena is missing features.

## Eclipse RDF4J

[RDF4J](https://rdf4j.org/) is a **Java** RDF stack centered on `Repository`, connections, and Rio parsers. Strengths: clear repository abstraction, many triplestore bindings, solid SPARQL support.

**Relationship to Kastor:** Same story as Jena: Kastor can sit **above** RDF4J and present one Kotlin API. Switching between Jena and RDF4J in Kastor is mostly **configuration**, not a rewrite (see [code comparisons](comparisons.md)).

## rdflib (Python)

[rdflib](https://rdflib.readthedocs.io/) is the mainstream **Python** RDF library: `Graph`, terms, parsers, serializers, and optional SPARQL execution or HTTP endpoints. Strengths: simplicity, large community, natural fit for Python data tooling and notebooks.

**Relationship to Kastor:** rdflib and Kastor solve **similar problems in different ecosystems**. You would not mix them in one process for the same graph without an IPC or serialization boundary (e.g. exchange **RDF files** or use a **shared SPARQL endpoint**). If your team is **Python-first**, rdflib is the natural choice; if you are **Kotlin/JVM-first**, Kastor compares to **Jena/RDF4J** directly and to rdflib only at the level of "another full RDF API for that language."

## Other names you might be comparing

- **Products** (e.g. GraphDB, Stardog, Amazon Neptune): databases or services, often accessed via **SPARQL**. Kastor’s **SPARQL provider** is the bridge when you query or update such systems remotely; compare those products on ops and SPARQL extensions, not on Kotlin API shape.
- **RDF.js / Oxigraph / other language stacks**: same pattern—pick by **deployment language** and whether you need **embedded** JVM stores vs **HTTP** SPARQL.

## When to use what

| Situation | Reasonable default |
|-----------|-------------------|
| **Kotlin (or Java) service**, you want less boilerplate and typed SPARQL builders | **Kastor** over raw Jena/RDF4J |
| **Heavy investment in Jena-specific APIs** (e.g. custom reasoning pipelines) | **Jena** directly, or Jena under Kastor where Kastor adds value |
| **RDF4J-native store or habits** | **RDF4J** directly, or RDF4J under Kastor |
| **Python applications, ML, notebooks** | **rdflib** (or pyoxigraph, etc.), not Kastor |
| **Only remote SPARQL**, language-agnostic | Any client; Kastor’s value is still Kotlin DX if the app is Kotlin |
| **Ontology-driven JVM types** from SHACL | Kastor + **Kastor Gen** (see [Kastor Gen comparisons](../../kastor-gen/getting-started/comparisons.md)) |

## Related pages

- [Code comparisons: Kastor vs Jena / RDF4J](comparisons.md)
- [Benefits & value](benefits.md)
- [Design philosophy](../philosophy.md)
- [Providers overview](../providers/README.md) — Jena vs RDF4J vs SPARQL capabilities
- [Provider comparison](../providers/provider-comparison.md) — feature matrix across Kastor’s JVM providers
