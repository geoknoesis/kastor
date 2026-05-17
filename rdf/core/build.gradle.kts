plugins {
  id("maven-publish")
}

dependencies {
  api(project(":rdf:sparql-contract"))
  implementation(libs.kotlinx.coroutines.core)
  // Core has no other main-source deps beyond those declared at root.
  // For tests we add a real provider so calls to `Rdf.memory()` resolve to a
  // SPARQL-capable backend. We deliberately use `testRuntimeOnly` so the core
  // module never compiles against provider types.
  testRuntimeOnly(project(":rdf:jena"))
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

