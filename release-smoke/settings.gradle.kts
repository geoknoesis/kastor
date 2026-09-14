pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
    // Track the toolchain of the Kastor checkout this smoke test lives in (single source of truth:
    // ../gradle/libs.versions.toml); override with -PkotlinVersion=… to test another compiler.
    val catalogKotlin: String = Regex("""(?m)^\s*kotlin\s*=\s*"([^"]+)"""")
        .find(file("../gradle/libs.versions.toml").readText())?.groupValues?.get(1)
        ?: error("Cannot read the kotlin version from ../gradle/libs.versions.toml; pass -PkotlinVersion=<version>")
    plugins {
        kotlin("jvm") version providers.gradleProperty("kotlinVersion").getOrElse(catalogKotlin)
    }
}
rootProject.name = "kastor-release-smoke"
