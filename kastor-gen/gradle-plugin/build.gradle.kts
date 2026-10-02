plugins {
    alias(libs.plugins.kotlin.jvm)
    id("java-gradle-plugin")
    id("maven-publish")
    alias(libs.plugins.plugin.publish)
}

dependencies {
    implementation(project(":kastor-gen:processor"))

    // Gradle API
    implementation(gradleApi())
    // No kotlin-gradle-plugin dependency: source sets are wired reflectively (see KotlinSourceSetWiring),
    // so the plugin never ships a second copy of KGP classes into consumer builds.

    // KSP
    implementation(libs.ksp.symbol.processing.api)

    // KotlinPoet (transitive from processor, but needed for FileSpec)
    implementation(libs.kotlinpoet)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(gradleTestKit())
    testImplementation(project(":kastor-gen:runtime"))
    testImplementation(project(":rdf:sparql-contract"))
}

gradlePlugin {
    website.set("https://github.com/geoknoesis/kastor")
    vcsUrl.set("https://github.com/geoknoesis/kastor")
    plugins {
        create("kastorGen") {
            id = "com.geoknoesis.kastor.gen"
            implementationClass = "com.geoknoesis.kastor.gen.gradle.OntoMapperPlugin"
            displayName = "Kastor Gen"
            description = "Generate Kotlin domain interfaces, RDF-backed wrappers, vocabularies and DSLs from SHACL shapes and JSON-LD contexts"
            tags.set(listOf("rdf", "shacl", "json-ld", "kotlin", "code-generation", "semantic-web", "ontology"))
        }
    }
}

// Release naming: every published kastor-gen artifact is kastor-gen-*; the plugin marker keeps the plugin id.
publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") artifactId = "kastor-gen-gradle-plugin"
    }
}

// Java source/target is governed by the root `jvmToolchain(21)`; no per-module
// sourceCompatibility/targetCompatibility needed.

// ---------------------------------------------------------------------------------------------------------------------
// TestKit consumer builds resolve nothing from the network.
//
// The consumer builds of the TestKit tests apply the Kotlin Gradle plugin and compile Kotlin. Left to themselves they
// would download the plugin and the Kotlin compiler into the build-local TestKit directory on every cold run. Instead:
// - the Kotlin Gradle plugin is put on the plugin-under-test classpath, next to this plugin, so the consumer applies
//   `kotlin("jvm")` without a version and without resolving it;
// - what the Kotlin plugin resolves when it compiles (compiler, build tools, the scripting compiler plugin, the
//   standard library) is copied from this build's own resolution into a file repository (`testKitRepository`), which
//   is the only repository of the consumer builds; they run with `--offline`.
// Everything is resolved by this build, through its repositories, lock files and dependency verification.
// ---------------------------------------------------------------------------------------------------------------------

val testKitPluginClasspath: Configuration = configurations.create("testKitPluginClasspath") {
    description = "The Kotlin Gradle plugin for the plugin-under-test classpath of the TestKit consumer builds."
    isCanBeConsumed = false
    isCanBeResolved = true
    // Annotations of gson only: not needed to run the plugin, and not among the artifacts this build verifies.
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
        // The variant of the plugin built for the Gradle version that runs this build (and the consumer builds).
        attribute(
            org.gradle.api.attributes.plugin.GradlePluginApiVersion.GRADLE_PLUGIN_API_VERSION_ATTRIBUTE,
            objects.named(org.gradle.util.GradleVersion.current().version),
        )
    }
}

dependencies {
    testKitPluginClasspath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
}

tasks.named<org.gradle.plugin.devel.tasks.PluginUnderTestMetadata>("pluginUnderTestMetadata") {
    pluginClasspath.from(testKitPluginClasspath)
}

/**
 * Writes the modules of resolved configurations as a Maven repository on disk: each module's artifact and a minimal
 * POM that lists the dependencies it was resolved with (exact versions; no parents, no BOMs), so the repository is
 * self-contained and a build that uses only it resolves without any other source.
 */
