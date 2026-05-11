plugins {
    id("org.jetbrains.kotlin.jvm")
    id("application")
    // KSP is optional - uncomment to enable code generation
    // id("com.google.devtools.ksp")
}

application {
    mainClass.set("HelloCodegenKt")
}

dependencies {
    implementation(project(":rdf:core"))
    implementation(project(":rdf:jena"))
    implementation(project(":kastor-gen:runtime"))
    // Uncomment to enable code generation
    // ksp(project(":kastor-gen:processor"))
}

