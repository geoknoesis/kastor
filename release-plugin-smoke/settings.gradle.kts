pluginManagement {
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri(providers.gradleProperty("kastorRepository").getOrElse("../build/release-repository")) } }
            filter { includeGroupByRegex("com\\.geoknoesis\\.kastor(\\..*)?") }
        }
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("com.geoknoesis.kastor.gen") version providers.gradleProperty("kastorVersion").getOrElse("0.2.1")
    }
}
rootProject.name = "kastor-published-plugin-consumer"
include("late-processor")
