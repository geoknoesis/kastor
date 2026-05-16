plugins {
  kotlin("jvm")
  `java-library`
  id("maven-publish")
}

dependencies {
  api(project(":rdf:core"))
  api(project(":rdf:shacl-validation"))
  implementation(project(":rdf:jena"))
  implementation(libs.onnxruntime)
  implementation(libs.djl.huggingface.tokenizers)
  testImplementation(project(":rdf:testkit"))
  testImplementation(libs.junit.jupiter)
  testImplementation("org.junit.jupiter:junit-jupiter-params:${libs.versions.junit.get()}")
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "onto-quality-embed"
      version = project.version.toString()
    }
  }
}
