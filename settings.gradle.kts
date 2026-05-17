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
  ":rdf:core", ":rdf:sparql-contract", ":rdf:sparql-lang", ":rdf:shacl-dsl", ":rdf:jena", ":rdf:jena-reasoning", ":rdf:rdf4j", ":rdf:rdf4j-reasoning", ":rdf:sparql", ":rdf:reasoning", ":rdf:reasoning-hermit", ":rdf:shacl-validation", ":rdf:testkit", ":rdf:cli", ":rdf:examples",
  ":rdf:conformance",
  ":kastor-gen:runtime", ":kastor-gen:processor", ":kastor-gen:gradle-plugin", ":kastor-gen:validation-jena", ":kastor-gen:validation-rdf4j",
  ":examples:dcat-us",
  ":examples:dcat-catalog-workshop",
  ":examples:hello-world",
  ":examples:hello-codegen",
  ":tools:onto-quality",
  ":tools:onto-quality-metrics",
  ":tools:onto-quality-embed",
  ":tools:onto-quality-llm-koog",
  ":tools:onto-quality-cli",
  ":benchmarks:shacl",
  ":benchmarks:shacl-era-cli",
)

// Physical grouping under domain folders; Gradle project paths (:rdf:*, :tools:*, …) are unchanged.
project(":rdf:sparql-contract").projectDir = file("rdf/sparql/contract")
project(":rdf:sparql-lang").projectDir = file("rdf/sparql/lang")
project(":rdf:sparql").projectDir = file("rdf/sparql/endpoint")
project(":rdf:jena").projectDir = file("rdf/providers/jena")
project(":rdf:jena-reasoning").projectDir = file("rdf/providers/jena-reasoning")
project(":rdf:rdf4j").projectDir = file("rdf/providers/rdf4j")
project(":rdf:rdf4j-reasoning").projectDir = file("rdf/providers/rdf4j-reasoning")
project(":rdf:reasoning").projectDir = file("rdf/reasoning/facade")
project(":rdf:reasoning-hermit").projectDir = file("rdf/reasoning/hermit")
project(":rdf:shacl-validation").projectDir = file("rdf/shacl/validation")
project(":rdf:shacl-dsl").projectDir = file("rdf/shacl/dsl")
project(":tools:onto-quality").projectDir = file("tools/onto-quality/library")
project(":tools:onto-quality-cli").projectDir = file("tools/onto-quality/cli")
project(":tools:onto-quality-metrics").projectDir = file("tools/onto-quality/metrics")
project(":tools:onto-quality-embed").projectDir = file("tools/onto-quality/embed")
project(":tools:onto-quality-llm-koog").projectDir = file("tools/onto-quality/llm-koog")
project(":benchmarks:shacl").projectDir = file("benchmarks/shacl/jmh")
project(":benchmarks:shacl-era-cli").projectDir = file("benchmarks/shacl/era-cli")

