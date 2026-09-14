pluginManagement {
    // Defaults track the Kastor checkout this smoke test lives in; override with
    // -PkastorVersion=…, -PkotlinVersion=…, -PkspVersion=… (e.g. from a CI matrix).
    // The files are parsed line by line: this block is compiled on its own, before the build's classpath exists.

    /** `key=value` from a properties-style file (first match, `#` comments ignored). */
    fun propertyValue(path: String, key: String): String? {
        val source = file(path)
        if (!source.isFile) return null
        return source.readLines()
            .map { it.substringBefore('#').trim() }
            .firstOrNull { '=' in it && it.substringBefore('=').trim() == key }
            ?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** `key = "value"` from the `[versions]` table of the root version catalog. */
    fun catalogVersion(key: String): String? {
        val catalog = file("../gradle/libs.versions.toml")
        if (!catalog.isFile) return null
        var table = ""
        for (raw in catalog.readLines()) {
            val line = raw.substringBefore('#').trim()
            if (line.startsWith("[")) {
                table = line
                continue
            }
            if (table == "[versions]" && '=' in line && line.substringBefore('=').trim() == key) {
                return line.substringAfter('=').trim().removeSurrounding("\"").takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    val rootVersion: String = propertyValue("../gradle.properties", "version")
        ?: Regex("""(?m)^\s*version\s*=\s*"([^"]+)"""").find(file("../build.gradle.kts").readText())?.groupValues?.get(1)
        ?: error("Cannot determine the Kastor version from ../gradle.properties; pass -PkastorVersion=<version>")
    val kastorVersion = providers.gradleProperty("kastorVersion").getOrElse(rootVersion)
    gradle.extra["kastorVersion"] = kastorVersion
    val kotlinVersion = providers.gradleProperty("kotlinVersion").orNull
        ?: catalogVersion("kotlin")
        ?: error("Cannot read the kotlin version from ../gradle/libs.versions.toml; pass -PkotlinVersion=<version>")
    val kspVersion = providers.gradleProperty("kspVersion").orNull
        ?: catalogVersion("ksp")
        ?: error("Cannot read the ksp version from ../gradle/libs.versions.toml; pass -PkspVersion=<version>")
    gradle.extra["kspVersion"] = kspVersion
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
        kotlin("jvm") version kotlinVersion
        id("com.google.devtools.ksp") version kspVersion
    }
}
rootProject.name = "kastor-published-plugin-consumer"
include("late-processor")
