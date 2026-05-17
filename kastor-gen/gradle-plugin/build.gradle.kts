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
    implementation("com.google.devtools.ksp:symbol-processing-api:${libs.versions.ksp.get()}")
    
    // KotlinPoet (transitive from processor, but needed for FileSpec)
    implementation("com.squareup:kotlinpoet:2.2.0")

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
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
