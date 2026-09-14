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

// KSP cannot see ontology files: declare them as inputs of the KSP task so that editing person-shape.ttl
// re-runs generation, and tell the Kastor processor they are tracked (silences its staleness warning).
tasks.matching { it.name == "kspKotlin" }.configureEach {
    inputs.dir("src/main/resources").withPropertyName("kastorOntologyFiles").withPathSensitivity(PathSensitivity.RELATIVE)
}
extensions.configure<com.google.devtools.ksp.gradle.KspExtension> {
    arg("kastor.gen.resources.tracked", "true")
}
