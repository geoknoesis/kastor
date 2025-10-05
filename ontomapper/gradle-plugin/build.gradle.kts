plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-gradle-plugin")
    id("maven-publish")
}

dependencies {
    implementation(project(":ontomapper:processor"))

    // Gradle API
    implementation(gradleApi())

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // KSP
    implementation("com.google.devtools.ksp:symbol-processing-api:1.9.10-1.0.13")

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation("org.gradle:gradle-tooling-api:8.0")
    testImplementation("org.gradle:gradle-test-kit:8.0")
}

gradlePlugin {
    plugins {
        create("ontomapper") {
            id = "com.geoknoesis.ontomapper"
            implementationClass = "com.geoknoesis.ontomapper.gradle.OntoMapperPlugin"
            displayName = "OntoMapper"
            description = "Generate domain interfaces and wrappers from SHACL and JSON-LD context files"
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
}
