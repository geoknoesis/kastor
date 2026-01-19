plugins {
  id("maven-publish")
}

dependencies {
  // Core has no extra deps beyond those declared at root
  // Reasoning functionality is now in separate module
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      
      groupId = project.group.toString()
      artifactId = project.name
      version = project.version.toString()
    }
  }
}

