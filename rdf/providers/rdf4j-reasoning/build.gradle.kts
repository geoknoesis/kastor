plugins {
  kotlin("jvm")
  `java-library`
  id("maven-publish")
}

dependencies {
  api(project(":rdf:rdf4j"))
  implementation(project(":rdf:reasoning"))
  implementation(libs.rdf4j.runtime)
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = project.name
      version = project.version.toString()
    }
  }
}
