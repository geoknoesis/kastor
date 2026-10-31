plugins {
    kotlin("jvm")
    `java-library`
}
dependencies {
    api(project(":rdf:core"))
    implementation(project(":rdf:sparql-contract"))
    implementation(project(":rdf:shacl-validation"))
    testImplementation(project(":rdf:jena"))
    testImplementation(project(":rdf:rdf4j"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
tasks.test { useJUnitPlatform() }
