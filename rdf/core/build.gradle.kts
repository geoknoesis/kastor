plugins {
  id("maven-publish")
}

dependencies {
  implementation(libs.slf4j.api)
  // JsonLdExpandedCheck reads the expanded JSON-LD of the providers, which bring the jakarta.json API at runtime.
  compileOnly(libs.jakarta.json)

  api(project(":rdf:sparql-contract"))
  api(libs.kotlinx.coroutines.core)
  // Core has no other main-source deps beyond those declared at root.
  // For tests we add a real provider so calls to `Rdf.memory()` resolve to a
  // SPARQL-capable backend. We deliberately use `testRuntimeOnly` so the core
  // module never compiles against provider types.
  testRuntimeOnly(project(":rdf:jena"))
}

// Declarations shared with other Kastor modules only (@InternalKastorApi, opt-in at ERROR level, e.g. the XSD lexical
// parsers of com.geoknoesis.kastor.rdf.xsd) are not part of the supported ABI and stay out of the dump.
kotlin {
  compilerOptions { optIn.add("com.geoknoesis.kastor.rdf.InternalKastorApi") }
  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
  abiValidation {
    filters {
      exclude {
        annotatedWith.add("com.geoknoesis.kastor.rdf.InternalKastorApi")
      }
    }
  }
}

tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
  systemProperty(
    "kastor.core.main.sources",
    layout.projectDirectory.dir("src/main/kotlin").asFile.absolutePath,
  )
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "rdf-core"
      version = project.version.toString()
    }
  }
}

