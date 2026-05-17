plugins {
  kotlin("jvm")
  application
}

application {
  mainClass.set("com.geoknoesis.kastor.benchmarks.shacl.era.ShaclEraCliKt")
}

dependencies {
  implementation(project(":rdf:core"))
  implementation(project(":rdf:jena"))
  implementation(project(":rdf:shacl-validation"))
}
