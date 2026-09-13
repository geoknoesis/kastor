# Incremental Builds

{% include version-banner.md %}

## Overview

Kastor Gen can generate code in two ways, and they behave differently when ontology files change:

| | KSP processor (`@Rdf(shacl = …)`) | Gradle plugin (`com.geoknoesis.kastor.gen`) |
|---|---|---|
| Re-runs when the annotated Kotlin file changes | yes | n/a |
| Re-runs when the SHACL / JSON-LD file changes | **no** (see below) | yes — the files are task inputs |
| Build cache | via KSP/Kotlin compilation | task is `@CacheableTask` |
| Configuration cache | — | compatible |

## KSP processor

The processor registers its outputs as *aggregating* over the annotated source files, so KSP regenerates
them whenever those Kotlin sources change.

**Known limitation:** KSP cannot observe files under `src/main/resources`. Editing only
`person-shape.ttl` or the JSON-LD context does **not** trigger regeneration. After editing an ontology
resource, do one of:

- touch (or edit) the Kotlin file that carries the `@Rdf(shacl = …)` / `@file:Rdf(shacl = …)` annotation;
- run a clean build of the module (for example `./gradlew :my-module:clean :my-module:build`);
- use the Gradle plugin instead, which tracks the ontology files as real inputs.

Generation is all-or-nothing: all files for one annotation are generated in memory and checked for
(case-insensitive) file-name collisions before anything is written. A Turtle syntax error or a name
collision fails the build instead of leaving empty or partial output.

Relative ontology paths are resolved from the annotated file's source set first
(`src/<set>/resources`, then `src/main/resources`, then the project directory), then from the
directories listed in the KSP option `kastor.gen.resources`:

```kotlin
ksp {
    arg("kastor.gen.resources", "${projectDir}/ontologies")
}
```

## Gradle plugin task

Each configured ontology gets an `OntologyGenerationTask` (`generateOntology<Name>`).

### Inputs and outputs

| Property | Annotation | Notes |
|---|---|---|
| `shaclFile` | `@InputFile`, `@PathSensitive(RELATIVE)` | resolved from `shaclPath` (project directory, then `src/main/resources`) |
| `contextFile` | `@InputFile`, `@PathSensitive(RELATIVE)` | resolved from `contextPath` |
| `interfacePackage`, `wrapperPackage`, `vocabularyPackage`, `dslPackage` | `@Input @Optional` | |
| `generateInterfaces`, `generateWrappers`, `generateVocabulary`, `generateDsl` | `@Input @Optional` | |
| `vocabularyName`, `vocabularyNamespace`, `vocabularyPrefix`, `dslName` | `@Input @Optional` | |
| `outputDirectory` | `@OutputDirectory` | default `build/generated/sources/kastor-gen/<name>` |
| `ontologyName`, `shaclPath`, `contextPath` | `@Internal` | used for resolution and diagnostics only |

Because the ontology **contents** are inputs, editing either file re-runs the task; no manual
`inputs.files(...)` configuration is needed. The task is `@CacheableTask`, so its output can be restored
from the local or remote build cache.

### Stale output

The task writes a manifest (`.kastor-generated-files`) in its output directory. On each run it first
generates everything in memory and fails — without touching the output directory — on parse errors, name
collisions or invalid configuration. Only then does it delete the files recorded by the previous run and
write the new ones. Renamed or removed shapes therefore never leave stale files behind, including
case-only renames on case-insensitive file systems.

### Wiring

The plugin adds each task's output directory to the `main` Kotlin source set (`jvmMain` for Kotlin
Multiplatform projects), so compilation depends on generation automatically. No `sourceSets` or
`dependsOn` configuration is required.

## Troubleshooting

**Generated code does not reflect an ontology edit (KSP).** Touch the annotated Kotlin file or clean the
module; see the limitation above.

**Generated code does not reflect an ontology edit (Gradle plugin).** Check that `shaclPath` /
`contextPath` point at the file you edited (`./gradlew generateOntology<Name> --info` logs the resolved
absolute paths).

**Build fails with `name collisions`.** Two classes or properties map to the same Kotlin name. The message
lists the IRIs; add distinct JSON-LD context terms (types) or `sh:name` values (properties).

**Forcing regeneration.**

```bash
./gradlew generateOntology --rerun-tasks
```

## Related Documentation

- [Gradle Plugin Reference](../reference/gradle-plugin.md)
- [Gradle Configuration](../tutorials/gradle-configuration.md)
- [KSP Documentation](https://kotlinlang.org/docs/ksp-overview.html)
- [Gradle Build Cache](https://docs.gradle.org/current/userguide/build_cache.html)
