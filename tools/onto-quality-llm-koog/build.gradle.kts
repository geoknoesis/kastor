plugins {
  kotlin("jvm")
  `java-library`
  id("maven-publish")
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":tools:onto-quality"))
  implementation(libs.koog.agents)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "onto-quality-llm-koog"
      version = project.version.toString()
    }
  }
}

tasks.test {
  useJUnitPlatform()
}
