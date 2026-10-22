plugins {
  id("maven-publish")
}

dependencies {
  api(project(":rdf:core"))
  api(project(":rdf:shacl-validation"))
  implementation(project(":rdf:sparql-contract"))
  testImplementation(libs.kotlinx.serialization.json)
  testRuntimeOnly(project(":rdf:jena"))
}

tasks.withType<Jar>().configureEach {
  from("NOTICE") { into("META-INF") }
}

// External RDF-H suite adapter. Kept on the test classpath, outside the published library.
tasks.register("conformanceConfig") {
  group = "verification"
  description = "Writes an RDF-H JSON adapter configuration for the external RDF-H conformance suite."
  dependsOn(tasks.named("testClasses"))
  val java = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
  val testRuntime = sourceSets["test"].runtimeClasspath
  val configFile = layout.buildDirectory.file("conformance/adapter-config.json")
  outputs.file(configFile)
  inputs.files(testRuntime)
  doLast {
    fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val command = listOf(java.get().executablePath.asFile.absolutePath, "-cp", testRuntime.asPath,
      "com.geoknoesis.kastor.rdf.holon.RdfHConformanceAdapter")
    configFile.get().asFile.apply {
      parentFile.mkdirs()
      writeText("{\"rdf-h\": [${command.joinToString(", ", transform = ::quote)}]}\n")
    }
  }
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))
      artifactId = "rdf-holon"
      pom.description.set("Provider-neutral RDF-H 0.8 registry, scopes, conversion, and scoped SHACL validation.")
    }
  }
}
