plugins {
  application
  id("maven-publish")
}

application {
  mainClass.set("com.geoknoesis.kastor.ontoquality.cli.MainKt")
}

dependencies {
  implementation(project(":tools:onto-quality"))
  implementation(project(":tools:onto-quality-metrics"))
  implementation(project(":tools:onto-quality-embed"))
  implementation(project(":tools:onto-quality-llm-koog"))
  implementation(project(":rdf:core"))
  implementation(project(":rdf:jena"))
  implementation(libs.clikt)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  // SLF4J binding for the application: library warnings (e.g. the model-cache self-heal notice) go to stderr at WARN.
  runtimeOnly(libs.slf4j.simple)
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "onto-quality-cli"
      version = project.version.toString()
    }
  }
}
