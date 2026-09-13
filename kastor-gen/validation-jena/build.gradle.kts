plugins {
  id("org.jetbrains.kotlin.jvm")
  id("java-library")
  id("maven-publish")
}

dependencies {
  api(project(":kastor-gen:runtime"))
  api(project(":rdf:jena"))
  
  // Jena SHACL support
  implementation(libs.jena.shacl)
  
  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit.jupiter)
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
