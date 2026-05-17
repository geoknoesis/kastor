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

// Configure vocabulary generation
kastorGen {
    ontologies {
        // DCAT-US vocabulary
        create("dcat") {
            shaclPath = "ontologies/dcat-us.shacl.ttl"
            contextPath = "ontologies/dcat-us.context.jsonld"
            vocabularyPackage = "com.example.dcatus.vocab"
            generateInterfaces = false
            generateWrappers = false
            generateVocabulary = true
            vocabularyName = "DCAT"
            vocabularyNamespace = "http://www.w3.org/ns/dcat#"
            vocabularyPrefix = "dcat"
            outputDirectory = "build/generated/sources/dcat-vocab"
        }

        // Schema.org vocabulary
        create("schema") {
            shaclPath = "ontologies/schema.shacl.ttl"
            contextPath = "ontologies/schema.context.jsonld"
            vocabularyPackage = "com.example.schema.vocab"
            generateInterfaces = false
            generateWrappers = false
            generateVocabulary = true
            vocabularyName = "SCHEMA"
            vocabularyNamespace = "https://schema.org/"
            vocabularyPrefix = "schema"
            outputDirectory = "build/generated/sources/schema-vocab"
        }

        // FOAF vocabulary
        create("foaf") {
            shaclPath = "ontologies/foaf.shacl.ttl"
            contextPath = "ontologies/foaf.context.jsonld"
            vocabularyPackage = "com.example.foaf.vocab"
            generateInterfaces = false
            generateWrappers = false
            generateVocabulary = true
            vocabularyName = "FOAF"
            vocabularyNamespace = "http://xmlns.com/foaf/0.1/"
            vocabularyPrefix = "foaf"
            outputDirectory = "build/generated/sources/foaf-vocab"
        }
    }
}

// Add generated sources to source sets
sourceSets {
    main {
        kotlin {
            srcDir("build/generated/sources/dcat-vocab")
            srcDir("build/generated/sources/schema-vocab")
            srcDir("build/generated/sources/foaf-vocab")
        }
    }
}

tasks.compileKotlin {
    dependsOn("generateOntology")
}