abstract class TestKitRepository : DefaultTask() {
    @get:Input
    abstract val graphs: ListProperty<org.gradle.api.artifacts.result.ResolvedComponentResult>

    /** `group:name:version` of each module with an artifact, and the path of that artifact. */
    @get:Input
    abstract val artifactPaths: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val artifactFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val repository: DirectoryProperty

    @TaskAction
    fun write() {
        val root = repository.get().asFile
        root.deleteRecursively()
        root.mkdirs()
        val modules = LinkedHashMap<String, MutableSet<String>>()
        val pending = ArrayDeque(graphs.get())
        val seen = HashSet<Any>()
        while (pending.isNotEmpty()) {
            val component = pending.removeFirst()
            // The roots are all this project (one id): only modules are visited once.
            if (component.id is org.gradle.api.artifacts.component.ModuleComponentIdentifier && !seen.add(component.id)) continue
            val dependencies = component.dependencies
                .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                .filterNot { it.isConstraint }
                .map { it.selected }
            pending += dependencies
            val id = component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier ?: continue
            modules.getOrPut(id.displayName) { LinkedHashSet() } += dependencies
                .mapNotNull { (it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.displayName }
        }
        val artifacts = artifactPaths.get()
        for ((module, dependencies) in modules) {
            val (group, name, version) = module.split(':')
            val directory = File(root, "${group.replace('.', '/')}/$name/$version").apply { mkdirs() }
            val artifact = artifacts[module]?.let(::File)
            artifact?.copyTo(File(directory, "$name-$version.${artifact.extension}"), overwrite = true)
            File(directory, "$name-$version.pom").writeText(
                buildString {
                    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                    append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n")
                    append("  <modelVersion>4.0.0</modelVersion>\n")
                    append("  <groupId>$group</groupId>\n  <artifactId>$name</artifactId>\n  <version>$version</version>\n")
                    append("  <packaging>${if (artifact == null) "pom" else artifact.extension}</packaging>\n")
                    append("  <dependencies>\n")
                    for (dependency in dependencies.sorted()) {
                        val (g, a, v) = dependency.split(':')
                        append("    <dependency><groupId>$g</groupId><artifactId>$a</artifactId><version>$v</version><scope>compile</scope></dependency>\n")
                    }
                    append("  </dependencies>\n</project>\n")
                },
            )
        }
    }
}

val testKitRepository = tasks.register<TestKitRepository>("testKitRepository") {
    description = "Builds the file repository that the TestKit consumer builds resolve from (no network)."
    // What the Kotlin Gradle plugin resolves in a JVM project when it compiles: the same configurations of this very
    // project, which applies the same plugin version. The standard library and its annotations are part of them.
    listOf("kotlinBuildToolsApiClasspath", "kotlinCompilerClasspath", "kotlinCompilerPluginClasspathMain").forEach { name ->
        val configuration = configurations.getByName(name)
        graphs.add(configuration.incoming.resolutionResult.rootComponent)
        artifactFiles.from(configuration.incoming.artifacts.artifactFiles)
        artifactPaths.putAll(
            configuration.incoming.artifacts.resolvedArtifacts.map { resolved ->
                resolved
                    .filter { it.id.componentIdentifier is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                    .associate { it.id.componentIdentifier.displayName to it.file.absolutePath }
            },
        )
    }
    repository.set(layout.buildDirectory.dir("testkit-repository"))
}

tasks.test {
    useJUnitPlatform()
    // TestKit consumer builds share one bounded, reusable Gradle user home / daemon directory.
    systemProperty("kastor.testkit.dir", layout.buildDirectory.dir("testkit").get().asFile.absolutePath)
    // The only repository of the consumer builds (see above).
    inputs.dir(testKitRepository.flatMap { it.repository })
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("testKitRepository")
    systemProperty("kastor.testkit.repo", layout.buildDirectory.dir("testkit-repository").get().asFile.absolutePath)
}
