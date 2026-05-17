plugins {
  id("maven-publish")
}

dependencies {
  // Marker types only; no runtime deps beyond the Kotlin stdlib from the JVM plugin.
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "rdf-sparql-contract"
      version = project.version.toString()
    }
  }
}
