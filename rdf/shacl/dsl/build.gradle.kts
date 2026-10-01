plugins {
  id("maven-publish")
}

dependencies {
  api(project(":rdf:core"))
  api(project(":rdf:sparql-lang"))
  testRuntimeOnly(project(":rdf:jena"))
  // DSL-built shapes graphs are validated with the native engine in tests.
  testImplementation(project(":rdf:shacl-validation"))
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "rdf-shacl-dsl"
      version = project.version.toString()
    }
  }
}
