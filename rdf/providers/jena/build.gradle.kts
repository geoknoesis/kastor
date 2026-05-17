plugins {
  kotlin("jvm")
  `java-library`
  id("maven-publish")
}

dependencies {
  api(project(":rdf:core"))
  implementation(libs.jena.libs) { isTransitive = true }
  implementation(libs.jena.arq)
  implementation(libs.jena.tdb2)
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

