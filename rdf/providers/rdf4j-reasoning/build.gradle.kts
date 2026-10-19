plugins {
  kotlin("jvm")
  `java-library`
  id("maven-publish")
}

dependencies {
  implementation(libs.rdf4j.model.api)
  implementation(libs.rdf4j.model.vocabulary)
  implementation(libs.rdf4j.model)
  implementation(libs.rdf4j.repository.api)
  implementation(libs.rdf4j.sail.api)
  api(project(":rdf:core"))

  implementation(project(":rdf:rdf4j"))
  api(project(":rdf:reasoning"))
  implementation(libs.rdf4j.repository.sail)
  implementation(libs.rdf4j.sail.memory)
  implementation(libs.rdf4j.sail.inferencer)
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "rdf-rdf4j-reasoning"
      version = project.version.toString()
    }
  }
}
