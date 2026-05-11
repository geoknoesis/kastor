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

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = project.name
      version = project.version.toString()
    }
  }
}
