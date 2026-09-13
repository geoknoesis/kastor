import java.nio.file.Files
import java.util.Comparator

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.jmh) apply false
  alias(libs.plugins.dependency.analysis) apply false
  id("org.jetbrains.dokka") version "2.2.0" apply false
}

apply(plugin = "com.autonomousapps.dependency-analysis")

allprojects {
  group = "com.geoknoesis.kastor"
  version = "0.2.1"
}

subprojects {
  dependencyLocking { lockAllConfigurations() }
  // Tool classpaths (including Dokka) do not inherit the public BOM. Apply the
  // same security floors there; the BOM carries them to published consumers.
  configurations.configureEach {
    resolutionStrategy.eachDependency {
      when {
        requested.group.startsWith("com.fasterxml.jackson") -> useVersion(
          if (requested.name == "jackson-annotations") rootProject.libs.versions.jacksonAnnotations.get()
          else rootProject.libs.versions.jackson.get()
        )
        requested.group == "org.jsoup" && requested.name == "jsoup" -> useVersion(rootProject.libs.versions.jsoup.get())
        requested.group == "org.apache.commons" && requested.name == "commons-lang3" -> useVersion(rootProject.libs.versions.commonsLang3.get())
      }
    }
  }
  // The `:bom` module is a Gradle platform; it must not apply Kotlin/java-library.
  val isBom = project.path == ":bom"

  val skipDependencyAnalysis =
    isBom ||
      project.path.startsWith(":benchmarks:") ||
      project.path.startsWith(":examples:")

  if (!isBom) {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java-library")
    if (!skipDependencyAnalysis) {
      apply(plugin = "com.autonomousapps.dependency-analysis")
    }
  }

  // Apply KSP plugin to projects that need it (but not the processor itself or runtime).
  if ((project.path.startsWith(":kastor-gen:") && project.path != ":kastor-gen:processor" && project.path != ":kastor-gen:runtime" && project.path != ":kastor-gen:gradle-plugin") ||
      project.path.startsWith(":examples:")) {
    apply(plugin = "com.google.devtools.ksp")
  }

  if (!isBom) {
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
      jvmToolchain(21)
    }
  }

  // Place each module's build under root build/modules/<path>
  layout.buildDirectory.set(
    rootProject.layout.buildDirectory.dir(
      "modules/" + project.path.removePrefix(":").replace(":", "/")
    )
  )

  repositories {
    mavenCentral()
  }

  if (!isBom) {
    dependencies {
      add("api", platform(project(":bom")))
      add("testImplementation", rootProject.libs.kotlin.test)
      add("testImplementation", rootProject.libs.junit.jupiter)
      add("testRuntimeOnly", rootProject.libs.junit.platform.launcher)
      add("implementation", rootProject.libs.slf4j.api)

      // Add KSP dependencies for projects that use it (but not the processor itself or runtime).
      // Exclude simple hello-world and hello-codegen examples (they can enable KSP manually if needed).
      if ((project.path.startsWith(":kastor-gen:") && project.path != ":kastor-gen:processor" && project.path != ":kastor-gen:runtime" && project.path != ":kastor-gen:gradle-plugin") ||
          (project.path.startsWith(":examples:") && project.path != ":examples:hello-world" && project.path != ":examples:hello-codegen")) {
        add("ksp", project(":kastor-gen:processor"))
      }
    }

    tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
      useJUnitPlatform()
      // Persistent-store tests can retain mapped files until the worker JVM exits.
      // Keep scratch files on the build volume and clean after successful worker exit.
      val scratch = layout.buildDirectory.dir("test-tmp/$name").get().asFile
      val buildRoot = rootProject.layout.buildDirectory.get().asFile
      val clearScratch = {
        val path = scratch.canonicalFile.toPath()
        val allowed = buildRoot.canonicalFile.toPath()
        check(path.startsWith(allowed) && path != allowed) { "Unsafe test scratch directory: $path" }
        if (Files.exists(path)) {
          // No FOLLOW_LINKS: links inside a fixture are deleted, never traversed.
          Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
          }
        }
      }
      systemProperty("java.io.tmpdir", scratch.absolutePath)
      doFirst { clearScratch(); check(scratch.mkdirs() || scratch.isDirectory) }
      doLast { clearScratch() }
      // Opt-in selection is part of test identity; changing these flags must not
      // reuse a report in which the requested native/remote tests were skipped.
      listOf("KASTOR_SKIP_EMBEDDING_TESTS", "KASTOR_SKIP_OPENAI_LLM_TESTS",
        "KASTOR_RUN_EMBEDDING_TESTS", "KASTOR_RUN_SOAK_TESTS", "KASTOR_SOAK_CYCLES",
        "KASTOR_OOPS_BENCHMARK").forEach { name ->
        inputs.property(name, providers.environmentVariable(name).orElse(""))
      }
    }
    apply(plugin = "jacoco")
    tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>().configureEach {
      reports { xml.required.set(true); html.required.set(true) }
    }
    tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
      manifest.attributes["Implementation-Version"] = project.version.toString()
      isPreserveFileTimestamps = false
      isReproducibleFileOrder = true
    }

    // Sources JAR
    tasks.register<org.gradle.jvm.tasks.Jar>("sourcesJar") {
      archiveClassifier.set("sources")
      val sourceSets = project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
      from(sourceSets.getByName("main").allSource)
    }

    // Javadoc JAR (may be empty for pure Kotlin projects)
    tasks.register<org.gradle.jvm.tasks.Jar>("javadocJar") {
      archiveClassifier.set("javadoc")
      val javadoc = tasks.findByName("javadoc") as? org.gradle.api.tasks.javadoc.Javadoc
      if (javadoc != null) {
        dependsOn(javadoc)
        from(javadoc.destinationDir)
      }
    }
  }
  pluginManager.withPlugin("maven-publish") {
    if (!isBom) {
      apply(plugin = "org.jetbrains.dokka")
      extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
        abiValidation { }
      }
      tasks.named<org.gradle.jvm.tasks.Jar>("javadocJar") {
        dependsOn("dokkaGeneratePublicationHtml")
        from(layout.buildDirectory.dir("dokka/html"))
      }
    }
    extensions.configure<PublishingExtension> {
      repositories { maven { name = "staging"; url = rootProject.layout.buildDirectory.dir("release-repository").get().asFile.toURI() } }
      publications.withType<MavenPublication>().configureEach {
        if (project.path == ":kastor-gen:gradle-plugin" && name == "pluginMaven") {
          artifact(tasks.named("sourcesJar")); artifact(tasks.named("javadocJar"))
        }
        pom {
          name.convention(artifactId)
          description.convention("Kastor Kotlin RDF and ontology tools")
          url.set("https://github.com/geoknoesis/kastor")
          licenses { license { name.set("Apache License, Version 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0.txt") } }
          scm { url.set("https://github.com/geoknoesis/kastor"); connection.set("scm:git:https://github.com/geoknoesis/kastor.git") }
          developers { developer { id.set("geoknoesis"); name.set("Geoknoesis") } }
        }
      }
    }
    val signingKey = providers.environmentVariable("KASTOR_SIGNING_KEY")
    if (signingKey.isPresent) {
      apply(plugin = "signing")
      extensions.configure<SigningExtension> {
        useInMemoryPgpKeys(signingKey.get(), providers.environmentVariable("KASTOR_SIGNING_PASSWORD").orNull)
        sign(extensions.getByType<PublishingExtension>().publications)
      }
    }
  }
}

