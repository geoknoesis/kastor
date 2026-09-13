plugins {
    kotlin("jvm") version "2.4.20"
    application
}

repositories {
    maven { url = uri("../build/release-repository") }
    mavenCentral()
}
kotlin { jvmToolchain(21) }
// Defaults to the single version in the root gradle.properties; override with -PkastorVersion=X.Y.Z.
val kastorVersion = providers.gradleProperty("kastorVersion").getOrElse(
    java.util.Properties().apply { rootDir.resolve("../gradle.properties").reader().use(::load) }.getProperty("version")
)
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
