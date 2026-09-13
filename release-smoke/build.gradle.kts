plugins {
    kotlin("jvm") version "2.4.20"
    application
}

repositories {
    maven { url = uri("../build/release-repository") }
    mavenCentral()
}
kotlin { jvmToolchain(21) }
val kastorVersion = providers.gradleProperty("kastorVersion").getOrElse("0.2.1")
dependencies {
    implementation(platform("com.geoknoesis.kastor:kastor-bom:$kastorVersion"))
    implementation("com.geoknoesis.kastor:rdf-core")
    implementation("com.geoknoesis.kastor:rdf-jena")
    implementation("com.geoknoesis.kastor:rdf-rdf4j")
    implementation("com.geoknoesis.kastor:kastor-gen-runtime")
    implementation("com.geoknoesis.kastor:kastor-gen-validation-jena")
    implementation("com.geoknoesis.kastor:kastor-gen-validation-rdf4j")
}
application { mainClass = "SmokeKt" }
