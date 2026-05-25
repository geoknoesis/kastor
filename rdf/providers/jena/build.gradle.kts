plugins {
  kotlin("jvm")
  `java-library`
  id("maven-publish")
}

dependencies {
  api(project(":rdf:core"))
  api(libs.jena.libs) { isTransitive = true }
  api(libs.jena.arq)
  api(libs.jena.tdb2)
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "rdf-jena"
      version = project.version.toString()
    }
  }
}

