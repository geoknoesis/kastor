import java.nio.file.Files
import java.util.Comparator

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.jmh) apply false
  alias(libs.plugins.dependency.analysis) apply false
  alias(libs.plugins.dokka) apply false
}

apply(plugin = "com.autonomousapps.dependency-analysis")

// The version is defined once, in gradle.properties (`version=`). Releases are tagged `vX.Y.Z`.
allprojects {
  group = "com.geoknoesis.kastor"
}

/** Non-published platforms: the consumer BOM and the build-internal constraints platform. */
val platformProjects = setOf(":bom", ":build-platform")

/**
 * Line-coverage floors enforced by `jacocoTestCoverageVerification` (wired into `check`).
 * Measured from a clean local run (2026-09-12) minus roughly ten points of headroom; ratchet
 * upwards as coverage improves. Modules without an entry are not gated.
 */
val coverageFloors = mapOf(
  ":rdf:core" to "0.55",
  ":rdf:jena" to "0.45",
  ":rdf:rdf4j" to "0.50",
  ":rdf:reasoning" to "0.65",
  ":rdf:reasoning-hermit" to "0.50",
  ":rdf:shacl-dsl" to "0.60",
  ":rdf:shacl-validation" to "0.50",
  ":rdf:sparql-lang" to "0.35",
  ":rdf:sparql" to "0.55",
  ":rdf:testkit" to "0.75",
  ":kastor-gen:processor" to "0.50",
  ":kastor-gen:runtime" to "0.35",
  ":kastor-gen:validation-jena" to "0.70",
  ":kastor-gen:validation-rdf4j" to "0.55",
  ":tools:onto-quality" to "0.75",
  ":tools:onto-quality-metrics" to "0.80",
  ":tools:onto-quality-embed" to "0.15",
)

subprojects {
  dependencyLocking { lockAllConfigurations() }
  // Tool classpaths (including Dokka) do not see :build-platform. Apply the same security
  // floors there. None of this is published.
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
  // Platform modules must not apply Kotlin/java-library.
  val isPlatform = project.path in platformProjects

  val skipDependencyAnalysis =
    isPlatform ||
      project.path.startsWith(":benchmarks:") ||
      project.path.startsWith(":examples:")

  if (!isPlatform) {
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

  if (!isPlatform) {
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

  // Repositories are declared only in settings.gradle.kts (FAIL_ON_PROJECT_REPOS).

  if (!isPlatform) {
    // Build-internal third-party platforms and security floors. Added at resolution time to
    // every resolvable configuration that sees a Kastor project (the same reach the former
    // BOM-borne floors had), and never to a declared/published scope, so consumers' POMs and
    // Gradle module metadata do not inherit them.
    val buildPlatformScope = configurations.dependencyScope("kastorBuildPlatform") {
      description = "Build-internal third-party platforms and security floors (never published)."
    }
    dependencies.add(buildPlatformScope.name, dependencies.platform(dependencies.project(mapOf("path" to ":build-platform"))))
    afterEvaluate {
      configurations.configureEach {
        if (isCanBeResolved && hierarchy.any { c -> c.dependencies.any { it is ProjectDependency } }) {
          extendsFrom(buildPlatformScope.get())
        }
      }
    }

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

    val testMaxHeap = providers.gradleProperty("kastor.test.maxHeap").orElse("1g")
    val testMaxForks = providers.gradleProperty("kastor.test.maxParallelForks").map(String::toInt).orElse(1)
    tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
      useJUnitPlatform()
      // Bound every test JVM. The JVM default (25% of physical RAM) multiplied by concurrent
      // forks and daemons exhausted memory on developer machines. Override per machine in
      // ~/.gradle/gradle.properties: kastor.test.maxHeap=2g, kastor.test.maxParallelForks=2.
      maxHeapSize = testMaxHeap.get()
      maxParallelForks = testMaxForks.get()
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
    coverageFloors[project.path]?.let { floor ->
      val verification = tasks.named<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        violationRules {
          rule {
            limit {
              counter = "LINE"
              value = "COVEREDRATIO"
              minimum = floor.toBigDecimal()
            }
          }
        }
      }
      tasks.named("check") { dependsOn(verification) }
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

  // Resolves every resolvable configuration so `--write-locks` refreshes complete lock state:
  //   ./gradlew resolveAndLockAll --write-locks
  tasks.register("resolveAndLockAll") {
    group = "help"
    description = "Resolves all resolvable configurations (use with --write-locks)."
    notCompatibleWithConfigurationCache("Resolves configurations at execution time")
    doLast {
      configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
    }
  }

  pluginManager.withPlugin("maven-publish") {
    if (!isPlatform) {
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
    tasks.withType<PublishToMavenRepository>().configureEach { mustRunAfter(":cleanReleaseRepository") }
    // Signing is optional for local staging, but mandatory for :centralBundle (enforced below).
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

// ---------------------------------------------------------------------------------------------
// Release publication
//
//   publishAllPublicationsToStagingRepository  -> build/release-repository (unsigned allowed)
//   centralBundle                              -> signed Maven Central Portal bundle zip
//
// The bundle is uploaded by .github/workflows/publish.yml (tag `vX.Y.Z`, protected `release`
// environment). Nothing in this build uploads anywhere.
// ---------------------------------------------------------------------------------------------
val releaseRepository = layout.buildDirectory.dir("release-repository")

val cleanReleaseRepository = tasks.register<Delete>("cleanReleaseRepository") {
  group = "publishing"
  description = "Deletes build/release-repository so bundles never contain stale versions."
  delete(releaseRepository)
}

val publishingProjects = provider { subprojects.filter { it.plugins.hasPlugin("maven-publish") } }

val centralBundle = tasks.register<Zip>("centralBundle") {
  group = "publishing"
  description = "Builds a signed Maven Central Portal bundle from the staging repository (release versions only)."
  dependsOn(cleanReleaseRepository)
  dependsOn(publishingProjects.map { projects -> projects.map { "${it.path}:publishAllPublicationsToStagingRepository" } })
  from(releaseRepository) { exclude("**/maven-metadata*") }
  archiveFileName.set(provider { "kastor-${project.version}-central-bundle.zip" })
  destinationDirectory.set(layout.buildDirectory.dir("central"))
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
}

gradle.taskGraph.whenReady {
  if (hasTask(centralBundle.get())) {
    val releaseVersion = project.version.toString()
    check(!releaseVersion.endsWith("-SNAPSHOT") && releaseVersion != "unspecified") {
      "centralBundle publishes release versions only (current version: $releaseVersion). " +
        "Set version=X.Y.Z in gradle.properties in the release commit and tag it vX.Y.Z."
    }
    check(providers.environmentVariable("KASTOR_SIGNING_KEY").isPresent) {
      "Release publication must be signed: set KASTOR_SIGNING_KEY (ASCII-armored private key) " +
        "and KASTOR_SIGNING_PASSWORD. Unsigned artifacts are only allowed in the local staging repository."
    }
  }
}

// Collect only current task outputs, retaining module paths to avoid filename collisions.
val collectArtifacts = tasks.register<org.gradle.api.tasks.Sync>("collectArtifacts") {
  duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.FAIL
  subprojects.forEach { p ->
    dependsOn("${p.path}:assemble")
    if (p.path !in platformProjects) {
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
    "Runs :rdf:conformance RDF 1.2 harness smoke tests (bundled fixture; no W3C corpus download)."
  dependsOn(":rdf:conformance:conformanceSmokeTest")
}
