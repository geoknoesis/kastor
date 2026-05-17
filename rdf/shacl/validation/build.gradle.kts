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
    // Forward CLI `-Dshacl.w3c.useNative=...` into forked test JVMs (not forwarded by default).
    System.getProperty("shacl.w3c.useNative")?.let { value ->
        systemProperty("shacl.w3c.useNative", value)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(tasks.named("sourcesJar"))
            artifact(tasks.named("javadocJar"))

            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()
        }
    }
}
