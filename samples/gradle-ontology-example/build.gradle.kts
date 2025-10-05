plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.geoknoesis.ontomapper")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.geoknoesis.kastor:ontomapper-runtime:0.1.0")
    implementation("com.geoknoesis.kastor:rdf-core:0.1.0")
}

// Configure OntoMapper plugin
ontomapper {
    shaclPath.set("ontologies/dcat-us.shacl.ttl")
    contextPath.set("ontologies/dcat-us.context.jsonld")
    targetPackage.set("com.example.dcatus.generated")
    generateInterfaces.set(true)
    generateWrappers.set(true)
    outputDirectory.set("build/generated/sources/ontomapper")
}

// Add generated sources to source sets
sourceSets {
    main {
        kotlin {
            srcDir("build/generated/sources/ontomapper")
        }
    }
}

tasks.compileKotlin {
    dependsOn("generateOntology")
}
