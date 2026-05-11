plugins {
    id("org.jetbrains.kotlin.jvm")
    id("application")
}

// Disable KSP for this simple example
afterEvaluate {
    plugins.withId("com.google.devtools.ksp") {
        plugins.apply("com.google.devtools.ksp")
    }
}

application {
    mainClass.set("HelloWorldKt")
}

dependencies {
    implementation(project(":rdf:core"))
    implementation(project(":rdf:jena")) // For SPARQL query support
}

