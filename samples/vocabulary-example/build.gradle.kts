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

// Configure vocabulary generation
kastorGen {
    ontologies {
        // DCAT-US vocabulary
        create("dcat") {
            shaclPath.set("ontologies/dcat-us.shacl.ttl")
            contextPath.set("ontologies/dcat-us.context.jsonld")
            targetPackage.set("com.example.dcatus.vocab")
            generateInterfaces.set(false)
            generateWrappers.set(false)
            generateVocabulary.set(true)
            vocabularyName.set("DCAT")
            vocabularyNamespace.set("http://www.w3.org/ns/dcat#")
            vocabularyPrefix.set("dcat")
            outputDirectory.set("build/generated/sources/dcat-vocab")
        }
        
        // Schema.org vocabulary
        create("schema") {
            shaclPath.set("ontologies/schema.shacl.ttl")
            contextPath.set("ontologies/schema.context.jsonld")
            targetPackage.set("com.example.schema.vocab")
            generateInterfaces.set(false)
            generateWrappers.set(false)
            generateVocabulary.set(true)
            vocabularyName.set("SCHEMA")
            vocabularyNamespace.set("https://schema.org/")
            vocabularyPrefix.set("schema")
            outputDirectory.set("build/generated/sources/schema-vocab")
        }
        
        // FOAF vocabulary
        create("foaf") {
            shaclPath.set("ontologies/foaf.shacl.ttl")
            contextPath.set("ontologies/foaf.context.jsonld")
            targetPackage.set("com.example.foaf.vocab")
            generateInterfaces.set(false)
            generateWrappers.set(false)
            generateVocabulary.set(true)
            vocabularyName.set("FOAF")
            vocabularyNamespace.set("http://xmlns.com/foaf/0.1/")
            vocabularyPrefix.set("foaf")
            outputDirectory.set("build/generated/sources/foaf-vocab")
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
