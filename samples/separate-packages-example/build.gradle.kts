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
    implementation("com.geoknoesis.kastor:rdf-jena:0.2.0")
}

// Configure separate packages for different generated components
kastorGen {
    ontologies {
        // DCAT-US with separate packages
        create("dcat") {
            shaclPath = "ontologies/dcat-us.shacl.ttl"
            contextPath = "ontologies/dcat-us.context.jsonld"

            interfacePackage = "com.example.dcatus.interfaces"
            wrapperPackage = "com.example.dcatus.wrappers"
            vocabularyPackage = "com.example.dcatus.vocab"

            generateInterfaces = true
            generateWrappers = true
            generateVocabulary = true

            vocabularyName = "DCAT"
            vocabularyNamespace = "http://www.w3.org/ns/dcat#"
            vocabularyPrefix = "dcat"

            outputDirectory = "build/generated/sources/dcat"
        }

        // Schema.org with separate packages
        create("schema") {
            shaclPath = "ontologies/schema.shacl.ttl"
            contextPath = "ontologies/schema.context.jsonld"

            interfacePackage = "com.example.schema.interfaces"
            wrapperPackage = "com.example.schema.wrappers"
            vocabularyPackage = "com.example.schema.vocab"

            generateInterfaces = true
            generateWrappers = true
            generateVocabulary = true

            vocabularyName = "SCHEMA"
            vocabularyNamespace = "https://schema.org/"
            vocabularyPrefix = "schema"

            outputDirectory = "build/generated/sources/schema"
        }

        // FOAF with separate packages
        create("foaf") {
            shaclPath = "ontologies/foaf.shacl.ttl"
            contextPath = "ontologies/foaf.context.jsonld"

            interfacePackage = "com.example.foaf.interfaces"
            wrapperPackage = "com.example.foaf.wrappers"
            vocabularyPackage = "com.example.foaf.vocab"

            generateInterfaces = true
            generateWrappers = true
            generateVocabulary = true

            vocabularyName = "FOAF"
            vocabularyNamespace = "http://xmlns.com/foaf/0.1/"
            vocabularyPrefix = "foaf"

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
