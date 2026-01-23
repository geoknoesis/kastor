plugins {
  id("org.jetbrains.kotlin.jvm")
  id("java-library")
  id("org.jetbrains.kotlin.plugin.serialization")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xexplicit-api=warning")
    }
}

dependencies {
  api(project(":kastor-gen:runtime"))
  
  // KSP dependencies
  implementation("com.google.devtools.ksp:symbol-processing-api:${libs.versions.ksp.get()}")
  
  // KotlinPoet for type-safe code generation
  implementation("com.squareup:kotlinpoet:2.2.0")
  implementation("com.squareup:kotlinpoet-ksp:2.2.0")
  
  // JSON serialization for JSON-LD context parsing
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
  
  // Optional: Jena for compile-time schema parsing
  implementation(libs.jena.arq)
  
  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

// KSP configuration will be applied by root build.gradle.kts
