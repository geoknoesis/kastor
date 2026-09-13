plugins {
    kotlin("jvm") version "2.4.20"
    id("com.google.devtools.ksp") version "2.3.12"
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
val kastorVersion = providers.gradleProperty("kastorVersion").getOrElse("0.2.1")
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
