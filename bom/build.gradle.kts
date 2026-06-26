plugins {
    `java-platform`
    `maven-publish`
}

// `java-platform` modules can only declare dependency constraints, not actual
// implementation/api dependencies. The default rejection is sometimes too strict
// for clients consuming the BOM, so we relax it explicitly.
javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        api(project(":rdf:core"))
        api(project(":rdf:sparql-contract"))
        api(project(":rdf:sparql-lang"))
        api(project(":rdf:shacl-dsl"))
        api(project(":rdf:jena"))
        api(project(":rdf:jena-reasoning"))
        api(project(":rdf:rdf4j"))
        api(project(":rdf:rdf4j-reasoning"))
        api(project(":rdf:sparql"))
        api(project(":rdf:reasoning-hermit"))
        api(project(":rdf:shacl-validation"))
        api(project(":rdf:testkit"))
        api(project(":rdf:cli"))

        api(project(":kastor-gen:runtime"))
        // NOTE: :kastor-gen:processor (a KSP annotation processor) and
        // :kastor-gen:gradle-plugin (a Gradle plugin) are intentionally excluded.
        // They are build-time artifacts — consumers add the processor via `ksp(...)`
        // and apply the plugin via `plugins {}`. Constraining them in a consumer BOM
        // would drag the Gradle API / tooling jars onto user classpaths.
        api(project(":kastor-gen:validation-jena"))
        api(project(":kastor-gen:validation-rdf4j"))
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["javaPlatform"])

            groupId = project.group.toString()
            artifactId = "kastor-bom"
            version = project.version.toString()

            pom {
                name.set("Kastor BOM")
                description.set(
                    "Bill-of-materials (Gradle platform) that pins compatible " +
                        "versions for every Kastor module."
                )
            }
        }
    }
}
