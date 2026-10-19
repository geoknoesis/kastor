plugins {
  kotlin("jvm")
  `java-library`
  id("maven-publish")
}

dependencies {
  implementation(libs.slf4j.api)
  testImplementation(libs.junit.params)

  implementation(libs.jena.base)
  api(libs.jena.core)
  implementation(libs.jena.dboe.transaction)

  api(project(":rdf:core"))
  api(project(":rdf:sparql-contract"))

  api(libs.jena.arq)
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

