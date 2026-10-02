plugins {
  kotlin("jvm")
  `java-library`
  id("maven-publish")
}

dependencies {
    testImplementation(project(":rdf:jena"))
  api(project(":rdf:core"))
  // No longer used by the adapter itself (results are decoded by JsonBindingRows); tests use it as the
  // reference parser. Kept as declared so that the dependency lock state of dependent modules is unchanged.
  implementation(libs.kotlinx.serialization.json)
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
