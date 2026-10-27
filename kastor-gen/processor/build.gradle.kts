plugins {
  id("org.jetbrains.kotlin.jvm")
  id("java-library")
  id("org.jetbrains.kotlin.plugin.serialization")
  id("maven-publish")
}

// Limit explicit-api enforcement to *main* sources only; tests don't need to
// declare visibility on every symbol. We keep it as a warning rather than an
// error because the legacy modules still have unannotated public API.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin").configure {
    compilerOptions {
        freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}

// The `internal` packages are implementation: public only across the module's own packages, not part of the ABI.
kotlin {
  // The shared XSD lexical parsers of :rdf:core (com.geoknoesis.kastor.rdf.xsd) are internal Kastor API.
  compilerOptions { optIn.add("com.geoknoesis.kastor.rdf.InternalKastorApi") }
  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
  abiValidation {
    filters {
      exclude {
        byNames.add("com.geoknoesis.kastor.gen.processor.internal.**")
      }
    }
  }
}

dependencies {
  implementation(libs.jena.core)
  testRuntimeOnly(libs.slf4j.api)
  api(project(":rdf:core"))
  // XPathRegex / SandboxRegex: generated sh:pattern checks use the validator's linear-time regex engine.
  implementation(project(":rdf:sparql-contract"))

  api(project(":kastor-gen:runtime"))
  
  // KSP dependencies
  api(libs.ksp.symbol.processing.api)

  // KotlinPoet for type-safe code generation
  api(libs.kotlinpoet)


  // JSON serialization for JSON-LD context parsing
  implementation(libs.kotlinx.serialization.json)
  
  // Optional: Jena for compile-time schema parsing
  runtimeOnly(libs.jena.arq)
  
  testImplementation(libs.kotlin.test)
  // The real Bean Validation API, which compiled generated sources and KSP test runs see on the class path.
  testRuntimeOnly(libs.jakarta.validation.api)
  // Providers for the round trips of generated records through Jena and RDF4J repositories.
  testRuntimeOnly(project(":rdf:jena"))
  testRuntimeOnly(project(":rdf:rdf4j"))

  // In-process Kotlin compiler used by tests that compile generated sources for tricky
  // ontology inputs (same artifact/version the Kotlin Gradle plugin already resolves).
  testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
  // KSP2 in process, so tests run the real processors over Kotlin sources (same artifact the KSP Gradle plugin uses).
  testImplementation(libs.ksp.symbol.processing.aa.embeddable)
  testImplementation(libs.ksp.symbol.processing.common.deps)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
  // Compiling generated sources in-process needs more than the 512m worker default.
  maxHeapSize = "1g"
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "kastor-gen-processor"
      version = project.version.toString()
    }
  }
}

// KSP configuration will be applied by root build.gradle.kts
