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

tasks.test {
    useJUnitPlatform()
    // TestKit consumer builds share one bounded, reusable Gradle user home / daemon directory.
    systemProperty("kastor.testkit.dir", layout.buildDirectory.dir("testkit").get().asFile.absolutePath)
}
