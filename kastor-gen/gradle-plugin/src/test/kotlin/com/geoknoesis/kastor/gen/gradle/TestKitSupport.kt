package com.geoknoesis.kastor.gen.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildFailure
import java.io.File

/** Shared TestKit directory so nested daemons are reused across tests and runs (set by the Gradle test task). */
internal fun testKitDir(): File =
    File(System.getProperty("kastor.testkit.dir") ?: File(System.getProperty("java.io.tmpdir"), "kastor-testkit").path)
        .apply { mkdirs() }

/**
 * TestKit consumer builds that never use the network.
 *
 * - Both plugins of a consumer build (`kotlin("jvm")` and `com.geoknoesis.kastor.gen`) come from the plugin-under-test
 *   classpath (`withPluginClasspath()`): the consumer applies them without a version and resolves neither.
 * - Its only repository is the file repository that `:kastor-gen:gradle-plugin:testKitRepository` builds from what
 *   the outer build resolved (Kotlin compiler, build tools, standard library), and it runs with `--offline`.
 *
 * So nothing is downloaded by a test, whatever the state of the TestKit directory; the JDK is the one that runs the
 * tests (`JAVA_HOME`), which satisfies `jvmToolchain(21)` without toolchain provisioning.
 */
internal object ConsumerBuild {

    private fun repository(): File {
        val path = System.getProperty("kastor.testkit.repo")
            ?: error(
                "The system property kastor.testkit.repo is not set: run the tests through Gradle " +
                    "(:kastor-gen:gradle-plugin:test builds the file repository of the consumer builds first)."
            )
        return File(path).also { check(it.isDirectory) { "The TestKit repository $path does not exist" } }
    }

    /** `settings.gradle` of a consumer build named [name]: one repository, the file repository. */
    fun settings(name: String): String = """
        dependencyResolutionManagement {
            repositories {
                maven {
                    url = uri('${repository().toURI()}')
                    metadataSources { mavenPom() }
                }
            }
        }
        rootProject.name = '$name'
    """.trimIndent()

    /** `gradle.properties` that keep the nested build small. */
    val properties: String = """
        org.gradle.workers.max=1
        org.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2
        kotlin.compiler.execution.strategy=in-process
        kotlin.internal.collectFUSMetrics=false
    """.trimIndent()

    fun runner(projectDir: File, vararg args: String): GradleRunner =
        GradleRunner.create().withProjectDir(projectDir).withTestKitDir(testKitDir()).withPluginClasspath().forwardOutput()
            .withEnvironment(System.getenv() + ("JAVA_HOME" to System.getProperty("java.home")))
            .withArguments(
                *args, "--offline", "--stacktrace", "--no-build-cache", "--configuration-cache", "--configuration-cache-problems=fail",
            )

    /** Runs the build; a failure caused by something missing from the file repository says what to do about it. */
    fun build(projectDir: File, vararg args: String): BuildResult =
        try {
            runner(projectDir, *args).build()
        } catch (failure: UnexpectedBuildFailure) {
            val output = failure.buildResult.output
            if ("offline mode" in output || "Could not resolve" in output || "Could not find" in output) {
                throw AssertionError(
                    "The consumer build could not resolve a dependency. It runs offline and resolves only from the file " +
                        "repository ${repository()} (task :kastor-gen:gradle-plugin:testKitRepository): add the " +
                        "configuration that provides the missing module to that task in " +
                        "kastor-gen/gradle-plugin/build.gradle.kts.\n\n$output",
                    failure,
                )
            }
            throw failure
        }
}
