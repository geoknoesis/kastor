# How this documentation fits together

{% include version-banner.md %}

This site follows **[Diátaxis](https://diataxis.fr/)**: four documentation modes that answer **different cognitive needs**. Keeping them separate avoids “tutorial pages that dump full APIs” and “reference pages that tell stories.”

If a word is unclear (**IRI**, **repository**, **provider**, **shape**), use the [**Glossary**](../concepts/glossary.md).

---

## The four modes (and where they live here)

| Mode (Diátaxis) | Answers | Must feel like | Primary locations on this site |
|-------------------|---------|----------------|--------------------------------|
| **Tutorial** | “Teach me hands-on.” | A safe, linear lesson with a verifiable win. | [Tutorials index](../tutorials/README.md), [Getting Started](getting-started.md), [Quick Start](quick-start.md), [Hello World](../tutorials/hello-world.md), [Load and query](../tutorials/load-and-query.md), [Jena-backed repo](../tutorials/jena-bridge.md) |
| **How-to guide** | “How do I accomplish X?” | Steps toward an outcome; assumes baseline competence. | [Guides](../guides/README.md) (`how-to-*.md`) |
| **Explanation** | “Why is it like this? What are the tradeoffs?” | Concepts, context, architecture—not a recipe. | [Concepts](../concepts/README.md), [Philosophy](../philosophy.md), conceptual parts of [Features](../features/README.md) |
| **Reference** | “What exactly does this API / flag / error do?” | Neutral, dense, complete facts. | [Reference](../reference/README.md), [API](../api/api-reference.md), [Providers](../providers/README.md), CLI options in repos |

**Conceptual “what is a dataset?”** → Explanation (Concepts). **“What is the `RdfRepository` signature?”** → Reference (API).

**Kastor Gen** mirrors the same split: [overview](../../kastor-gen/README.md), [tutorials](../../kastor-gen/tutorials/getting-started.md), [reference](../../kastor-gen/reference/README.md).

---

## Cognitive need → where to go

| You are thinking… | Open |
|-------------------|------|
| I’m new; walk me through setup | Tutorial: [Getting Started](getting-started.md) → [Quick Start](quick-start.md) |
| I need to parse / validate / reason | How-to: [Guides](../guides/README.md) |
| I don’t understand RDF terms or design choices | Explanation: [Concepts](../concepts/README.md), [Glossary](../concepts/glossary.md), [Philosophy](../philosophy.md) |
| I need exact behaviour or syntax | Reference: [API](../api/api-reference.md), [Reference index](../reference/README.md) |

---

## Anti-patterns (avoid mixing modes)

These come straight from Diátaxis-style reviews:

| Anti-pattern | Bad effect | Fix |
|--------------|------------|-----|
| **Tutorial pollution** — full API tables mid-lesson | Beginners stall; reference goes stale in two places | Keep tutorials minimal; link **→ Reference** |
| **How-to drift** — long history/theory before steps | Task readers can’t scan | Move “why” to an **Explanation** page; link at top |
| **Reference narrative** — opinions or tutorials in API docs | Hard to trust or grep | Move prose to **Explanation**; leave facts in Reference |
| **Explanation proceduralized** — install steps in philosophy pages | Wrong mental mode | Link **→ Tutorial** or **→ How-to** instead |

---

## Cross-linking (recommended)

- After a **tutorial** succeeds → **Next:** one **Reference** anchor (e.g. core API) + one **Explanation** (e.g. datasets).
- Each **how-to** → **Reference** for types/APIs used + **Troubleshooting** where relevant.
- **Explanation** pages → related **how-tos**, not full procedures.
- **Reference** → specs (W3C) and **Glossary** entries, not essays.

---

## Suggested reading order

1. **Tutorial:** install + first graph ([Getting Started](getting-started.md)).
2. **Explanation:** RDF mental model ([Concepts](../concepts/README.md), [Glossary](../concepts/glossary.md)).
3. **How-to:** your integration tasks ([Guides](../guides/README.md)).
4. **Reference:** while coding ([API](../api/api-reference.md)).

---

## Related links

- [Concepts overview](../concepts/README.md)
- [Guides overview](../guides/README.md)
- [Reference overview](../reference/README.md)
- [Repository architecture](../concepts/architecture.md) (contributors — layers & modules)
- [Physical repository layout](../concepts/architecture.md#physical-repository-layout) (where sources live on disk)
