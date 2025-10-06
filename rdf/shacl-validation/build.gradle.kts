plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    implementation(project(":rdf:core"))
    
    // SHACL validation specific dependencies
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
    // Test dependencies
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testImplementation("org.assertj:assertj-core:3.24.2")
}
