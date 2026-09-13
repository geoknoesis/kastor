plugins {
  id("maven-publish")
}

dependencies {
  api(project(":rdf:core"))
  implementation(libs.kotlinx.coroutines.core)
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
