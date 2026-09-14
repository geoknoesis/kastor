plugins { kotlin("jvm") }
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
// Same KSP version as the consumer build (settings.gradle.kts: -PkspVersion or the root version catalog).
val kspVersion = gradle.extra["kspVersion"] as String
dependencies { implementation("com.google.devtools.ksp:symbol-processing-api:$kspVersion") }
