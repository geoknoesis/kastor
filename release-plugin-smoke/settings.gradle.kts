pluginManagement {
    // Defaults track the Kastor checkout this smoke test lives in; override with
    // -PkastorVersion=…, -PkotlinVersion=…, -PkspVersion=… (e.g. from a CI matrix).
    val rootVersion: String = run {
        val properties = file("../gradle.properties")
        val fromProperties = if (properties.isFile) {
            java.util.Properties().apply { properties.inputStream().use { load(it) } }.getProperty("version")
        } else null
        fromProperties
            ?: Regex("""(?m)^\s*version\s*=\s*"([^"]+)"""").find(file("../build.gradle.kts").readText())?.groupValues?.get(1)
            ?: error("Cannot determine the Kastor version from ../gradle.properties; pass -PkastorVersion=<version>")
    }
    val kastorVersion = providers.gradleProperty("kastorVersion").getOrElse(rootVersion)
    gradle.extra["kastorVersion"] = kastorVersion
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri(providers.gradleProperty("kastorRepository").getOrElse("../build/release-repository")) } }
            filter { includeGroupByRegex("com\\.geoknoesis\\.kastor(\\..*)?") }
        }
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("com.geoknoesis.kastor.gen") version kastorVersion
        kotlin("jvm") version providers.gradleProperty("kotlinVersion").getOrElse("2.4.20")
        id("com.google.devtools.ksp") version providers.gradleProperty("kspVersion").getOrElse("2.3.12")
    }
}
rootProject.name = "kastor-published-plugin-consumer"
include("late-processor")
