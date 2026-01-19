// DCAT-US OntoMapper Example Configuration
// This example demonstrates OntoMapper code generation from DCAT-US 3.0 SHACL shapes

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.geoknoesis.ontomapper") version "0.1.0"
}

dependencies {
    // Core Kastor dependencies - using project dependencies
    implementation(project(":rdf:core"))
    implementation(project(":ontomapper:runtime"))
}

// Configure OntoMapper plugin
ontomapper {
    ontology("dcat-us") {
        shaclPath = "src/main/resources/dcat-us_3.0_shacl_shapes.ttl"
        contextPath = "src/main/resources/dcat-us_3.0_context.jsonld"
        interfacePackage = "com.geoknoesis.kastor.examples.dcat.generated"
        generateInterfaces = true
        generateWrappers = false  // Only generate interfaces as requested
        outputDirectory = "build/generated/sources/ontomapper"
        generateVocabulary = false
    }
}


// Add generated sources to source sets
sourceSets {
    main {
        kotlin {
            srcDir("build/generated/sources/ontomapper")
        }
    }
}

// Ensure proper task dependencies
tasks.compileKotlin {
    dependsOn("generateOntology")
}

// Fix KSP task dependency if KSP is applied
afterEvaluate {
    tasks.findByName("kspKotlin")?.let { kspTask ->
        kspTask.dependsOn("generateOntology")
    }
}

// Task to generate OntoMapper interfaces manually
tasks.register<JavaExec>("generateOntoMapperCode") {
    dependsOn("compileKotlin")
    mainClass.set("com.geoknoesis.kastor.examples.dcat.DCAT_US_Interface_GeneratorKt")
    classpath = sourceSets.main.get().runtimeClasspath
    description = "Generate OntoMapper interfaces from DCAT-US 3.0 SHACL shapes"
    group = "ontomapper"
}

// Task to run the example
tasks.register<JavaExec>("run") {
    dependsOn("generateOntoMapperCode", "compileKotlin")
    mainClass.set("com.geoknoesis.kastor.examples.dcat.DCAT_US_ExampleKt")
    classpath = sourceSets.main.get().runtimeClasspath
    description = "Run the DCAT-US OntoMapper example"
    group = "examples"
}

// Task to run the manual example
tasks.register<JavaExec>("runManualExample") {
    dependsOn("compileKotlin")
    mainClass.set("com.geoknoesis.kastor.examples.dcat.DCAT_US_Manual_ExampleKt")
    classpath = sourceSets.main.get().runtimeClasspath
    description = "Run the DCAT-US manual example showing what OntoMapper would generate"
    group = "examples"
}

// Task to run the generated interfaces example
tasks.register<JavaExec>("runGeneratedExample") {
    dependsOn("generateOntoMapperCode", "compileKotlin")
    mainClass.set("com.geoknoesis.kastor.examples.dcat.DCAT_US_Generated_ExampleKt")
    classpath = sourceSets.main.get().runtimeClasspath
    description = "Run the DCAT-US example using generated interfaces"
    group = "examples"
}