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
