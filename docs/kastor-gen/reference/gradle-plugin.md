# Kastor Gen Gradle Plugin

{% include version-banner.md %}

## Overview

The Kastor Gen Gradle plugin (`com.geoknoesis.kastor.gen`) generates code from a SHACL shapes file and an
optional JSON-LD context file at build time, configured entirely in the build script (no `@Rdf` annotation
needed).
For each configured ontology it can generate:

- domain interfaces,
- RDF-backed wrappers (registered with `OntoMapper`),
- a vocabulary object of `Iri` constants,
- an instance-DSL builder.

## Plugin ID

```
com.geoknoesis.kastor.gen
```

## Installation

> Kastor Gen is **not yet published** to the Gradle Plugin Portal or Maven Central. Until it is, build it
> from the [Kastor repository](https://github.com/geoknoesis/kastor) and consume it from Maven Local.

Publish the plugin and the runtime from a Kastor checkout (requires JDK 21+):

```bash
./gradlew :kastor-gen:gradle-plugin:publishToMavenLocal :kastor-gen:runtime:publishToMavenLocal :rdf:core:publishToMavenLocal
```

`java-gradle-plugin` also publishes the plugin marker, so the plugin id resolves from Maven Local. Use the
Kastor build's project version (the `version` in its root `build.gradle.kts`) below:

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
    id("com.geoknoesis.kastor.gen") version "0.3.0-SNAPSHOT"
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("com.geoknoesis.kastor:kastor-gen-runtime:0.3.0-SNAPSHOT")
}
```

Inside the Kastor build itself, apply the plugin by id and depend on `project(":kastor-gen:runtime")`.

The plugin has no runtime dependency on the Kotlin Gradle plugin: it finds the Kotlin source sets
reflectively, so it never adds a second copy of KGP classes to your build.

## Configuration

```kotlin
kastorGen {
    ontologies {
        create("dcat") {
            shaclPath = "ontologies/dcat-us.shacl.ttl"
            contextPath = "ontologies/dcat-us.context.jsonld"
            interfacePackage = "com.example.dcatus"   // required
        }
    }
}
```

`kastorGen.ontology("dcat") { … }` is an equivalent shorthand for `ontologies { create("dcat") { … } }`.

### Complete configuration

```kotlin
kastorGen {
    ontologies {
        create("dcat") {
            shaclPath = "ontologies/dcat-us.shacl.ttl"
            contextPath = "ontologies/dcat-us.context.jsonld"

            interfacePackage = "com.example.dcatus.model"      // required
            wrapperPackage = "com.example.dcatus.rdf"          // default: interfacePackage
            vocabularyPackage = "com.example.dcatus.vocab"     // default: interfacePackage
            dslPackage = "com.example.dcatus.dsl"              // default: <interfacePackage>.dsl

            generateInterfaces = true                          // default: true
            generateWrappers = true                            // default: true
            generateDsl = true                                 // default: false
            dslName = "dcat"                                   // default: derived from the context (else SHACL) file name

            vocabularyName = "DCAT"                            // these three together enable
            vocabularyNamespace = "http://www.w3.org/ns/dcat#" // vocabulary generation
            vocabularyPrefix = "dcat"

            outputDirectory = "src/generated/kotlin"           // default: build/generated/sources/kastor-gen
        }
    }
}
```

## Configuration properties (`OntologyConfig`)

Every optional setting is `null` until you assign it; the task then applies the default below.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `shaclPath` | `String` | required | SHACL file. Absolute, or relative to the project directory; if not found there, relative to `src/main/resources` |
| `contextPath` | `String` | optional | JSON-LD context file, resolved the same way. Without it, type and property names come from IRI local names and `sh:name` |
| `interfacePackage` | `String?` | **required** — the task fails with `interfacePackage must be set` | Package for interfaces |
| `wrapperPackage` | `String?` | `interfacePackage` | Package for wrappers |
| `vocabularyPackage` | `String?` | `interfacePackage` | Package for the vocabulary object |
| `dslPackage` | `String?` | `<interfacePackage>.dsl` | Package for the DSL |
| `generateInterfaces` | `Boolean?` | `true` | Generate interfaces |
| `generateWrappers` | `Boolean?` | `true` | Generate wrappers |
| `generateVocabulary` | `Boolean?` | `true` when `vocabularyName`, `vocabularyNamespace` and `vocabularyPrefix` are all set, else `false` | Generate a vocabulary object; if set to `true`, all three are required |
| `vocabularyName` | `String?` | — | Vocabulary object name (converted to an upper-case identifier, e.g. `DCAT`) |
| `vocabularyNamespace` | `String?` | — | Namespace IRI |
| `vocabularyPrefix` | `String?` | — | Prefix |
| `generateDsl` | `Boolean?` | `false` | Generate the instance DSL |
| `dslName` | `String?` | derived from the context file name (the SHACL file name when no context is set) | Top-level DSL function name; an explicit value must match `[a-zA-Z][a-zA-Z0-9]*` |
| `outputDirectory` | `String?` | `build/generated/sources/kastor-gen` | Output base directory, relative to the project directory; the ontology name is appended |

Validation performed before anything is written:

- The ontology name must match `[A-Za-z][A-Za-z0-9_-]*`.
- Every configured package must be a valid Kotlin package name (no keywords, valid segments).
- A derived DSL name is sanitised into an identifier (`dcat-us_3.0_context` → `dcatUs30Context`; a
  leading digit gets a `dsl` prefix).

## Generated tasks

| Task | Description |
|---|---|
| `generateOntology<Name>` | One `OntologyGenerationTask` per configuration (`dcat` → `generateOntologyDcat`) |
| `generateOntology` | Depends on all of them |

All tasks are in the `kastor-gen` group (`./gradlew tasks --group=kastor-gen`).

### Automatic source-set wiring

Each task's output directory is added to the `main` Kotlin source set of `org.jetbrains.kotlin.jvm`
projects, or, in `org.jetbrains.kotlin.multiplatform` projects, to the default source set of the `main`
compilation of **every JVM target**, whatever its name (`jvm()`, `jvm("desktop")`, …, including targets
added later). Because the source directory is the task's output, compiling Kotlin runs generation first — no
`sourceSets { … }` or `dependsOn` is needed.

- The generated code is JVM-only: a multiplatform project without a JVM target fails at the end of
  configuration with a clear error.
- A project that configures `kastorGen` ontologies but applies neither `org.jetbrains.kotlin.jvm` nor
  `org.jetbrains.kotlin.multiplatform` fails at configuration time instead of silently not compiling the
  generated sources. **Android projects are not supported**; there the `generateOntology<Name>` tasks still
  work, so add their `outputDirectory` to a source set yourself.

### `OntologyGenerationTask`

- `ontologyName` — the configuration name, used in diagnostics (`kastorGen ontology 'dcat': …`).
- `shaclCandidates` / `contextCandidates` — `@InputFiles`: the locations the paths may resolve to (project
  directory, then `src/main/resources`). The first existing file is chosen when the task runs, so editing
  an ontology file, or adding one at the preferred location, re-runs the task without invalidating the
  configuration cache. `shaclInput` / `contextInput` return the file that will be read.
- `shaclFile` / `contextFile` — optional `@InputFile` properties, unset by default; set one to name the file
  directly instead of through a path.
- `@CacheableTask`; execution does not touch `Project`, so the task is configuration-cache compatible.
- **All-or-nothing:** every file is generated in memory first. SHACL/JSON-LD parse errors, name
  collisions, invalid packages or missing required settings fail the task *before* the output directory is
  touched. Then:
  1. every entry of the previous run's manifest (`.kastor-generated-files`) is validated — an entry that
     escapes the output directory fails the task before anything is deleted;
  2. the complete new output is written to a staging directory, so an I/O failure leaves the previous
     output and manifest untouched;
  3. the manifest is first rewritten to list the previous **and** the new files; then the files listed in
     the old manifest are deleted, the staged files are moved in, and the manifest is rewritten to list only
     the new files once every move has succeeded.

  Renamed shapes therefore never leave stale files, including case-only renames on case-insensitive file
  systems. Replacing the output is also robust against failures:

  - Deletes and moves that fail with a transient lock (`AccessDeniedException`, or a
    `FileSystemException` such as "being used by another process" when an IDE, indexer or virus scanner holds
    a file on Windows) are retried with exponential backoff. Missing files are not retried.
  - Because the manifest lists the previous and the new files until every move succeeds, a failure part-way
    never leaves an untracked generated file: the next run deletes everything the manifest lists.
  - When the staging directory and the output directory are on different file stores (for example different
    drives), each file is copied next to its target and then renamed over it, so the target is never left
    half-written and the previous target stays intact on failure; the staged source is deleted last.
  - An I/O failure while replacing fails the task with a message explaining how to recover (see
    [Troubleshooting](#troubleshooting)).

See [Incremental Builds](../guides/incremental-builds.md).

## Generated code

### Interfaces and wrappers

Interfaces and wrappers are the same as those produced by the KSP processor (see
[Ontology Generation](../tutorials/ontology-generation.md) for type mapping, naming and inheritance).
Interfaces carry no Jakarta/javax validation annotations. Wrappers are `internal`, register themselves with
`OntoMapper.register`, and include the embedded constraint `validate()`.

When `wrapperPackage` differs from `interfacePackage`, the task also emits a small internal
`<Interface>Factory` object in the interface package that loads the wrapper, so `OntoMapper`'s
auto-discovery (`<Interface>Wrapper` / `<Interface>Factory` next to the interface) still works.

```kotlin
val catalog: Catalog = graph.materialize(Iri("https://data.example.org/catalog"))
```

### Vocabulary object

Built with KotlinPoet (descriptions, quotes and `$` cannot break the source) and deterministic (classes,
then properties, each sorted by IRI):

```kotlin
// GENERATED FILE - DO NOT EDIT
package com.example.dcatus.vocab

