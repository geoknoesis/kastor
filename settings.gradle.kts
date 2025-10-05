pluginManagement {
  repositories { gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
  repositories { mavenCentral(); mavenLocal() }
  // Gradle will automatically pick up gradle/libs.versions.toml as the default 'libs' catalog
}
rootProject.name = "kastor"
include(
  "bom",
  ":rdf:core", ":rdf:jena", ":rdf:rdf4j", ":rdf:sparql", ":rdf:reasoning", ":rdf:examples",
  ":mapper:core", ":mapper:processor",
  ":ontomapper:runtime", ":ontomapper:processor", ":ontomapper:gradle-plugin", ":ontomapper:validation-jena", ":ontomapper:validation-rdf4j",
  ":samples:dcat-us"
)


