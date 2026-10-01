plugins {
  application
  id("maven-publish")
}

application {
  mainClass.set("com.geoknoesis.kastor.ontoquality.cli.MainKt")
}

// The generated Windows script lists every jar on one `set CLASSPATH=` line: about 12,000 characters here, more than
// cmd.exe accepts (8191), so the script failed with "The input line is too long". A `lib\*` wildcard keeps it short.
tasks.named<CreateStartScripts>("startScripts") {
  doLast {
    val script = windowsScript
    script.writeText(script.readText().replace(Regex("set CLASSPATH=.*")) { "set CLASSPATH=%APP_HOME%\\lib\\*" })
  }
}

// The application's main class and start scripts, exposed to the tests as resources so CliLaunchTest starts the same
// class as the start script and checks the scripts themselves (a wrong mainClass or a script cmd.exe cannot run must
// fail the build, not the user's first run).
val generateCliLaunchInfo = tasks.register("generateCliLaunchInfo") {
  val mainClassName = application.mainClass
  val scriptName = application.applicationName
  val outputDir = layout.buildDirectory.dir("generated/cli-launch")
  inputs.property("mainClass", mainClassName)
  inputs.property("applicationName", scriptName)
  outputs.dir(outputDir)
  doLast {
    val file = outputDir.get().file("kastor-cli-launch.properties").asFile
    file.parentFile.mkdirs()
    file.writeText("mainClass=${mainClassName.get()}\napplicationName=$scriptName\n")
  }
}
sourceSets.named("test") {
  resources.srcDir(generateCliLaunchInfo)
  resources.srcDir(tasks.named("startScripts"))
}

// Dependencies of the application only (start scripts, distribution, `run`, tests). They are on runtimeClasspath but
// not in the published POM / Gradle module metadata, so a library consumer of onto-quality-cli does not inherit an
// SLF4J binding.
val applicationRuntimeOnly = configurations.dependencyScope("applicationRuntimeOnly")
configurations.named("runtimeClasspath") { extendsFrom(applicationRuntimeOnly.get()) }
configurations.named("testRuntimeClasspath") { extendsFrom(applicationRuntimeOnly.get()) }

dependencies {
  implementation(project(":tools:onto-quality"))
  implementation(project(":tools:onto-quality-metrics"))
  implementation(project(":tools:onto-quality-embed"))
  implementation(project(":tools:onto-quality-llm-koog"))
  implementation(project(":rdf:core"))
  implementation(project(":rdf:jena"))
  implementation(libs.clikt)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  // SLF4J binding for the application: library warnings (e.g. the model-cache self-heal notice) go to stderr at WARN.
  add(applicationRuntimeOnly.name, libs.slf4j.simple)
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))

      groupId = project.group.toString()
      artifactId = "onto-quality-cli"
      version = project.version.toString()
    }
  }
}