// Collect only current task outputs, retaining module paths to avoid filename collisions.
val collectArtifacts = tasks.register<org.gradle.api.tasks.Sync>("collectArtifacts") {
  duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.FAIL
  subprojects.forEach { p ->
    dependsOn("${p.path}:assemble")
    if (p.path != ":bom") {
      listOf("jar", "sourcesJar", "javadocJar").forEach { taskName ->
        from(p.tasks.named<org.gradle.jvm.tasks.Jar>(taskName).flatMap { it.archiveFile }) {
          into(p.path.removePrefix(":").replace(":", "/"))
        }
      }
    }
  }
  into(layout.buildDirectory.dir("artifacts"))
}

// Root aggregate build task
tasks.register("build") {
  dependsOn(collectArtifacts)
  dependsOn(subprojects.map { "${it.path}:check" })
  dependsOn("conformanceSmokeTest")
}

// Hello World example task
tasks.register("helloWorld") {
  group = "examples"
  description = "Run the hello-world example"
  dependsOn(":examples:hello-world:run")
}

// Hello Codegen example task
tasks.register("helloCodegen") {
  group = "examples"
  description = "Run the hello-codegen example"
  dependsOn(":examples:hello-codegen:run")
}

tasks.register("conformanceSmokeTest") {
  group = "verification"
  description =
    "Runs :rdf:conformance RDF 1.2 harness smoke tests (bundled fixture; no W3C submodule)."
  dependsOn(":rdf:conformance:conformanceSmokeTest")
}


