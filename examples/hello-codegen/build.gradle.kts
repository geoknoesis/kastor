plugins {
    id("org.jetbrains.kotlin.jvm")
    id("application")
    // The root build applies com.google.devtools.ksp to every :examples:* project.
}

application {
    mainClass.set("com.example.hello.HelloCodegenKt")
}

dependencies {
    implementation(project(":rdf:core"))
    implementation(project(":rdf:jena"))
    implementation(project(":kastor-gen:runtime"))
    // Generates Person + PersonWrapper from the @file:Rdf(shacl = "person-shape.ttl") annotation.
    ksp(project(":kastor-gen:processor"))
}
