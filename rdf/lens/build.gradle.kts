plugins {
    kotlin("jvm")
    `java-library`
}
dependencies {
    api(project(":rdf:core"))
    implementation(project(":rdf:sparql-contract"))
    testImplementation(project(":rdf:jena"))
    testImplementation(project(":rdf:rdf4j"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
tasks.test { useJUnitPlatform() }
