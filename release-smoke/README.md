This is an independent Gradle build. It resolves Kastor exclusively from the staged Maven repository, without project dependencies or `mavenLocal()`.

From the repository root:

```sh
./gradlew publishAllPublicationsToStagingRepository
./gradlew -p release-smoke run
```

The dependency graph exercises the BOM, both RDF providers, runtime, and both validation adapters. A separate TestKit fixture in the Gradle plugin tests compilation and execution of generated cross-package domain types.
