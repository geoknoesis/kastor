plugins {
  id("maven-publish")
}

dependencies {
  // The shared internals of the providers (marker types, sandbox limits) need the Kotlin stdlib only, except the
  // regular expressions of sandboxed queries and SHACL shapes, which run on RE2/J (linear time, BSD-3-Clause).
  implementation(libs.re2j)
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
