dependencies {
  api(project(":rdf:core"))
  implementation(libs.jena.libs) { isTransitive = true }
  implementation(libs.jena.arq)
  implementation(libs.jena.tdb2)
}

