plugins {
  id("org.jetbrains.kotlin.jvm")
  id("java-library")
  id("maven-publish")
}

dependencies {
  testImplementation(project(":kastor-gen:validation-rdf4j"))
  testImplementation(project(":rdf:shacl-validation"))
  implementation(libs.jena.arq)
  implementation(libs.jena.core)
  api(project(":rdf:core"))
  testImplementation(project(":rdf:sparql-contract"))

  api(project(":kastor-gen:runtime"))
  implementation(project(":rdf:jena"))
  
  // Jena SHACL support
  api(libs.jena.shacl)
  
  testImplementation(libs.kotlin.test)

  testRuntimeOnly(libs.junit.platform.launcher)
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifactId = "kastor-gen-validation-jena"
      artifact(tasks["sourcesJar"])
      artifact(tasks["javadocJar"])
    }
  }
}
