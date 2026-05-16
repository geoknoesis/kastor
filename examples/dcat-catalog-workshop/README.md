# DCAT catalog workshop (hands-on class)

Runnable **instructor + student** example for teaching Kastor RDF and **Kastor Gen** on one DCAT-shaped story (catalog → dataset → distribution).

## Run

```bash
# Full script (Part A hand RDF + Part B materialization)
./gradlew :examples:dcat-catalog-workshop:run

# Split demos
./gradlew :examples:dcat-catalog-workshop:runHandRdf
./gradlew :examples:dcat-catalog-workshop:runGenDemo
```

## What this module shows

| Track | Source | Topics |
|--------|--------|--------|
| **A** | `WorkshopHandRdf.kt` | `Rdf.memory()`, `repo.add`, `dcat` / `dcterms` / `voidMeta` DSL, **SPARQL query DSL** (`select`, `where`, `triple`, `values`), `getAs`, `asFlow` |
| **B** | `domain/CatalogDomain.kt` + `WorkshopGenDemo.kt` | `@Rdf` interfaces, KSP-generated wrappers, `OntoMapper.initialize`, `materialize<WorkshopCatalog>()` |

KSP is applied automatically for `:examples:*` (except `hello-world` / `hello-codegen`); this module **uses** the processor via `@Rdf` on `WorkshopCatalog` and related interfaces.

## Student workflow

1. Open `WorkshopHandRdf.kt` and trace the graph you build. Notice how `select { … }` uses the same vocabulary IRIs (`DCTERMS`, `DCAT`) as the RDF DSL — set a breakpoint and inspect `titleQuery.sparql` if you like.
2. Run `runHandRdf` and tweak literals or add a second `dcat:dataset`.  
3. Open `domain/CatalogDomain.kt` — explain `@file:Rdf(prefixes=…)` and property IRIs.  
4. Run `./gradlew :examples:dcat-catalog-workshop:compileKotlin` and inspect **generated** `*Wrapper` under `build/generated/ksp/...`.  
5. Run `runGenDemo` and set breakpoints on `materialize`.

## Related docs

- [DCAT catalog workshop (syllabus)](../../docs/kastor/tutorials/dcat-catalog-workshop/README.md)  
- Full **DCAT-US + Kastor Gen** deep example: [`../dcat-us/README.md`](../dcat-us/README.md)
