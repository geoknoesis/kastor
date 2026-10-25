plugins {
  id("maven-publish")
}

dependencies {
  implementation(libs.jena.core)
  testImplementation(project(":rdf:sparql-contract"))

  api(project(":rdf:core"))
  implementation(project(":rdf:jena"))
  implementation(libs.jena.arq)
  testImplementation(project(":rdf:rdf4j"))
  // A plain RDF4J store wrapped by Rdf4jRepository, for the sandboxed-query contract on stores Kastor did not create.
  testImplementation(libs.rdf4j.repository.sail)
  testImplementation(libs.rdf4j.sail.memory)
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "rdf-testkit"
      version = project.version.toString()
    }
  }
}