/** DCAT vocabulary. Generated from ontology files. */
object DCAT : Vocabulary {
    override val namespace: String = "http://www.w3.org/ns/dcat#"
    override val prefix: String = "dcat"

    /** IRI: http://www.w3.org/ns/dcat#Catalog */
    val Catalog: Iri by lazy { term("Catalog") }

    /** A name given to the resource. IRI: http://purl.org/dc/terms/title */
    val title: Iri by lazy { Iri("http://purl.org/dc/terms/title") }   // outside the dcat namespace
}
```

- Terms come from the shapes' target classes and property paths plus the context's type/property terms;
  the Kotlin name is the JSON-LD context term, else the IRI local name.
- Terms inside `vocabularyNamespace` use `term("local")`; terms from other namespaces (e.g. `dct:title` in
  a DCAT vocabulary) are emitted as full `Iri("…")` values.
- When several IRIs map to the same name, the in-namespace term keeps the plain name and the others are
  prefixed with their JSON-LD prefix (`dct_title`); any remaining clash fails the task and lists the IRIs.

### Instance DSL

With `generateDsl = true`, a type-safe builder DSL named `dslName` is generated into `dslPackage` for
creating RDF instances of the shapes.

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

This creates `generateOntologyDcat`, `generateOntologyFoaf` and the aggregate `generateOntology`; each
writes to its own directory (`build/generated/sources/kastor-gen/dcat`, `…/foaf`).

## Troubleshooting

**`kastorGen ontology 'x': interfacePackage must be set`** — `interfacePackage` is required.

**`… is not a valid Kotlin package name`** — a package segment is a keyword or contains invalid characters.

**`name collisions: …`** — two classes or properties map to the same Kotlin name (types are compared
case-insensitively). Add distinct JSON-LD context terms or `sh:name` values.

**`vocabulary term name collisions: …`** — distinct IRIs still share a name after prefix qualification;
add distinct context terms.

**`cannot read SHACL file …`** — the file does not exist or is not valid Turtle. Relative paths are tried
against the project directory, then `src/main/resources`; `--info` logs the resolved absolute paths.

**`kastor-gen: no JVM target found in …`** — add a JVM target (`jvm()` or `jvm("name")`) to the
multiplatform project.

**`kastor-gen: … configures kastorGen ontologies but applies neither org.jetbrains.kotlin.jvm nor
org.jetbrains.kotlin.multiplatform`** — apply one of them. Android projects are not supported; add the
`outputDirectory` of the `generateOntology<Name>` tasks to a source set yourself.

**`generated-files manifest escapes the output directory`** — `.kastor-generated-files` in the output
directory was edited or corrupted; nothing was deleted. Remove the output directory and run the task again.

**`cannot write generated files: …`** — writing to the staging directory failed; the previous output is
unchanged.

**`cannot replace the generated files in … (…); the output may be incomplete`** — deleting or moving a
generated file failed, even after retries (typically a file locked by another program on Windows). Close
the programs holding the files and run the task again: every file written so far is listed in the manifest
and will be replaced.

## Related Documentation

- [Gradle Configuration Tutorial](../tutorials/gradle-configuration.md)
- [Incremental Builds Guide](../guides/incremental-builds.md)
- [Ontology Generation](../tutorials/ontology-generation.md)
- [Processor Reference](processor.md)
