plugins {
  id("maven-publish")
}

dependencies {
  api(project(":rdf:core"))
  api(project(":rdf:sparql-lang"))
  testRuntimeOnly(project(":rdf:jena"))
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "rdf-shacl-dsl"
      version = project.version.toString()
    }
  }
}
