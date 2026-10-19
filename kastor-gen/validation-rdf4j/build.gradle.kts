plugins {
  id("org.jetbrains.kotlin.jvm")
  id("java-library")
  id("maven-publish")
}

dependencies {
  implementation(libs.rdf4j.model.api)
  implementation(libs.rdf4j.model.vocabulary)
  implementation(libs.rdf4j.model)
  implementation(libs.rdf4j.repository.api)
  implementation(libs.rdf4j.repository.sail)
  implementation(libs.rdf4j.sail.api)
  implementation(libs.rdf4j.sail.memory)
  api(project(":rdf:core"))
  testImplementation(project(":rdf:rdf4j"))

  api(project(":kastor-gen:runtime"))
  runtimeOnly(project(":rdf:rdf4j"))
  
  // RDF4J SHACL support
  implementation("org.eclipse.rdf4j:rdf4j-shacl:${libs.versions.rdf4j.get()}")
  
  testImplementation(libs.kotlin.test)

  testRuntimeOnly(libs.junit.platform.launcher)
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifactId = "kastor-gen-validation-rdf4j"
      artifact(tasks["sourcesJar"])
      artifact(tasks["javadocJar"])
    }
  }
}
