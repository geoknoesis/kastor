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
