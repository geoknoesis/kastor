plugins {
  id("org.jetbrains.kotlin.jvm")
  id("java-library")
  id("maven-publish")
}

dependencies {
  api(project(":rdf:core"))
  implementation(libs.slf4j.api)
  
  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      
      groupId = project.group.toString()
      artifactId = project.name
      version = project.version.toString()
    }
  }
}
