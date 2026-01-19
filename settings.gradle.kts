pluginManagement {
  repositories { 
    gradlePluginPortal()
    mavenCentral()
    mavenLocal()  // For local plugin development
  }
}
dependencyResolutionManagement {
  repositories { mavenCentral(); mavenLocal() }
  // Gradle will automatically pick up gradle/libs.versions.toml as the default 'libs' catalog
}
rootProject.name = "kastor"
include(
  "bom",
  ":rdf:core", ":rdf:jena", ":rdf:rdf4j", ":rdf:sparql", ":rdf:reasoning", ":rdf:shacl-validation", ":rdf:examples",
  ":kastor-gen:runtime", ":kastor-gen:processor", ":kastor-gen:gradle-plugin", ":kastor-gen:validation-jena", ":kastor-gen:validation-rdf4j",
  ":examples:dcat-us"
)


