plugins {
  application
  id("maven-publish")
}

application {
  mainClass.set("com.geoknoesis.kastor.rdf.cli.KastorRdfCliKt")
}

dependencies {
  implementation(project(":rdf:core"))
  implementation(project(":rdf:jena"))
  // Runtime use: diff relies on RdfGraphIsomorphism / RdfDatasetIsomorphism / RdfGraphSnapshots from the testkit.
  implementation(project(":rdf:testkit"))
  // SLF4J binding for the application: Jena RIOT warnings go to stderr at WARN (see simplelogger.properties).
  runtimeOnly(libs.slf4j.simple)
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "rdf-cli"
      version = project.version.toString()
    }
  }
}
