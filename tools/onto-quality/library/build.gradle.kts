plugins {
  kotlin("jvm")
  `java-library`
  id("maven-publish")
}

dependencies {
  api(project(":rdf:core"))
  api(project(":rdf:shacl-validation"))
  api(libs.kotlinx.coroutines.core)
  implementation(libs.slf4j.api)
  implementation(project(":rdf:reasoning"))
  implementation(project(":rdf:reasoning-hermit"))
  implementation(project(":rdf:jena"))
  implementation(project(":rdf:jena-reasoning"))
  implementation(libs.jena.arq)
  testImplementation(project(":rdf:testkit"))
  testImplementation(project(":tools:onto-quality-embed"))
  testImplementation(project(":tools:onto-quality-metrics"))
  testImplementation("org.junit.jupiter:junit-jupiter-params:${libs.versions.junit.get()}")
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "onto-quality"
      version = project.version.toString()
    }
  }
}
