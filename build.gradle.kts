import java.nio.file.Files
import java.util.Comparator

buildscript {
  dependencies {
    // dependency-analysis 3.12.0 brings kotlin-metadata-jvm 2.2.x, which cannot read Kotlin 2.4 class
    // metadata ("maximum supported version is 2.3.0"), so buildHealth fails. Keep this in step with the
    // `kotlin` version in gradle/libs.versions.toml (version catalogs are not available in buildscript {});
    // scripts/check-build-pins.py fails CI when they differ. Drop this block once dependency-analysis ships
    // Kotlin 2.4 metadata support (docs/reference/dependency-upgrade-plan.md, "Temporary pins").
    classpath("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.20")
  }
}

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.jmh) apply false
  alias(libs.plugins.dependency.analysis) apply false
  alias(libs.plugins.dokka) apply false
  alias(libs.plugins.cyclonedx) apply false
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
 * Measured from a clean local run (2026-09-12/13) minus roughly five to ten points of headroom;
 * ratchet upwards as coverage improves. Every published module with unit tests has an entry, except:
 *  - :rdf:sparql-contract: interfaces and value types only, no tests of its own (covered by providers).
 *  - :kastor-gen:gradle-plugin: its tests run the plugin in out-of-process Gradle TestKit builds, which
 *    the JaCoCo agent of the test JVM does not see (measured 0%). The plugin is gated by TestKit
 *    behaviour tests and release-plugin-smoke instead.
 * Non-published modules (examples, benchmarks, :rdf:conformance, :rdf:examples) are not gated.
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
  ":rdf:jena-reasoning" to "0.80", // measured 92.5%
  ":rdf:rdf4j-reasoning" to "0.85", // measured 94.3%
  ":rdf:cli" to "0.70", // measured 80.5%
  ":kastor-gen:processor" to "0.50",
  ":kastor-gen:runtime" to "0.35",
  ":kastor-gen:validation-jena" to "0.70",
  ":kastor-gen:validation-rdf4j" to "0.55",
  ":tools:onto-quality" to "0.75",
  ":tools:onto-quality-metrics" to "0.80",
  // Most of embed needs the ONNX model, whose tests are opt-in (KASTOR_RUN_EMBEDDING_TESTS); measured 26.5%.
  ":tools:onto-quality-embed" to "0.20",
  // Remote LLM calls are opt-in (KASTOR_SKIP_OPENAI_LLM_TESTS in CI); measured 9.4%. Floor guards against regression only.
  ":tools:onto-quality-llm-koog" to "0.05",
  ":tools:onto-quality-cli" to "0.65", // measured 76.0%
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
    tasks.register<org.gradle.api.tasks.bundling.Jar>("sourcesJar") {
      archiveClassifier.set("sources")
      val sourceSets = project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
      from(sourceSets.getByName("main").allSource)
    }

    // Javadoc JAR (may be empty for pure Kotlin projects)
    tasks.register<org.gradle.api.tasks.bundling.Jar>("javadocJar") {
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
      tasks.named<org.gradle.api.tasks.bundling.Jar>("javadocJar") {
        dependsOn("dokkaGeneratePublicationHtml")
        from(layout.buildDirectory.dir("dokka/html"))
      }
      // CycloneDX SBOM of the published runtime closure, attached to the module's publication as
      // <artifactId>-<version>-cyclonedx.{json,xml}. scripts/check-staged-publications.py requires both.
      apply(plugin = "org.cyclonedx.bom")
      val sbom = tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
        includeConfigs.set(listOf("runtimeClasspath"))
      }
      val isPluginMarker = { publication: MavenPublication -> publication.name.endsWith("PluginMarkerMaven") }
      extensions.configure<PublishingExtension> {
        publications.withType<MavenPublication>().matching { !isPluginMarker(it) }.configureEach {
          artifact(sbom.flatMap { it.jsonOutput }) { classifier = "cyclonedx"; extension = "json"; builtBy(sbom) }
          artifact(sbom.flatMap { it.xmlOutput }) { classifier = "cyclonedx"; extension = "xml"; builtBy(sbom) }
        }
      }
      // Name the SBOM's root component after the published coordinates, not the Gradle project name.
      // Lazy: java-gradle-plugin creates its publication (and modules rename artifactIds) after this runs.
      val mainPublication = provider {
        extensions.getByType<PublishingExtension>().publications.withType<MavenPublication>()
          .first { !isPluginMarker(it) }
      }
      sbom.configure {
        componentGroup.set(mainPublication.map { it.groupId })
        componentName.set(mainPublication.map { it.artifactId })
        componentVersion.set(mainPublication.map { it.version })
      }
    }
    extensions.configure<PublishingExtension> {
      repositories { maven { name = "staging"; url = rootProject.layout.buildDirectory.dir("release-repository").get().asFile.toURI() } }
      publications.withType<MavenPublication>().configureEach {
        if (project.path == ":kastor-gen:gradle-plugin" && name == "pluginMaven" && !pluginManager.hasPlugin("com.gradle.plugin-publish")) {
          artifact(tasks.named("sourcesJar")); artifact(tasks.named("javadocJar"))
        }
        pom {
          name.convention(artifactId)
          description.convention("Kastor Kotlin RDF and ontology tools")
          url.set("https://github.com/geoknoesis/kastor")
          licenses { license { name.set("Apache License, Version 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0.txt") } }
          scm {
            url.set("https://github.com/geoknoesis/kastor")
            connection.set("scm:git:https://github.com/geoknoesis/kastor.git")
            developerConnection.set("scm:git:ssh://git@github.com/geoknoesis/kastor.git")
          }
          issueManagement { system.set("GitHub Issues"); url.set("https://github.com/geoknoesis/kastor/issues") }
          developers { developer { id.set("geoknoesis"); name.set("Geoknoesis"); url.set("https://github.com/geoknoesis") } }
        }
      }
    }
    tasks.withType<PublishToMavenRepository>().configureEach { mustRunAfter(":cleanReleaseRepository") }
    // Signing is optional for local staging, but mandatory for :centralBundle (enforced below).
    // Blank values count as absent: GitHub Actions expands a missing secret to "".
    val signingKey = providers.environmentVariable("KASTOR_SIGNING_KEY").filter { it.isNotBlank() }
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
//   centralBundle                              -> signed Maven Central Portal bundle zip (local use)
//
// .github/workflows/publish.yml does not run centralBundle: it signs and bundles the exact
// build/release-repository that release-readiness.yml staged and tested in the same workflow run,
// so the uploaded bytes are the tested bytes. Nothing in this build uploads anywhere.
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
    check(providers.environmentVariable("KASTOR_SIGNING_KEY").filter { it.isNotBlank() }.isPresent) {
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
