plugins {
  id("org.jetbrains.kotlin.jvm")
  id("application")
}

dependencies {
  implementation(project(":kastor-gen:runtime"))
  implementation(project(":kastor-gen:processor"))
  
  // Choose backend (Jena or RDF4J)
  implementation(project(":rdf:jena"))
  implementation(project(":kastor-gen:validation-jena"))
  
  // Alternative: RDF4J backend
  // implementation(project(":rdf:rdf4j"))
  // implementation(project(":kastor-gen:validation-rdf4j"))
  
  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

// KSP configuration will be applied by root build.gradle.kts

application {
  mainClass.set("com.example.dcatus.DcatUsDemoKt")
}
