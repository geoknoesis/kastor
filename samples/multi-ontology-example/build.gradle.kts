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
    implementation("com.geoknoesis.kastor:rdf-jena:0.1.0")
}

// Configure multiple ontologies
kastorGen {
    ontologies {
        // DCAT-US ontology
        create("dcat") {
            shaclPath = "ontologies/dcat-us.shacl.ttl"
            contextPath = "ontologies/dcat-us.context.jsonld"
            interfacePackage = "com.example.dcatus.generated"
            wrapperPackage = "com.example.dcatus.generated"
            vocabularyPackage = "com.example.dcatus.generated"
            outputDirectory = "build/generated/sources/dcat"
        }

        // Schema.org ontology
        create("schema") {
            shaclPath = "ontologies/schema.shacl.ttl"
            contextPath = "ontologies/schema.context.jsonld"
            interfacePackage = "com.example.schema.generated"
            wrapperPackage = "com.example.schema.generated"
            vocabularyPackage = "com.example.schema.generated"
            outputDirectory = "build/generated/sources/schema"
        }

        // FOAF ontology
        create("foaf") {
            shaclPath = "ontologies/foaf.shacl.ttl"
            contextPath = "ontologies/foaf.context.jsonld"
            interfacePackage = "com.example.foaf.generated"
            wrapperPackage = "com.example.foaf.generated"
            vocabularyPackage = "com.example.foaf.generated"
            outputDirectory = "build/generated/sources/foaf"
        }
    }
}

// Add generated sources to source sets
sourceSets {
    main {
        kotlin {
            srcDir("build/generated/sources/dcat")
            srcDir("build/generated/sources/schema")
            srcDir("build/generated/sources/foaf")
        }
    }
}

tasks.compileKotlin {
    dependsOn("generateOntology")
}
