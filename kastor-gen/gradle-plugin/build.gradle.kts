plugins {
    alias(libs.plugins.kotlin.jvm)
    id("java-gradle-plugin")
    id("maven-publish")
}

dependencies {
    implementation(project(":kastor-gen:processor"))

    // Gradle API
    implementation(gradleApi())

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // KSP
    implementation(libs.ksp.symbol.processing.api)

    // KotlinPoet (transitive from processor, but needed for FileSpec)
    implementation(libs.kotlinpoet)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.gradle.tooling.api)
    testImplementation(gradleTestKit())
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
