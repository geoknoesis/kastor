plugins {
  id("org.jetbrains.kotlin.jvm")
  id("java-library")
}

dependencies {
  api(project(":ontomapper:runtime"))
  api(project(":rdf:rdf4j"))
  
  // RDF4J SHACL support
  implementation("org.eclipse.rdf4j:rdf4j-shacl:${libs.versions.rdf4j.get()}")
  
  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}
