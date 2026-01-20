plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
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
    implementation("com.google.devtools.ksp:symbol-processing-api:1.9.10-1.0.13")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.24")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.gradle:gradle-tooling-api:8.0")
    testImplementation("org.gradle:gradle-test-kit:8.0")
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

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
}
