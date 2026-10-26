plugins {
    kotlin("jvm")
    `java-library`
    id("maven-publish")
}

dependencies {
    api(project(":rdf:core"))

    testImplementation(libs.kotlinx.coroutines.test)

}

// The shared XSD lexical parsers of :rdf:core (com.geoknoesis.kastor.rdf.xsd) are internal Kastor API.
kotlin { compilerOptions { optIn.add("com.geoknoesis.kastor.rdf.InternalKastorApi") } }

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(tasks.named("sourcesJar"))
            artifact(tasks.named("javadocJar"))

            groupId = project.group.toString()
            artifactId = "rdf-reasoning"
            version = project.version.toString()
        }
    }
}
