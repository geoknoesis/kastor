plugins {
    kotlin("jvm")
    `java-library`
    id("maven-publish")
}

dependencies {
    api(project(":rdf:core"))
    implementation(project(":rdf:reasoning"))
    // Turtle / RDF/XML round-trip uses Rdf.parse / RdfGraph.serialize (provider SPI).
    implementation(project(":rdf:jena"))

    // OWL API 4.x + HermiT (versions aligned; isolated to this module).
    implementation("net.sourceforge.owlapi:owlapi-distribution:4.5.29")
    implementation("net.sourceforge.owlapi:org.semanticweb.hermit:1.4.5.519")

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
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
