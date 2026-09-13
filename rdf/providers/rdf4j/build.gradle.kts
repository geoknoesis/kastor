plugins {
  kotlin("jvm")
  `java-library`
  id("maven-publish")
}

// Upstream LZ4 relocated; exclude the old capability to avoid a duplicate provider.
configurations.configureEach { exclude(group = "org.lz4", module = "lz4-java") }

dependencies {
  implementation("at.yawk.lz4:lz4-java")
  api(project(":rdf:core"))
  implementation(project(":rdf:shacl-validation"))
  implementation(libs.rdf4j.repository.api)
  implementation(libs.rdf4j.repository.sail)
  implementation(libs.rdf4j.sail.memory)
  implementation(libs.rdf4j.sail.nativerdf)
  implementation(libs.rdf4j.sail.inferencer)
  implementation(libs.rdf4j.shacl)
  implementation(libs.rdf4j.queryrender)
  implementation(libs.rdf4j.rio.jsonld)
  implementation(libs.rdf4j.rio.turtle)
  implementation(libs.rdf4j.rio.rdfxml)
  implementation(libs.rdf4j.rio.n3)
  implementation(libs.rdf4j.rio.trig)
  implementation(libs.rdf4j.rio.nquads)
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "rdf-rdf4j"
      version = project.version.toString()
    }
  }
}
