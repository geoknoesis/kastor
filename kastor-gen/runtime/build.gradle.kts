plugins {
  id("org.jetbrains.kotlin.jvm")
  id("java-library")
  id("maven-publish")
}

dependencies {
  api(project(":rdf:core"))
  implementation(libs.slf4j.api)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
  // Tests rely on `Rdf.memory()`, which now requires a SPARQL-capable provider
  // to be discoverable via ServiceLoader. Adding Jena as a runtime-only test
  // dependency keeps the runtime module compile-time-decoupled from any
  // particular provider.
  testRuntimeOnly(project(":rdf:jena"))
}

// GraphStateCache and its settings are shared with the validation adapters only (@KastorGenInternalApi, opt-in at
// ERROR level): they are not part of the supported ABI and stay out of the dump.
kotlin {
  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
  abiValidation {
    filters {
      exclude {
        annotatedWith.add("com.geoknoesis.kastor.gen.runtime.KastorGenInternalApi")
      }
    }
  }
}

tasks.test {
  // FactoryRegistryRulesTest proves that the registry holds registered classes weakly by walking its object graph
  // (JDK collections included) instead of waiting for a garbage collection.
  jvmArgs(
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.ref=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED",
  )
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "kastor-gen-runtime"
      version = project.version.toString()
    }
  }
}
