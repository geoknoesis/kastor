# Gradle Configuration for Ontology Generation

This tutorial generates domain interfaces, wrappers and a vocabulary from SHACL and JSON-LD context files
using only Gradle configuration — no `@Rdf` annotation in your sources. Every option is listed in the
[Gradle Plugin Reference](../reference/gradle-plugin.md).

## When to use the plugin instead of KSP

| | Gradle plugin | KSP `@Rdf(shacl = …)` |
|---|---|---|
| Configuration | build script | annotation in a Kotlin file |
| Ontology file edits trigger regeneration | yes (task inputs) | no — touch the annotated file or clean |
| Vocabulary object generation | yes | no |
| Data classes, `NestedMode`, write support | no | yes |

## Step 1: Apply the plugin

Kastor Gen is not yet published. Publish it to Maven Local from a Kastor checkout first:

```bash
./gradlew :kastor-gen:gradle-plugin:publishToMavenLocal :kastor-gen:runtime:publishToMavenLocal :rdf:core:publishToMavenLocal
```

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("com.geoknoesis.kastor.gen") version "0.3.0-SNAPSHOT"   // the Kastor build's project version
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("com.geoknoesis.kastor:kastor-gen-runtime:0.3.0-SNAPSHOT")
    // plus an RDF provider, e.g. com.geoknoesis.kastor:rdf-jena
}
```

Inside the Kastor build, apply `id("com.geoknoesis.kastor.gen")` and use `project(":kastor-gen:runtime")`.

## Step 2: Add the ontology files

```
project/
├── build.gradle.kts
└── src/main/resources/
    └── ontologies/
        ├── dcat-us.shacl.ttl
        └── dcat-us.context.jsonld
```

Paths may be relative to the project directory or to `src/main/resources`.

## Step 3: Configure an ontology

```kotlin
kastorGen {
    ontologies {
        create("dcat") {
            shaclPath = "ontologies/dcat-us.shacl.ttl"
            contextPath = "ontologies/dcat-us.context.jsonld"
            interfacePackage = "com.example.dcatus"
        }
    }
}
```

`interfacePackage` is the only required setting besides the two paths. Wrappers and the vocabulary default
to the same package, and the DSL to `com.example.dcatus.dsl`.

## Step 4: Build

```bash
./gradlew build
```

The plugin registers `generateOntologyDcat` (and the aggregate `generateOntology`) and adds its output
directory, `build/generated/sources/kastor-gen/dcat`, to the `main` Kotlin source set, so `compileKotlin`
runs generation automatically. You do not need to add `sourceSets` or `dependsOn`.

Run generation on its own with:

```bash
./gradlew generateOntologyDcat
```

## Step 5: Use the generated code

```kotlin
import com.example.dcatus.Catalog
import com.geoknoesis.kastor.gen.runtime.materialize
import com.geoknoesis.kastor.rdf.*

val repo = Rdf.memory()
// ... load DCAT-US data ...
val catalog: Catalog = repo.defaultGraph.materialize(Iri("https://data.example.gov/catalog"))
println(catalog.title)
```

## Separate packages

```kotlin
kastorGen {
    ontologies {
        create("dcat") {
            shaclPath = "ontologies/dcat-us.shacl.ttl"
            contextPath = "ontologies/dcat-us.context.jsonld"

            interfacePackage = "com.example.dcatus.model"
            wrapperPackage = "com.example.dcatus.rdf"
            vocabularyPackage = "com.example.dcatus.vocab"
        }
    }
}
```

Wrappers are `internal`; application code uses the interfaces and `materialize`. When the wrapper package
differs, a small generated `<Interface>Factory` in the interface package keeps `OntoMapper`
auto-discovery working.

## Vocabulary generation

Setting all three vocabulary properties enables vocabulary generation:

```kotlin
kastorGen {
    ontologies {
        create("dcat") {
            shaclPath = "ontologies/dcat-us.shacl.ttl"
            contextPath = "ontologies/dcat-us.context.jsonld"
            interfacePackage = "com.example.dcatus"

            vocabularyName = "DCAT"
            vocabularyNamespace = "http://www.w3.org/ns/dcat#"
            vocabularyPrefix = "dcat"
        }
    }
}
```

Result (abridged):

```kotlin
object DCAT : Vocabulary {
    override val namespace: String = "http://www.w3.org/ns/dcat#"
    override val prefix: String = "dcat"

