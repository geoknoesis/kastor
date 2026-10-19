plugins {
  id("maven-publish")
}

dependencies {
  testImplementation(libs.jena.core)
  api(project(":rdf:sparql-contract"))

  api(project(":rdf:core"))
  api(libs.kotlinx.coroutines.core)
  testRuntimeOnly(project(":rdf:jena"))
  // Rendered queries/updates/Turtle are validated with Jena's parsers in tests.
  testImplementation(libs.jena.arq)
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "rdf-sparql-lang"
      version = project.version.toString()
    }
  }
}
