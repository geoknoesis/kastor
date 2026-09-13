plugins {
    alias(libs.plugins.kotlin.jvm)
    id("java-gradle-plugin")
    id("maven-publish")
}

dependencies {
    implementation(project(":kastor-gen:processor"))

    // Gradle API
    implementation(gradleApi())
    // No kotlin-gradle-plugin dependency: source sets are wired reflectively (see KotlinSourceSetWiring),
    // so the plugin never ships a second copy of KGP classes into consumer builds.

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

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
    plugins {
        create("kastorGen") {
            id = "com.geoknoesis.kastor.gen"
            implementationClass = "com.geoknoesis.kastor.gen.gradle.OntoMapperPlugin"
            displayName = "Kastor Gen"
            description = "Generate domain interfaces and wrappers from SHACL and JSON-LD context files"
        }
    }
}

// Java source/target is governed by the root `jvmToolchain(21)`; no per-module
// sourceCompatibility/targetCompatibility needed.

tasks.test {
    useJUnitPlatform()
}
