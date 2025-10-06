plugins {
  kotlin("jvm")
  `java-library`
}

dependencies {
  implementation(project(":rdf:core"))
  implementation(project(":rdf:jena"))
  implementation(project(":rdf:rdf4j"))
  implementation(project(":rdf:sparql"))
  implementation(project(":rdf:reasoning"))
  implementation(project(":rdf:shacl-validation"))
  
  testImplementation(project(":rdf:core"))
  testImplementation(project(":rdf:jena"))
  testImplementation(project(":rdf:rdf4j"))
  testImplementation(project(":rdf:sparql"))
  testImplementation(project(":rdf:reasoning"))
  testImplementation(project(":rdf:shacl-validation"))
  testImplementation(libs.junit.jupiter)
}

