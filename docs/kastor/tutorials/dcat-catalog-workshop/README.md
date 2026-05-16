## DCAT catalog workshop (class syllabus)

Hands-on story: **catalog → dataset → distribution** using [DCAT](https://www.w3.org/TR/vocab-dcat-3/)-shaped RDF, first with the **hand-built DSL**, then with **Kastor Gen** (`@Rdf` + KSP).

## Repository material

Runnable Gradle module: [`examples/dcat-catalog-workshop/`](https://github.com/geoknoesis/kastor/tree/main/examples/dcat-catalog-workshop) (clone-relative path in your checkout: `examples/dcat-catalog-workshop/`).

| Part | Kotlin sources | Learning goals |
|------|----------------|----------------|
| **A** | `WorkshopHandRdf.kt` | In-memory graph, `dcat` / `dcterms` / `voidMeta` DSL, **SPARQL query DSL** (`select` / `prefix` / `where` / `triple` / `values`), flows |
| **B** | `domain/CatalogDomain.kt`, `WorkshopGenDemo.kt` | `@file:Rdf(prefixes=…)`, `@Rdf` interfaces, generated wrappers, `materialize` |

## Suggested lesson flow (90–120 min)

1. **Concepts (10 min)** — Remind subject / predicate / object; introduce DCAT roles (catalog, dataset, distribution).
2. **Part A live (25 min)** — Walk `WorkshopHandRdf.kt`: graph DSL first, then **SPARQL DSL** (`select`, `prefix`, `where`, `triple`, `values`); run `./gradlew :examples:dcat-catalog-workshop:runHandRdf`; optionally inspect `titleQuery.sparql` / `distributionQuery.sparql` in a debugger. Invite students to add a literal or a second dataset link.
3. **Break / exercises (15 min)** — Students extend the hand graph or add a variable to the `select` DSL.
4. **Part B intro (15 min)** — Show `CatalogDomain.kt`: class IRIs, property IRIs, why prefixes live on the file annotation.
5. **Part B live (20 min)** — Run `compileKotlin`, open generated `*Wrapper` under `build/generated/ksp/`, then `runGenDemo` with breakpoints on `materialize`.
6. **Wrap-up (10 min)** — Compare maintainability of hand DSL vs generated domain layer; point to [DCAT-US example](../../../../examples/dcat-us/README.md) for a fuller profile.

## Commands (copy-paste)

```bash
./gradlew :examples:dcat-catalog-workshop:run
./gradlew :examples:dcat-catalog-workshop:runHandRdf
./gradlew :examples:dcat-catalog-workshop:runGenDemo
./gradlew :examples:dcat-catalog-workshop:test
```

## Related documentation

- [Testing RDF graphs](../../guides/how-to-test-rdf-graphs.md) — isomorphism checks used in the module tests  
- [SPARQL bindings & Flow](../../guides/how-to-sparql-bindings-and-flows.md)  
- [Kastor Gen: Getting started](../../../kastor-gen/tutorials/getting-started.md)  
- [Kastor Gen: Domain modeling](../../../kastor-gen/tutorials/domain-modeling.md)
