plugins {
  application
}

application {
  mainClass.set("com.geoknoesis.kastor.examples.workshop.WorkshopTutorialKt")
}

dependencies {
  implementation(project(":rdf:core"))
  implementation(project(":rdf:jena"))
  implementation(project(":kastor-gen:runtime"))
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(project(":rdf:testkit"))
}

tasks.register<JavaExec>("runHandRdf") {
  group = "examples"
  description = "Run Part A only (hand-built DCAT DSL + SPARQL)"
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("com.geoknoesis.kastor.examples.workshop.WorkshopHandRdfKt")
}

tasks.register<JavaExec>("runGenDemo") {
  group = "examples"
  description = "Run Part B only (@Rdf materialization after KSP)"
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("com.geoknoesis.kastor.examples.workshop.WorkshopGenDemoKt")
}
