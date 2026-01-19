plugins {
  id("org.jetbrains.kotlin.jvm")
  id("java-library")
}

dependencies {
  api(project(":kastor-gen:runtime"))
  api(project(":rdf:jena"))
  
  // Jena SHACL support
  implementation("org.apache.jena:jena-shacl:${libs.versions.jena.get()}")
  
  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}
