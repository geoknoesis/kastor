plugins {
  kotlin("jvm")
  `java-library`
  id("maven-publish")
}

// Upstream LZ4 relocated; exclude the old capability to avoid a duplicate provider.
configurations.configureEach { exclude(group = "org.lz4", module = "lz4-java") }

dependencies {
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.slf4j.api)
  testImplementation(libs.junit.params)

  implementation(libs.rdf4j.common.exception)
  // KastorEvaluationStrategy (per-query custom functions) builds on the evaluation strategy API and its iterations.
  implementation(libs.rdf4j.common.iterator)
  implementation(libs.rdf4j.common.order)
  implementation(libs.rdf4j.common.transaction)
  api(libs.rdf4j.model.api)
  implementation(libs.rdf4j.model.vocabulary)
  implementation(libs.rdf4j.model)
  implementation(libs.rdf4j.query)
  implementation(libs.rdf4j.queryalgebra.evaluation)
  implementation(libs.rdf4j.queryalgebra.model)
  implementation(libs.rdf4j.queryparser.api)
  implementation(libs.rdf4j.queryparser.sparql)
  implementation(libs.rdf4j.rio.api)
  implementation(libs.rdf4j.sail.api)

  runtimeOnly(libs.lz4.java)
  api(project(":rdf:core"))
  api(project(":rdf:sparql-contract"))
  api(project(":rdf:shacl-validation"))
  api(libs.rdf4j.repository.api)
  implementation(libs.rdf4j.repository.sail)
  implementation(libs.rdf4j.sail.memory)
  implementation(libs.rdf4j.sail.nativerdf)
  implementation(libs.rdf4j.sail.inferencer)
  implementation(libs.rdf4j.shacl)

  implementation(libs.rdf4j.rio.jsonld)
  implementation(libs.hasmac.jsonld)
  runtimeOnly(libs.rdf4j.rio.turtle)
  runtimeOnly(libs.rdf4j.rio.rdfxml)
  runtimeOnly(libs.rdf4j.rio.n3)
  runtimeOnly(libs.rdf4j.rio.trig)
  runtimeOnly(libs.rdf4j.rio.nquads)
  // Initial-binding parity tests compare rows with the Jena provider.
  testImplementation(project(":rdf:jena"))
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
