plugins {
  kotlin("jvm")
  `java-library`
  id("maven-publish")
}

dependencies {
  api(project(":rdf:core"))
  testImplementation(project(":rdf:jena"))
  // Tests only: the reference parser the streaming result decoder (JsonBindingRows) is compared with.
  // The adapter itself has no JSON library, so consumers of rdf-sparql do not get one from it.
  testImplementation(libs.kotlinx.serialization.json)
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "rdf-sparql"
      version = project.version.toString()
    }
  }
}
