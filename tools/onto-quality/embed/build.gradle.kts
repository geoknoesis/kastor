plugins {
  kotlin("jvm")
  `java-library`
  id("maven-publish")
}

tasks.test {
  systemProperty("kastor.soak.report", layout.buildDirectory.file("reports/native-soak.csv").get().asFile.absolutePath)
}

dependencies {
  implementation(libs.slf4j.api)

  api(project(":rdf:core"))
  runtimeOnly(project(":rdf:shacl-validation"))
  implementation(project(":rdf:jena"))
  implementation(libs.onnxruntime)
  implementation(libs.djl.huggingface.tokenizers)

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
