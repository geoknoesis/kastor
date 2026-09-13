plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
    id("com.geoknoesis.kastor.gen")
    application
}
repositories {
    exclusiveContent {
        forRepository { maven { url = uri(providers.gradleProperty("kastorRepository").getOrElse("../build/release-repository")) } }
        filter { includeGroupByRegex("com\\.geoknoesis\\.kastor(\\..*)?") }
    }
    mavenCentral()
}
kotlin { jvmToolchain(21) }
val kastorVersion = gradle.extra["kastorVersion"] as String
dependencies {
    implementation(platform("com.geoknoesis.kastor:kastor-bom:$kastorVersion"))
    implementation("com.geoknoesis.kastor:kastor-gen-runtime")
    ksp("com.geoknoesis.kastor:kastor-gen-processor:$kastorVersion")
    ksp(project(":late-processor"))
}
kastorGen {
    ontologies {
        create("fixture") {
            shaclPath = "shapes.ttl"
            contextPath = "context.json"
            interfacePackage = "fixture.domain"
            wrapperPackage = "fixture.wrappers"
        }
    }
}
application { mainClass.set("fixture.MainKt") }
