plugins {
  kotlin("jvm")
  `java-library`
  id("maven-publish")
}

dependencies {
  implementation(libs.jena.core)
  api(project(":rdf:core"))

  implementation(project(":rdf:jena"))
  api(project(":rdf:reasoning"))
  runtimeOnly(libs.jena.arq)
  // Cross-provider parity tests (ReasonerAxiomParityTest).
  testImplementation(project(":rdf:rdf4j-reasoning"))
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "rdf-jena-reasoning"
      version = project.version.toString()
    }
  }
}
