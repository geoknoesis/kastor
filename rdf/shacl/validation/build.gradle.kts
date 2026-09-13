plugins {
    kotlin("jvm")
    `java-library`
    id("maven-publish")
}

dependencies {
    implementation(project(":rdf:core"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.assertj.core)
    testImplementation(project(":rdf:jena"))
    testImplementation(project(":rdf:rdf4j"))
    testImplementation(libs.jena.arq)
    testImplementation(libs.jena.shacl)
}

tasks.withType<Test>().configureEach {
    // CLI system properties are not automatically forwarded to forked test JVMs.
    listOf("shacl.w3c.useNative", "shacl.w3c.manifest").forEach { name ->
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
    inputs.files(fileTree("test-data/w3c-shacl12"))
    providers.systemProperty("shacl.w3c.manifest").orNull?.let {
        inputs.files(fileTree(file(it).parentFile))
    }
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
