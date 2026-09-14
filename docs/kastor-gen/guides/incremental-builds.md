# Incremental Builds

{% include version-banner.md %}

## Overview

Kastor Gen can generate code in two ways, and they behave differently when ontology files change:

| | KSP processor (`@Rdf(shacl = …)`) | Gradle plugin (`com.geoknoesis.kastor.gen`) |
|---|---|---|
| Re-runs when the annotated Kotlin file changes | yes | n/a |
| Re-runs when the SHACL / JSON-LD file changes | **only if you declare the files as KSP task inputs** (see below) | yes — the files are task inputs |
| Build cache | via KSP/Kotlin compilation | task is `@CacheableTask` |
| Configuration cache | — | compatible |

## KSP processor

The processor registers ontology-generated outputs as *aggregating* over the annotated source files, so KSP
regenerates them whenever those Kotlin sources change. Wrappers for hand-written `@Rdf` interfaces depend
only on the interface's own file and the files of its supertypes, so editing an unrelated source does not
invalidate every wrapper.

Each request (package + ontology files) is generated at most once per compilation, across all KSP rounds:
a symbol presented again in a later round (for example because its file references types generated in
the first round) is not generated twice.

**KSP cannot observe ontology files.** Editing only `person-shape.ttl` or the JSON-LD context does not by
itself trigger regeneration, so the processor logs a **warning for each ontology file it reads**, naming
the file. Pick one of:

- **Recommended with KSP:** declare the resources as inputs of the KSP task and tell the processor they are
  tracked, which also silences the warning. This is what `examples/hello-codegen` and `examples/dcat-us`
  do:

  ```kotlin
  // build.gradle.kts
  tasks.matching { it.name == "kspKotlin" }.configureEach {
      inputs.dir("src/main/resources")
          .withPropertyName("kastorOntologyFiles")
          .withPathSensitivity(PathSensitivity.RELATIVE)
  }
  ksp {
      arg("kastor.gen.resources.tracked", "true")
  }
  ```

  Only set `kastor.gen.resources.tracked=true` when the files really are task inputs; otherwise generated
  code silently goes stale.
- use the Gradle plugin instead, which tracks the ontology files as real inputs;
- as a one-off, touch the annotated Kotlin file or run a clean build of the module
  (`./gradlew :my-module:clean :my-module:build`).

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

Relative `kastor.gen.resources` entries are resolved against the KSP option `kastor.gen.projectDir`. Without
it they would depend on the compiler's working directory (the Gradle daemon's, not the project's), so a
relative entry that does not exist there **fails generation** with a message explaining the fix:

```kotlin
ksp {
    arg("kastor.gen.projectDir", projectDir.absolutePath)
    arg("kastor.gen.resources", "ontologies")
}
```

If the directories hold your ontology files, declare them as KSP task inputs too (see above).

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

**Generated code does not reflect an ontology edit (KSP).** The ontology files are not inputs of the KSP
task. Declare them as `kspKotlin` inputs and set `kastor.gen.resources.tracked=true` (see above); as a
one-off, touch the annotated Kotlin file or clean the module.

**Warning `kastor-gen: KSP does not track changes to the ontology file …`.** Same cause; the warning
disappears once `kastor.gen.resources.tracked=true` is set.

**`relative entry '…' of kastor.gen.resources does not exist relative to the compiler working directory`.**
Use an absolute path or set `arg("kastor.gen.projectDir", projectDir.absolutePath)`.

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
