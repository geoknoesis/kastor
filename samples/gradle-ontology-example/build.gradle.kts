plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.geoknoesis.kastor.gen")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.geoknoesis.kastor:kastor-gen-runtime:0.1.0")
    implementation("com.geoknoesis.kastor:rdf-core:0.1.0")
}

// Configure Kastor Gen plugin
kastorGen {
    shaclPath.set("ontologies/dcat-us.shacl.ttl")
    contextPath.set("ontologies/dcat-us.context.jsonld")
    targetPackage.set("com.example.dcatus.generated")
    generateInterfaces.set(true)
    generateWrappers.set(true)
    outputDirectory.set("build/generated/sources/kastor-gen")
}

// Add generated sources to source sets
sourceSets {
    main {
        kotlin {
            srcDir("build/generated/sources/kastor-gen")
        }
    }
}

tasks.compileKotlin {
    dependsOn("generateOntology")
}
