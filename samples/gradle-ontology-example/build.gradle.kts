plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.geoknoesis.kastor.gen")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.geoknoesis.kastor:kastor-gen-runtime:0.2.0")
    implementation("com.geoknoesis.kastor:rdf-core:0.2.0")
    implementation("com.geoknoesis.kastor:sparql-lang:0.2.0")
}

// Configure Kastor Gen plugin
kastorGen {
    ontologies {
        create("dcat") {
            shaclPath = "ontologies/dcat-us.shacl.ttl"
            contextPath = "ontologies/dcat-us.context.jsonld"
            interfacePackage = "com.example.dcatus.generated"
            wrapperPackage = "com.example.dcatus.generated"
            vocabularyPackage = "com.example.dcatus.generated"
            generateInterfaces = true
            generateWrappers = true
            outputDirectory = "build/generated/sources/kastor-gen"
        }
    }
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
