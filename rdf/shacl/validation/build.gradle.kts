plugins {
    kotlin("jvm")
    `java-library`
    id("maven-publish")
}

dependencies {
  implementation(libs.slf4j.api)
  testImplementation(libs.junit.params)

  testImplementation(libs.jena.core)
  testImplementation(libs.opentest4j)
  implementation(project(":rdf:sparql-contract"))

    api(project(":rdf:core"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(project(":rdf:jena"))
    testImplementation(project(":rdf:rdf4j"))
    testImplementation(libs.jena.arq)
    testImplementation(libs.jena.shacl)
}

tasks.withType<Test>().configureEach {
    // CLI system properties are not automatically forwarded to forked test JVMs.
    listOf("shacl.w3c.useNative", "shacl.w3c.manifest", "shacl.w3c.includeNonApproved").forEach { name ->
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
    // Run the manifest rows that are not (yet) approved too: -PshaclW3cIncludeNonApproved=true.
    if (providers.gradleProperty("shaclW3cIncludeNonApproved").orNull == "true") {
        systemProperty("shacl.w3c.includeNonApproved", "true")
    }
    inputs.property("shaclW3cIncludeNonApproved", providers.gradleProperty("shaclW3cIncludeNonApproved").orElse("false"))
    // Explicit opt-out: a missing or empty W3C suite fails the conformance test unless this is set.
    val allowMissingW3c = providers.gradleProperty("shaclW3cAllowMissingData").orNull == "true"
    systemProperty("shacl.w3c.allowMissingData", allowMissingW3c.toString())
    inputs.property("shaclW3cAllowMissingData", allowMissingW3c)
    inputs.files(fileTree("test-data/w3c-shacl12"))
    providers.systemProperty("shacl.w3c.manifest").orNull?.let {
        inputs.files(fileTree(file(it).parentFile))
    }
}

// Full W3C SHACL 1.2 manifest suite only (JUnit tag `w3c`). Requires the upstream checkout under
// test-data/w3c-shacl12 (see test-data/README.md) and fails instead of skipping when it is missing.
tasks.register<Test>("w3cConformanceTest") {
    group = "verification"
    description = "Runs the full W3C SHACL 1.2 test suite against the native SHACL engine."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("w3c") }
    systemProperty("shacl.w3c.requireSuite", "true")
    shouldRunAfter(tasks.named("test"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(tasks.named("sourcesJar"))
            artifact(tasks.named("javadocJar"))

            groupId = project.group.toString()
            artifactId = "rdf-shacl-validation"
            version = project.version.toString()
        }
    }
}
