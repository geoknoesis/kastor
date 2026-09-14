// Imported explicitly: inside a build script `java` resolves to the `java {}` extension, so a
// fully qualified `java.util.Properties` does not compile.
import java.util.Properties

plugins {
    kotlin("jvm") // version from settings.gradle.kts (../gradle/libs.versions.toml)
    application
}

repositories {
    maven { url = uri("../build/release-repository") }
    mavenCentral()
}
kotlin { jvmToolchain(21) }
// Defaults to the single version in the root gradle.properties; override with -PkastorVersion=X.Y.Z.
val kastorVersion = providers.gradleProperty("kastorVersion").getOrElse(
    Properties().apply { rootDir.resolve("../gradle.properties").reader().use { load(it) } }.getProperty("version")
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
