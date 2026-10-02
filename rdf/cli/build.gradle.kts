plugins {
  application
  id("maven-publish")
}

application {
  // The name used by the help text and the documentation: the distribution has bin/kastor-rdf and bin/kastor-rdf.bat.
  applicationName = "kastor-rdf"
  mainClass.set("com.geoknoesis.kastor.rdf.cli.KastorRdfCli")
}

// Resources of the application only (the configuration of its SLF4J binding). They are not in src/main/resources,
// so the published rdf-cli jar has no simplelogger.properties that could shadow a consumer's own; the distribution
// carries them in the launcher jar, `run` and the tests get the directory on their class path.
val applicationResources = layout.projectDirectory.dir("src/application/resources")

// The class path of the distribution as one jar: its manifest lists the jars of lib/ in class-path order. Both start
// scripts put only this jar on the class path, so Windows and Unix load the same jars in the same order (a `lib\*`
// wildcard loads them in directory order), and the Windows script stays far below cmd.exe's 8191-character line limit.
val launcherJar = tasks.register<Jar>("launcherJar") {
  description = "Jar whose manifest Class-Path lists the application's jars in order; holds the application-only resources."
  archiveBaseName.set(application.applicationName)
  archiveClassifier.set("launcher")
  from(applicationResources)
  val mainJar = tasks.named<Jar>("jar").flatMap { it.archiveFileName }
  val classPath = mainJar.zip(configurations.named("runtimeClasspath").flatMap { it.elements }) { main, files ->
    (listOf(main) + files.map { it.asFile.name }.filter { it.endsWith(".jar") }).distinct().joinToString(" ")
  }
  inputs.property("classPath", classPath)
  doFirst { manifest.attributes["Class-Path"] = classPath.get() }
}
tasks.named<CreateStartScripts>("startScripts") {
  classpath = files(launcherJar)
}
distributions.named("main") {
  contents { into("lib") { from(launcherJar) } }
}
tasks.named<JavaExec>("run") { classpath(applicationResources) }

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
  resources.srcDir(applicationResources)
}

// CliDistributionTest runs the start scripts of the installDist layout (bin/, lib/), as a user does.
tasks.named<Test>("test") {
  val installation = tasks.named<Sync>("installDist")
  val installDir = installation.map { it.destinationDir.absolutePath }
  val libraryJar = tasks.named<Jar>("jar").flatMap { it.archiveFileName }
  inputs.files(installation).withPropertyName("installedApplication").withPathSensitivity(PathSensitivity.RELATIVE)
  jvmArgumentProviders.add(
    CommandLineArgumentProvider { listOf("-Dkastor.cli.installDir=${installDir.get()}", "-Dkastor.cli.libraryJar=${libraryJar.get()}") },
  )
}

// Dependencies of the application only (start scripts, distribution, `run`, tests). They are on runtimeClasspath but
// not in the published POM / Gradle module metadata, so a library consumer of rdf-cli does not inherit an SLF4J binding.
val applicationRuntimeOnly = configurations.dependencyScope("applicationRuntimeOnly")
configurations.named("runtimeClasspath") { extendsFrom(applicationRuntimeOnly.get()) }
configurations.named("testRuntimeClasspath") { extendsFrom(applicationRuntimeOnly.get()) }

dependencies {
  implementation(project(":rdf:core"))
  // Parsing and serialization. `diff` uses rdf-core's graph isomorphism, so the test kit is not an application dependency.
  implementation(project(":rdf:jena"))
  // SLF4J binding for the application: Jena RIOT warnings go to stderr at WARN (see src/application/resources).
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

// What consumers of the published library get: the generated POM, and the dependencies of the variants that Gradle
// module metadata is written from (apiElements, runtimeElements). Neither may name an SLF4J binding or the test kit.
val verifyPublishedDependencies = tasks.register("verifyPublishedDependencies") {
  group = "verification"
  description = "Fails when the published POM or module metadata variants depend on an SLF4J binding or the test kit."
  dependsOn("generatePomFileForMavenPublication")
  val pom = layout.buildDirectory.file("publications/maven/pom-default.xml")
  val variants = provider {
    listOf("apiElements", "runtimeElements").flatMap { name ->
      configurations.getByName(name).allDependencies.map { "$name: ${it.group}:${it.name}" }
    }
  }
  val projectPath = project.path
  val forbidden = listOf("slf4j-simple", "slf4j-nop", "logback", "log4j-slf4j", "testkit")
  inputs.file(pom)
  inputs.property("variants", variants)
  val marker = layout.buildDirectory.file("verification/published-dependencies.txt")
  outputs.file(marker)
  doLast {
    val pomLines = pom.get().asFile.readLines().filter { "<artifactId>" in it }.map { "pom: ${it.trim()}" }
    val found = (variants.get() + pomLines).filter { line -> forbidden.any { it in line } }
    check(found.isEmpty()) { "Published metadata of $projectPath must not depend on an SLF4J binding or the test kit: $found" }
    check(variants.get().isNotEmpty() && pomLines.size > 1) { "No published dependencies found for $projectPath: the check looked at the wrong place" }
    marker.get().asFile.writeText((variants.get() + pomLines).joinToString("\n"))
  }
}
tasks.named("check") { dependsOn(verifyPublishedDependencies) }
