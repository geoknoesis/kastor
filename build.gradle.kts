plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
  group = "com.geoknoesis.kastor"
  version = "0.2.0"
}

subprojects {
  // The `:bom` module is a Gradle platform; it must not apply Kotlin/java-library.
  val isBom = project.path == ":bom"

  if (!isBom) {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java-library")
  }

  // Apply KSP plugin to projects that need it (but not the processor itself or runtime).
  if ((project.path.startsWith(":kastor-gen:") && project.path != ":kastor-gen:processor" && project.path != ":kastor-gen:runtime") ||
      project.path.startsWith(":examples:")) {
    apply(plugin = "com.google.devtools.ksp")
  }

  if (!isBom) {
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
      jvmToolchain(17)
    }
  }

  // Place each module's build under root build/modules/<path>
  layout.buildDirectory.set(
    rootProject.layout.buildDirectory.dir(
      "modules/" + project.path.removePrefix(":").replace(":", "/")
    )
  )

  repositories {
    mavenCentral()
  }

  if (!isBom) {
    dependencies {
      add("testImplementation", rootProject.libs.kotlin.test)
      add("testImplementation", rootProject.libs.junit.jupiter)
      add("testRuntimeOnly", rootProject.libs.junit.platform.launcher)
      add("implementation", rootProject.libs.slf4j.api)

      // Add KSP dependencies for projects that use it (but not the processor itself or runtime).
      // Exclude simple hello-world and hello-codegen examples (they can enable KSP manually if needed).
      if ((project.path.startsWith(":kastor-gen:") && project.path != ":kastor-gen:processor" && project.path != ":kastor-gen:runtime") ||
          (project.path.startsWith(":examples:") && project.path != ":examples:hello-world" && project.path != ":examples:hello-codegen")) {
        add("ksp", project(":kastor-gen:processor"))
      }
    }

    tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
      useJUnitPlatform()
    }

    // Sources JAR
    tasks.register<org.gradle.jvm.tasks.Jar>("sourcesJar") {
      archiveClassifier.set("sources")
      val sourceSets = project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
      from(sourceSets.getByName("main").allSource)
    }

    // Javadoc JAR (may be empty for pure Kotlin projects)
    tasks.register<org.gradle.jvm.tasks.Jar>("javadocJar") {
      archiveClassifier.set("javadoc")
      val javadoc = tasks.findByName("javadoc") as? org.gradle.api.tasks.javadoc.Javadoc
      if (javadoc != null) {
        dependsOn(javadoc)
        from(javadoc.destinationDir)
      }
    }
  }
}

// Collect all artifacts (jar, sourcesJar, javadocJar) into build/artifacts
val collectArtifacts = tasks.register<org.gradle.api.tasks.Copy>("collectArtifacts") {
  duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
  subprojects.forEach { p ->
    dependsOn("${p.path}:assemble")
    // The :bom module is a Gradle platform and has no sources/javadoc jars.
    if (p.path != ":bom") {
      dependsOn("${p.path}:sourcesJar")
      dependsOn("${p.path}:javadocJar")
    }
    from(p.layout.buildDirectory.dir("libs"))
  }
  into(layout.buildDirectory.dir("artifacts"))
}

// Root aggregate build task
tasks.register("build") {
  dependsOn(collectArtifacts)
}

// Hello World example task
tasks.register("helloWorld") {
  group = "examples"
  description = "Run the hello-world example"
  dependsOn(":examples:hello-world:run")
}

// Hello Codegen example task
tasks.register("helloCodegen") {
  group = "examples"
  description = "Run the hello-codegen example"
  dependsOn(":examples:hello-codegen:run")
}


