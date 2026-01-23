plugins {
  id("org.jetbrains.kotlin.jvm") version "2.1.0" apply false
  id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
  id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
}

allprojects {
  group = "com.geoknoesis.kastor"
  version = "0.1.0"
}

subprojects {
  apply(plugin = "org.jetbrains.kotlin.jvm")
  apply(plugin = "java-library")
  
  // Apply KSP plugin to projects that need it (but not the processor itself or runtime)
  if ((project.path.startsWith(":kastor-gen:") && project.path != ":kastor-gen:processor" && project.path != ":kastor-gen:runtime") || project.path.startsWith(":samples:") || project.path.startsWith(":examples:")) {
    apply(plugin = "com.google.devtools.ksp")
  }

  extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
    jvmToolchain(17)
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

  dependencies {
    add("testImplementation", "org.jetbrains.kotlin:kotlin-test:2.1.0")
    add("testImplementation", "org.junit.jupiter:junit-jupiter:5.10.3")
    add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher:1.10.3")
    add("implementation", "org.slf4j:slf4j-api:2.0.13")
    
    // Add KSP dependencies for projects that use it (but not the processor itself or runtime)
    if ((project.path.startsWith(":kastor-gen:") && project.path != ":kastor-gen:processor" && project.path != ":kastor-gen:runtime") || project.path.startsWith(":samples:") || project.path.startsWith(":examples:")) {
      add("ksp", project(":kastor-gen:processor"))
    }
  }

  tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
    useJUnitPlatform()
  }

  // Sources JAR
  val sourcesJar = tasks.register<org.gradle.jvm.tasks.Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    val sourceSets = project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
    from(sourceSets.getByName("main").allSource)
  }

  // Javadoc JAR (may be empty for pure Kotlin projects)
  val javadocJar = tasks.register<org.gradle.jvm.tasks.Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
    val javadoc = tasks.findByName("javadoc") as? org.gradle.api.tasks.javadoc.Javadoc
    if (javadoc != null) {
      dependsOn(javadoc)
      from(javadoc.destinationDir)
    }
  }
}

// Collect all artifacts (jar, sourcesJar, javadocJar) into build/artifacts
val collectArtifacts = tasks.register<org.gradle.api.tasks.Copy>("collectArtifacts") {
  duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
  subprojects.forEach { p ->
    dependsOn("${p.path}:assemble")
    dependsOn("${p.path}:sourcesJar")
    dependsOn("${p.path}:javadocJar")
    from(p.layout.buildDirectory.dir("libs"))
  }
  into(layout.buildDirectory.dir("artifacts"))
}

// Root aggregate build task
tasks.register("build") {
  dependsOn(collectArtifacts)
}


