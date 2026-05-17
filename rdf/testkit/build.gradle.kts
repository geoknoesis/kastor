plugins {
  id("maven-publish")
}

dependencies {
  api(project(":rdf:core"))
  implementation(project(":rdf:jena"))
  implementation(libs.jena.arq)
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "rdf-testkit"
      version = project.version.toString()
    }
  }
}
