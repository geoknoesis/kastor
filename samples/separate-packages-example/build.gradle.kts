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
    implementation("com.geoknoesis.kastor:rdf-jena:0.1.0")
}

// Configure separate packages for different generated components
ontomapper {
    ontologies {
        // DCAT-US with separate packages
        create("dcat") {
            shaclPath.set("ontologies/dcat-us.shacl.ttl")
            contextPath.set("ontologies/dcat-us.context.jsonld")
            
            // Separate packages for each component
            interfacePackage.set("com.example.dcatus.interfaces")
            wrapperPackage.set("com.example.dcatus.wrappers")
            vocabularyPackage.set("com.example.dcatus.vocab")
            
            // Generate all components
            generateInterfaces.set(true)
            generateWrappers.set(true)
            generateVocabulary.set(true)
            
            // Vocabulary configuration
            vocabularyName.set("DCAT")
            vocabularyNamespace.set("http://www.w3.org/ns/dcat#")
            vocabularyPrefix.set("dcat")
            
            outputDirectory.set("build/generated/sources/dcat")
        }
        
        // Schema.org with separate packages
        create("schema") {
            shaclPath.set("ontologies/schema.shacl.ttl")
            contextPath.set("ontologies/schema.context.jsonld")
            
            // Separate packages for each component
            interfacePackage.set("com.example.schema.interfaces")
            wrapperPackage.set("com.example.schema.wrappers")
            vocabularyPackage.set("com.example.schema.vocab")
            
            // Generate all components
            generateInterfaces.set(true)
            generateWrappers.set(true)
            generateVocabulary.set(true)
            
            // Vocabulary configuration
            vocabularyName.set("SCHEMA")
            vocabularyNamespace.set("https://schema.org/")
            vocabularyPrefix.set("schema")
            
            outputDirectory.set("build/generated/sources/schema")
        }
        
        // FOAF with separate packages
        create("foaf") {
            shaclPath.set("ontologies/foaf.shacl.ttl")
            contextPath.set("ontologies/foaf.context.jsonld")
            
            // Separate packages for each component
            interfacePackage.set("com.example.foaf.interfaces")
            wrapperPackage.set("com.example.foaf.wrappers")
            vocabularyPackage.set("com.example.foaf.vocab")
            
            // Generate all components
            generateInterfaces.set(true)
            generateWrappers.set(true)
            generateVocabulary.set(true)
            
            // Vocabulary configuration
            vocabularyName.set("FOAF")
            vocabularyNamespace.set("http://xmlns.com/foaf/0.1/")
            vocabularyPrefix.set("foaf")
            
            outputDirectory.set("build/generated/sources/foaf")
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
