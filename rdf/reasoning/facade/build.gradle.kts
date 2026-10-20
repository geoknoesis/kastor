plugins {
    kotlin("jvm")
    `java-library`
    id("maven-publish")
}

dependencies {
    api(project(":rdf:core"))

    testImplementation(libs.kotlinx.coroutines.test)

}

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
