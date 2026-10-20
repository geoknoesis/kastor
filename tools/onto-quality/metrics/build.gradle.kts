plugins {
  kotlin("jvm")
  `java-library`
  id("maven-publish")
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  testImplementation(libs.jena.core)
  implementation(libs.kotlinx.serialization.core)

  api(project(":rdf:core"))
  api(project(":tools:onto-quality"))
  implementation(libs.kotlinx.serialization.json)
  testRuntimeOnly(project(":rdf:jena"))
  testRuntimeOnly(libs.jena.arq)
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "onto-quality-metrics"
      version = project.version.toString()
    }
  }
}
