# Concepts Overview

{% include version-banner.md %}

This section builds the semantic mental model you need to use the SDK correctly and confidently.
Read these before deep API work to avoid accidental semantic mistakes.

- [**Glossary**](glossary.md) — definitions shared with Getting Started, Guides, and Reference (IRI, dataset, provider, shape, …).
- [**How documentation fits together**](../getting-started/documentation-guide.md) — tutorials vs how-tos vs explanations vs reference.

If you are **changing the codebase**, start with [Repository architecture](architecture.md) for Gradle modules and dependency direction, then [Physical repository layout](architecture.md#physical-repository-layout) for where modules live on disk.

## Core concepts

- [RDF Fundamentals](rdf-fundamentals.md)
- [Datasets](datasets.md)
- [SPARQL Fundamentals](sparql-fundamentals.md)
- [Vocabularies](vocabularies.md)
  - [Create a Custom Vocabulary](../guides/how-to-create-vocabulary.md)
- [Glossary](glossary.md)
- [Repository architecture](architecture.md)
- [Design Philosophy](../philosophy.md)

## Recommended progression

1. RDF terms (IRI, blank node, literal) → [RDF Fundamentals](rdf-fundamentals.md)
2. Datasets and named graphs → [Datasets](datasets.md)
3. Query model → [SPARQL Fundamentals](sparql-fundamentals.md)
4. Vocabulary management → [Vocabularies](vocabularies.md)

