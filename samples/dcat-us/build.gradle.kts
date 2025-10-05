plugins {
  id("org.jetbrains.kotlin.jvm")
  id("application")
}

dependencies {
  implementation(project(":ontomapper:runtime"))
  implementation(project(":ontomapper:processor"))
  
  // Choose backend (Jena or RDF4J)
  runtimeOnly(project(":rdf:jena"))
  runtimeOnly(project(":ontomapper:validation-jena"))
  
  // Alternative: RDF4J backend
  // runtimeOnly(project(":rdf:rdf4j"))
  // runtimeOnly(project(":ontomapper:validation-rdf4j"))
  
  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

// KSP configuration will be applied by root build.gradle.kts

application {
  mainClass.set("com.example.dcatus.DcatUsDemoKt")
}
