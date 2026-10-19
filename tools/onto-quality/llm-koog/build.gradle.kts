plugins {
  kotlin("jvm")
  `java-library`
  id("maven-publish")
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  implementation(libs.agents.tools)
  implementation(libs.prompt.executor.anthropic.client)
  implementation(libs.prompt.executor.clients)
  implementation(libs.prompt.executor.model)
  implementation(libs.prompt.executor.ollama.client)
  implementation(libs.prompt.executor.openai.client.base)
  implementation(libs.prompt.executor.openai.client)
  implementation(libs.prompt.llm)
  implementation(libs.prompt.model)
  runtimeOnly(libs.ktor.client.apache5)
  implementation(libs.ktor.client.core)
  runtimeOnly(libs.ktor.serialization.kotlinx.json)
  implementation(libs.kotlinx.serialization.core)
  implementation(project(":rdf:core"))
  implementation(project(":rdf:shacl-validation"))

  api(project(":tools:onto-quality"))

  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)

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