    val Catalog: Iri by lazy { term("Catalog") }
    val Dataset: Iri by lazy { term("Dataset") }
    val dataset: Iri by lazy { term("dataset") }
    val title: Iri by lazy { Iri("http://purl.org/dc/terms/title") }   // outside the dcat namespace
}
```

Terms outside the vocabulary namespace are written as full `Iri("…")` values. If two IRIs would get the
same name, the in-namespace term keeps it and the other is prefixed with its JSON-LD prefix
(`dct_title`).

For a vocabulary only:

```kotlin
create("dcatVocab") {
    shaclPath = "ontologies/dcat-us.shacl.ttl"
    contextPath = "ontologies/dcat-us.context.jsonld"
    interfacePackage = "com.example.dcatus"   // still required
    generateInterfaces = false
    generateWrappers = false
    vocabularyName = "DCAT"
    vocabularyNamespace = "http://www.w3.org/ns/dcat#"
    vocabularyPrefix = "dcat"
}
```

Set `generateVocabulary = false` to switch it off even when the metadata is present.

## DSL generation

```kotlin
create("dcat") {
    shaclPath = "ontologies/dcat-us.shacl.ttl"
    contextPath = "ontologies/dcat-us.context.jsonld"
    interfacePackage = "com.example.dcatus"
    generateDsl = true
    dslName = "dcat"          // optional
}
```

Without `dslName` the name is derived from the context file name and sanitised into an identifier
(`dcat-us.context.jsonld` → `dcatUsContext`). An explicit `dslName` must match `[a-zA-Z][a-zA-Z0-9]*`.

## Multiple ontologies

```kotlin
kastorGen {
    ontologies {
        create("dcat") {
            shaclPath = "ontologies/dcat-us.shacl.ttl"
            contextPath = "ontologies/dcat-us.context.jsonld"
            interfacePackage = "com.example.dcatus"
        }
        create("foaf") {
            shaclPath = "ontologies/foaf.shacl.ttl"
            contextPath = "ontologies/foaf.context.jsonld"
            interfacePackage = "com.example.foaf"
        }
    }
}
```

Each ontology gets its own task and output directory; `generateOntology` runs all of them.

## Conditional configuration

The configuration is ordinary Kotlin, so project properties can drive it:

```kotlin
val withDsl = providers.gradleProperty("kastor.dsl").isPresent

kastorGen {
    ontologies {
        create("dcat") {
            shaclPath = "ontologies/dcat-us.shacl.ttl"
            contextPath = "ontologies/dcat-us.context.jsonld"
            interfacePackage = "com.example.dcatus"
            generateDsl = withDsl
        }
    }
}
```

## Incremental builds

The SHACL and context files are `@InputFile`s of the task and the task is `@CacheableTask`: editing either
file re-runs generation, unchanged inputs are up to date or restored from the build cache, and files from
the previous run are removed before new ones are written. No manual `inputs.files(...)` or
`outputs.cacheIf` configuration is needed. See [Incremental Builds](../guides/incremental-builds.md).

## Troubleshooting

| Message | Fix |
|---|---|
| `kastorGen ontology 'dcat': interfacePackage must be set` | Set `interfacePackage`. |
| `cannot read SHACL file …` | Check the path (project directory, then `src/main/resources`) and the Turtle syntax. |
| `generation reported errors: …` / `name collisions: …` | Two classes or properties map to the same Kotlin name; add distinct JSON-LD context terms or `sh:name` values. The message lists the IRIs. |
| `dslName '…' must match …` | Use letters and digits only, starting with a letter. |
| `vocabularyName is required when generateVocabulary is true` | Set all three vocabulary properties, or leave `generateVocabulary` unset. |
| `Unresolved reference` in your code | Check the package you import from; generation output is wired into `main` (or `jvmMain`) automatically. |

Inspect the tasks and resolved paths with:

```bash
./gradlew tasks --group=kastor-gen
./gradlew generateOntologyDcat --info
```

## See also

- [Gradle Plugin Reference](../reference/gradle-plugin.md)
- [Ontology Generation](ontology-generation.md) — the annotation (KSP) approach, type mapping and naming
- [Domain Modeling](domain-modeling.md)
- [FAQ](../faq.md)
