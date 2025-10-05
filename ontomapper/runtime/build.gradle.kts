plugins {
  id("org.jetbrains.kotlin.jvm")
  id("java-library")
}

dependencies {
  api(project(":rdf:core"))
  implementation(libs.slf4j.api)
  
  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}
