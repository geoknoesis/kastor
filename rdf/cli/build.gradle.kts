plugins {
  application
  id("maven-publish")
}

application {
  mainClass.set("com.geoknoesis.kastor.rdf.cli.KastorRdfCli")
}

// The application's main class, exposed to the tests as a resource so CliLaunchTest starts the same class as the
// start script (a wrong mainClass must fail the build, not the user's first run).
val generateCliLaunchInfo = tasks.register("generateCliLaunchInfo") {
  val mainClassName = application.mainClass
  val outputDir = layout.buildDirectory.dir("generated/cli-launch")
  inputs.property("mainClass", mainClassName)
  outputs.dir(outputDir)
  doLast {
    val file = outputDir.get().file("kastor-cli-launch.properties").asFile
    file.parentFile.mkdirs()
    file.writeText("mainClass=${mainClassName.get()}\n")
  }
}
sourceSets.named("test") { resources.srcDir(generateCliLaunchInfo) }

// Dependencies of the application only (start scripts, distribution, `run`, tests). They are on runtimeClasspath but
// not in the published POM / Gradle module metadata, so a library consumer of rdf-cli does not inherit an SLF4J binding.
val applicationRuntimeOnly = configurations.dependencyScope("applicationRuntimeOnly")
configurations.named("runtimeClasspath") { extendsFrom(applicationRuntimeOnly.get()) }
configurations.named("testRuntimeClasspath") { extendsFrom(applicationRuntimeOnly.get()) }

dependencies {
  implementation(project(":rdf:core"))
  // Parsing and serialization. `diff` uses rdf-core's graph isomorphism, so the test kit is not an application dependency.
  implementation(project(":rdf:jena"))
  // SLF4J binding for the application: Jena RIOT warnings go to stderr at WARN (see simplelogger.properties).
  add(applicationRuntimeOnly.name, libs.slf4j.simple)
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
