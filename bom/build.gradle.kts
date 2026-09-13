plugins {
    `java-platform`
    `maven-publish`
}

// The published consumer BOM aligns Kastor modules ONLY. Third-party alignment platforms and
// security floors used by this build live in the non-published :build-platform project so
// they are never forced onto consumers (e.g. Netty 4.1 users being upgraded to 4.2).

// Build-time artifacts intentionally excluded: consumers add the KSP processor via `ksp(...)`
// and apply the Gradle plugin via `plugins {}`. Constraining them here would drag the Gradle
// API / tooling jars onto user classpaths.
val buildTimeOnly = setOf(":kastor-gen:processor", ":kastor-gen:gradle-plugin")

val bomProjects = listOf(
    ":rdf:core",
    ":rdf:sparql-contract",
    ":rdf:sparql-lang",
    ":rdf:shacl-dsl",
    ":rdf:jena",
    ":rdf:jena-reasoning",
    ":rdf:rdf4j",
    ":rdf:rdf4j-reasoning",
    ":rdf:sparql",
    ":rdf:reasoning",
    ":rdf:reasoning-hermit",
    ":rdf:shacl-validation",
    ":rdf:testkit",
    ":rdf:cli",
    ":kastor-gen:runtime",
    ":kastor-gen:validation-jena",
    ":kastor-gen:validation-rdf4j",
    ":tools:onto-quality",
    ":tools:onto-quality-metrics",
    ":tools:onto-quality-embed",
    ":tools:onto-quality-llm-koog",
    ":tools:onto-quality-cli",
)

dependencies {
    constraints {
        bomProjects.forEach { api(project(it)) }
    }
}

// Every project that applies `maven-publish` must be in the BOM (or explicitly build-time only).
val publishedProjects = objects.setProperty(String::class.java)
gradle.projectsEvaluated {
    publishedProjects.set(
        rootProject.subprojects
            .filter { it != project && it.plugins.hasPlugin("maven-publish") }
            .map { it.path }
    )
}

val verifyBomCoverage = tasks.register("verifyBomCoverage") {
    group = "verification"
    description = "Fails when a published Kastor module is missing from (or stale in) kastor-bom."
    val declared = bomProjects.toSortedSet()
    val excluded = buildTimeOnly.toSortedSet()
    val published = publishedProjects
    inputs.property("declared", declared)
    inputs.property("excluded", excluded)
    inputs.property("published", published)
    val marker = layout.buildDirectory.file("verify-bom-coverage.txt")
    outputs.file(marker)
    doLast {
        val expected = published.get().toSortedSet() - excluded
        val missing = expected - declared
        val stale = declared - expected
        check(missing.isEmpty() && stale.isEmpty()) {
            buildString {
                append("kastor-bom is out of sync with published modules.")
                if (missing.isNotEmpty()) append(" Missing: $missing.")
                if (stale.isNotEmpty()) append(" Not published (remove from BOM): $stale.")
                append(" Edit bom/build.gradle.kts.")
            }
        }
        marker.get().asFile.writeText(declared.joinToString("\n", postfix = "\n"))
    }
}

tasks.named("check") { dependsOn(verifyBomCoverage) }

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
                        "versions for every published Kastor module."
                )
            }
        }
    }
}
