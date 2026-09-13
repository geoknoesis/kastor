plugins { kotlin("jvm") }
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
dependencies { implementation("com.google.devtools.ksp:symbol-processing-api:2.3.12") }
