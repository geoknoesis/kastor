---
title: Kastor in 5 minutes
description: One-page tour of repositories, graph DSL, string & Kotlin SPARQL, providers, SHACL 1.2, Kastor Gen, ontology-quality, and conformance—with copy-paste Kotlin.
---

# Kastor in 5 minutes

{% include version-banner.md %}

This page is a **single-sheet sampler**: add one Gradle block, then skim the sections below—**graph DSL**, **string & Kotlin SPARQL**, **providers**, **SHACL 1.2**, **Kastor Gen** (plugin + KSP), **ontology quality** (Kotlin + CLI), and pointers to **conformance**. Each section has a short excerpt; **[Complete listing](#complete-listing-one-main)** merges the runnable RDF API pieces into one `main()`.

For a slower, step-by-step path, use [Getting Started](getting-started.md) and [Quick Start](quick-start.md).

## Prerequisites

- **JDK 17**
- A Kotlin **JVM** Gradle module (`kotlin("jvm")`)

## Dependencies

Minimal set for **repository + DSL + SPARQL** (pick **one** backend; both is fine):

```kotlin
dependencies {
    implementation("com.geoknoesis.kastor:rdf-core:0.2.0")
    implementation("com.geoknoesis.kastor:rdf-jena:0.2.0")   // or rdf-rdf4j:0.2.0
}
```

Add these **only when** you use the matching section later on:

```kotlin
    // Kotlin SPARQL DSL — section 3 (select {})
    implementation("com.geoknoesis.kastor:sparql-lang:0.2.0")

    // Ontology quality — section 7 (bundled SHACL catalogues; transitive SHACL + Jena stack)
    implementation("com.geoknoesis.kastor:onto-quality:0.2.0")
```

Add these **only if** you follow the [SHACL](#shacl-12-validation-native) section:

```kotlin
    implementation("com.geoknoesis.kastor:rdf-shacl-validation:0.2.0")
    implementation("com.geoknoesis.kastor:rdf-shacl-dsl:0.2.0")
```

**Kastor Gen** uses the **Gradle plugin** (and usually **`kastor-gen-runtime`** in `dependencies`)—see [§6](#6-kastor-gen-ontology--kotlin).

Optional: **`rdf-sparql`** (SPARQL endpoint provider), reasoning modules—[Installation](installation.md).

```kotlin
repositories { mavenCentral() }
```

---

## 1. Repository and graph DSL

`Rdf.memory()` gives you an in-memory **`RdfRepository`**. Use **`repo.add { }`** with **`iri(...)`**, FOAF-style **`has` / `with`**, and **`RDF.type`**:

```kotlin
import com.geoknoesis.kastor.rdf.*

val repo = Rdf.memory()
repo.add {
    val alice = iri("http://example.org/alice")
    alice has FOAF.name with "Alice Example"
    alice has RDF.type with FOAF.Person
}
```

The DSL is **vocabulary-agnostic**; FOAF and RDF constants are bundled for convenience. Your own predicates are just **`iri("…")`**.

---

## 2. Swap the engine (Jena, RDF4J, SPARQL, …)

The **same** `RdfRepository` API works across providers. Examples:

```kotlin
// In-memory Jena (good parity check with your JVM stack)
val jenaMemory = Rdf.repository {
    providerId = "jena"
    variantId = "memory"
}

// In-memory RDF4J
val rdf4jMemory = Rdf.repository {
    providerId = "rdf4j"
    variantId = "memory"
}
```

Remote endpoints and TDB2 / NativeStore use **`RdfProviderRegistry.create(RdfConfig(...))`** or the same `Rdf.repository { }` DSL with provider-specific options—see [Configuration variants](configuration-variants.md) and [Providers](../providers/README.md).

---

## 3. SPARQL queries

You need a **SPARQL-capable** repository on the classpath (the **`rdf-jena`** / **`rdf-rdf4j`** line above). **`Rdf.memory()`** already picks Jena or RDF4J `memory` when one of them is registered.

### String SPARQL (`SparqlSelectQuery`)

Run a query against the repository’s **default graph**:

```kotlin
import com.geoknoesis.kastor.rdf.SparqlSelectQuery

val rows = repo.select(SparqlSelectQuery("""
    PREFIX foaf: <http://xmlns.com/foaf/0.1/>
    SELECT ?name WHERE {
      ?s foaf:name ?name .
    }
""".trimIndent()))

rows.forEach { println(it.getString("name")) }
```

### Kotlin SPARQL DSL (`select { }`)

Add **`sparql-lang`**. The DSL builds a **`SparqlSelect`** that **`repo.select`** accepts—same execution path as a string query.

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.sparql.*
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF

val dslQuery = select("name") {
    version("1.2")
    prefix("foaf", FOAF.namespace)
    where {
        pattern(var_("person"), FOAF.name, var_("name"))
        pattern(var_("person"), RDF.type, FOAF.Person)
    }
}

repo.select(dslQuery).forEach { println(it.getString("name")) }
```

More patterns (filters, OPTIONAL, aggregates, RDF-star): [SPARQL fundamentals](../concepts/sparql-fundamentals.md), [Kastor query DSL tutorial](../guides/kastor-query-dsl-tutorial.md).

---

## 4. Datasets and RDF-star (where you need more than one graph)

Kastor models **datasets** (default graph + named graphs) and supports **RDF-star** constraints depending on provider variant—see [How to use datasets](../guides/how-to-use-datasets.md) and [RDF-star](../features/rdf-star.md).

---

## 5. SHACL 1.2 validation (native)

With **`rdf-shacl-validation`** + **`rdf-shacl-dsl`**, define shapes in Kotlin and validate the **default graph** against them:

```kotlin
import com.geoknoesis.kastor.rdf.dsl.*
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ValidationProfile

val shapes = shacl {
    nodeShape("http://example.org/shapes/Person") {
        targetClass(FOAF.Person)
        property(FOAF.name) { minCount = 1 }
    }
}

val report = ShaclValidation.validator(ValidationProfile.SHACL_CORE)
    .validate(repo.defaultGraph, shapes)

println("Conforms: ${report.isValid}")
```

Details: [SHACL validation](../features/shacl-validation.md), [SHACL DSL guide](../api/shacl-dsl-guide.md).

---

## 6. Kastor Gen (ontology → Kotlin)

**Kastor Gen** generates **interfaces**, **wrappers**, vocabulary constants, and optional **domain DSL** from SHACL + JSON-LD context at build time—Kotlin stays domain-shaped; RDF stays explicit.

### Gradle-only generation (no `@Rdf` in your sources)

Use the **`com.geoknoesis.kastor.gen`** plugin and declare ontologies under **`kastorGen`** ([full reference](../../kastor-gen/reference/gradle-plugin.md)):

```kotlin
// settings.gradle.kts — ensure Gradle can resolve the plugin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("com.geoknoesis.kastor.gen") version "0.2.0"
}

dependencies {
    implementation("com.geoknoesis.kastor:rdf-core:0.2.0")
    implementation("com.geoknoesis.kastor:rdf-jena:0.2.0")
    implementation("com.geoknoesis.kastor:kastor-gen-runtime:0.2.0")
}

kastorGen {
    ontologies {
        create("demo") {
            shaclPath = "src/main/resources/shapes/demo.shacl.ttl"
            contextPath = "src/main/resources/context/demo.context.jsonld"
            interfacePackage = "com.example.generated"
            wrapperPackage = "com.example.generated"
        }
    }
}
```

Point **`shaclPath`** / **`contextPath`** at real files (see [Ontology generation tutorial](../../kastor-gen/tutorials/ontology-generation.md)); run **`./gradlew generateOntology`** (task name is documented in the plugin reference).

### Annotation + KSP (interfaces in source)

```kotlin
plugins {
    kotlin("jvm") version "2.3.21"
    id("com.google.devtools.ksp") version "2.3.7"
}

dependencies {
    implementation("com.geoknoesis.kastor:rdf-core:0.2.0")
    implementation("com.geoknoesis.kastor:rdf-jena:0.2.0")
    implementation("com.geoknoesis.kastor:kastor-gen-runtime:0.2.0")
    ksp("com.geoknoesis.kastor:kastor-gen-processor:0.2.0")
}
```

```kotlin
import com.geoknoesis.kastor.gen.annotations.Rdf

@Rdf(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @Rdf(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>
}
```

Tutorials: [Kastor Gen README](../../kastor-gen/README.md) · [Getting started](../../kastor-gen/tutorials/getting-started.md).

---

## 7. Ontology quality (`onto-quality`)

**Ontology quality** runs **bundled SHACL catalogues** (OWL profile, SKOS, data quality, RDF 1.2-aware bundles, modern-engineering checks, …) and returns a structured **`QualityReport`**—broader than instance-only SHACL, aimed at **vocabulary and catalogue hygiene**.

Add **`com.geoknoesis.kastor:onto-quality:0.2.0`** (see [dependencies](#dependencies) above). It brings a validator-facing stack transitively; keep **`rdf-jena`** or **`rdf-rdf4j`** aligned with the rest of your app when you mix APIs.

### Kotlin: parse Turtle, run default checker

```kotlin
import com.geoknoesis.kastor.ontoquality.QualityChecker
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation

val ontology = Rdf.parse(
    """
    @prefix skos: <http://www.w3.org/2004/02/skos/core#> .

    <http://example.org/s> a skos:ConceptScheme ;
        skos:prefLabel "Demo scheme"@en .

    <http://example.org/c> a skos:Concept ;
        skos:inScheme <http://example.org/s> ;
        skos:prefLabel "Concept"@en .
    """.trimIndent(),
    format = "TURTLE",
)

val validator = ShaclValidation.validator()
val report = QualityChecker.default(validator).check(ontology)

println(report.describeText())
println("Conforms: ${report.conforms}")
```

Use **`QualityChecker.builder(validator).addCatalog(...)`** with **`BundledCatalogs`** entries when you want a **subset** of catalogues (for example SKOS-only stacks)—see [How to check ontology quality](../guides/how-to-ontology-quality.md).

### CLI (`onto-qa`)

Same catalogues from the command line (requires the **`onto-qa`** distribution / classpath described in the how-to):

```bash
onto-qa check my-ontology.ttl --catalog all --reasoner none
onto-qa check my-ontology.ttl --catalog skos-vocabulary --reasoner none
```

Reasoning-aware runs (`--reasoner rdfs`, `hermit`, …) are covered in the guide.

**Explanation / tiers:** [Ontology quality](../features/ontology-quality.md).

---

## 8. Standards and conformance

- **RDF 1.2 syntax**: Jena and RDF4J providers are driven against **W3C RDF 1.2 syntax** manifests (Turtle, TriG, N-Triples, N-Quads). See [RDF 1.2 conformance](../concepts/rdf-1.2-conformance.md).
- **SHACL 1.2**: Native validator aims for **full SHACL 1.2** (Core + SPARQL-related extensions); see the feature page linked above.

---

## Complete listing (one `main`)

Use Gradle dependencies for **`rdf-core`**, **`rdf-jena`** (or RDF4J), **`sparql-lang`**, and **`rdf-shacl-validation`** + **`rdf-shacl-dsl`** when you want the SHACL block. **`onto-quality`** is separate—see [§7](#7-ontology-quality-onto-quality).

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.dsl.*
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ValidationProfile
import com.geoknoesis.kastor.rdf.sparql.*
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF

fun main() {
    // --- Repository + DSL ---
    val repo = Rdf.memory()
    repo.add {
        val alice = iri("http://example.org/alice")
        alice has FOAF.name with "Alice Example"
        alice has RDF.type with FOAF.Person
    }

    // --- SPARQL (string) ---
    val names = repo.select(
        SparqlSelectQuery(
            """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT ?name WHERE { ?s foaf:name ?name . }
            """.trimIndent(),
        ),
    )
    names.forEach { println("String SPARQL: ${it.getString("name")}") }

    // --- SPARQL (Kotlin DSL — needs sparql-lang) ---
    val dslQuery = select("name") {
        version("1.2")
        prefix("foaf", FOAF.namespace)
        where {
            pattern(var_("person"), FOAF.name, var_("name"))
            pattern(var_("person"), RDF.type, FOAF.Person)
        }
    }
    repo.select(dslQuery).forEach { println("DSL SPARQL: ${it.getString("name")}") }

    // --- Same API, different backend (optional) ---
    val alsoJena = Rdf.repository {
        providerId = "jena"
        variantId = "memory"
    }
    alsoJena.add {
        val bob = iri("http://example.org/bob")
        bob has FOAF.name with "Bob"
    }

    // --- SHACL 1.2 (needs rdf-shacl-validation + rdf-shacl-dsl) ---
    val shapes = shacl {
        nodeShape("http://example.org/shapes/Person") {
            targetClass(FOAF.Person)
            property(FOAF.name) { minCount = 1 }
        }
    }
    val report =
        ShaclValidation.validator(ValidationProfile.SHACL_CORE)
            .validate(repo.defaultGraph, shapes)
    println("SHACL report valid: ${report.isValid}")
}
```

---

## Where to go next

| Goal | Page |
|------|------|
| Install variants & optional modules | [Installation](installation.md) |
| Kotlin SPARQL DSL deep dive | [Kastor query DSL tutorial](../guides/kastor-query-dsl-tutorial.md) |
| Ontology quality (catalogues, CLI, reasoning) | [How to check ontology quality](../guides/how-to-ontology-quality.md) |
| Kastor Gen plugin & tasks | [Gradle plugin reference](../../kastor-gen/reference/gradle-plugin.md) |
| Longer guided tour | [Quick Start](quick-start.md) |
| Architecture & modules | [Repository architecture](../concepts/architecture.md) |
| Reasoning providers | [Reasoning](../features/reasoning.md) |
| Examples repo | [Examples](../examples/README.md) |
