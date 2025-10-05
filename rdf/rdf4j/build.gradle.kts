dependencies {
  api(project(":rdf:core"))
  implementation(project(":rdf:reasoning"))
  implementation(libs.rdf4j.repository.api)
  implementation(libs.rdf4j.repository.sail)
  implementation(libs.rdf4j.sail.memory)
  implementation(libs.rdf4j.sail.nativerdf)
  implementation(libs.rdf4j.queryrender)
  implementation(libs.rdf4j.runtime)
  implementation(libs.rdf4j.rio.jsonld)
  implementation(libs.rdf4j.rio.turtle)
  implementation(libs.rdf4j.rio.rdfxml)
  implementation(libs.rdf4j.rio.n3)
  implementation(libs.rdf4j.rio.trig)
  implementation(libs.rdf4j.rio.nquads)
}

