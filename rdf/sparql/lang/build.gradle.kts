plugins {
  id("maven-publish")
}

dependencies {
  api(project(":rdf:core"))
  implementation(libs.kotlinx.coroutines.core)
  testRuntimeOnly(project(":rdf:jena"))
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "sparql-lang"
      version = project.version.toString()
    }
  }
}
