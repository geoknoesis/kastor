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

// Configure multiple ontologies
ontomapper {
    ontologies {
        // DCAT-US ontology
        create("dcat") {
            shaclPath.set("ontologies/dcat-us.shacl.ttl")
            contextPath.set("ontologies/dcat-us.context.jsonld")
            targetPackage.set("com.example.dcatus.generated")
            outputDirectory.set("build/generated/sources/dcat")
        }
        
        // Schema.org ontology
        create("schema") {
            shaclPath.set("ontologies/schema.shacl.ttl")
            contextPath.set("ontologies/schema.context.jsonld")
            targetPackage.set("com.example.schema.generated")
            outputDirectory.set("build/generated/sources/schema")
        }
        
        // FOAF ontology
        create("foaf") {
            shaclPath.set("ontologies/foaf.shacl.ttl")
            contextPath.set("ontologies/foaf.context.jsonld")
            targetPackage.set("com.example.foaf.generated")
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
