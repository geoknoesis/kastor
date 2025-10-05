plugins {
  id("org.jetbrains.kotlin.jvm")
  id("java-library")
  id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
  api(project(":ontomapper:runtime"))
  
  // KSP dependencies
  implementation("com.google.devtools.ksp:symbol-processing-api:${libs.versions.ksp.get()}")
  
  // JSON serialization for JSON-LD context parsing
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
  
  // Optional: Jena for compile-time schema parsing
  implementation(libs.jena.arq)
  
  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

// KSP configuration will be applied by root build.gradle.kts
