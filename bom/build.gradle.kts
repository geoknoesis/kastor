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
    api(platform("com.fasterxml.jackson:jackson-bom:${libs.versions.jackson.get()}"))
    api(platform("io.netty:netty-bom:4.2.18.Final"))
    api(platform("io.opentelemetry:opentelemetry-bom:1.66.0"))
    constraints {
        // Security floors are published so independent consumers receive them too.
        api("com.google.guava:guava:33.7.1-jre")
        api("commons-beanutils:commons-beanutils:1.11.0")
        api("org.apache.httpcomponents.client5:httpclient5:5.6.4")
        api("org.apache.httpcomponents.core5:httpcore5:5.4.3")
        api("org.apache.httpcomponents.core5:httpcore5-h2:5.4.3")
        api("org.apache.httpcomponents:httpclient:4.5.14")
        api("org.apache.james:apache-mime4j-core:0.8.15")
        api("org.apache.thrift:libthrift:0.24.0")
        api("org.jsoup:jsoup:${libs.versions.jsoup.get()}")
        api("org.apache.commons:commons-lang3:${libs.versions.commonsLang3.get()}")
        api("at.yawk.lz4:lz4-java:1.11.3")
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
        api(project(":rdf:reasoning"))
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
