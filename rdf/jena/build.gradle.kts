dependencies {
  api(project(":rdf:core"))
  implementation(project(":rdf:reasoning"))
  implementation(libs.jena.libs) { isTransitive = true }
  implementation(libs.jena.arq)
  implementation(libs.jena.tdb2)
}

