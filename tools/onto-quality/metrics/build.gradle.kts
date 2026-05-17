plugins {
  kotlin("jvm")
  `java-library`
  id("maven-publish")
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":rdf:core"))
  api(project(":tools:onto-quality"))
  implementation(libs.kotlinx.serialization.json)
  testImplementation(project(":rdf:jena"))
  testImplementation(libs.jena.arq)
  testImplementation(project(":rdf:testkit"))
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
