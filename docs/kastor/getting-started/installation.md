# 🏗️ Installation Guide

This guide will walk you through installing and setting up Kastor RDF in your Kotlin project.

## 📋 Table of Contents

- [Prerequisites](#-prerequisites)
- [Quick Installation](#-quick-installation)
- [Build System Setup](#-build-system-setup)
- [IDE Configuration](#-ide-configuration)
- [Backend Selection](#-backend-selection)
- [Verification](#-verification)
- [Troubleshooting](#-troubleshooting)

## 🎯 Prerequisites

Before installing Kastor RDF, ensure you have:

### Required Software

- **Kotlin 1.9+** - [Download from kotlinlang.org](https://kotlinlang.org/docs/command-line.html)
- **Java 11+** - [Download from oracle.com](https://www.oracle.com/java/technologies/downloads/) or [OpenJDK](https://adoptium.net/)
- **Build System** - Gradle (recommended) or Maven

### Optional Software

- **IDE** - IntelliJ IDEA, Eclipse, or VS Code with Kotlin support
- **Git** - For version control

### Verify Your Setup

```bash
# Check Kotlin version
kotlinc -version

# Check Java version
java -version

# Check Gradle version (if using Gradle)
gradle --version
```

## 🚀 Quick Installation

### 1. Add Dependencies

Add the following to your `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "1.9.0"
}

dependencies {
    // Core API
    implementation("com.geoknoesis:kastor-rdf-core:0.1.0")
    
    // Choose your backend (or both)
    implementation("com.geoknoesis:kastor-rdf-jena:0.1.0")    // Apache Jena backend
    implementation("com.geoknoesis:kastor-rdf-rdf4j:0.1.0")   // Eclipse RDF4J backend
}

repositories {
    mavenCentral()
}
```

### 2. Import the API

```kotlin
import com.geoknoesis.kastor.rdf.*
```

### 3. Test Installation

Create a simple test file:

```kotlin
fun main() {
    val repo = Rdf.memory()
    println("✅ Kastor RDF installed successfully!")
    repo.close()
}
```

## 🏗️ Build System Setup

### Gradle (Recommended)

#### Basic Setup

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "1.9.0"
    application
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Core API
    implementation("com.geoknoesis:kastor-rdf-core:0.1.0")
    
    // Backends
    implementation("com.geoknoesis:kastor-rdf-jena:0.1.0")
    implementation("com.geoknoesis:kastor-rdf-rdf4j:0.1.0")
    
    // Testing
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("MainKt")
}

tasks.test {
    useJUnitPlatform()
}
```

#### Advanced Gradle Configuration

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "1.9.0"
    application
    kotlin("plugin.serialization") version "1.9.0"
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
    // Add if using snapshot versions
    maven { url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots") }
}

dependencies {
    // Core API
    implementation("com.geoknoesis:kastor-rdf-core:0.1.0")
    
    // Backends
    implementation("com.geoknoesis:kastor-rdf-jena:0.1.0")
    implementation("com.geoknoesis:kastor-rdf-rdf4j:0.1.0")
    
    // Additional dependencies
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.0")
    
    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.0")
    testImplementation("io.mockk:mockk:1.13.0")
}

application {
    mainClass.set("MainKt")
}

tasks.test {
    useJUnitPlatform()
}

// Kotlin configuration
kotlin {
    jvmToolchain(11)
}

// Java configuration
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}
```

### Maven

#### Basic Setup

```xml
<!-- pom.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>kastor-rdf-example</artifactId>
    <version>1.0.0</version>

    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
        <kotlin.version>1.9.0</kotlin.version>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- Core API -->
        <dependency>
            <groupId>com.geoknoesis</groupId>
            <artifactId>kastor-rdf-core</artifactId>
            <version>0.1.0</version>
        </dependency>
        
        <!-- Jena Backend -->
        <dependency>
            <groupId>com.geoknoesis</groupId>
            <artifactId>kastor-rdf-jena</artifactId>
            <version>0.1.0</version>
        </dependency>
        
        <!-- RDF4J Backend -->
        <dependency>
            <groupId>com.geoknoesis</groupId>
            <artifactId>kastor-rdf-rdf4j</artifactId>
            <version>0.1.0</version>
        </dependency>
        
        <!-- Testing -->
        <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-test</artifactId>
            <version>${kotlin.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <sourceDirectory>${project.basedir}/src/main/kotlin</sourceDirectory>
        <testSourceDirectory>${project.basedir}/src/test/kotlin</testSourceDirectory>
        
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
                <executions>
                    <execution>
                        <id>compile</id>
                        <phase>compile</phase>
                        <goals>
                            <goal>compile</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            
            <plugin>
                <groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-maven-plugin</artifactId>
                <version>${kotlin.version}</version>
                <executions>
                    <execution>
                        <id>compile</id>
                        <phase>compile</phase>
                        <goals>
                            <goal>compile</goal>
                        </goals>
                    </execution>
                    <execution>
                        <id>test-compile</id>
                        <phase>test-compile</phase>
                        <goals>
                            <goal>test-compile</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

## 💻 IDE Configuration

### IntelliJ IDEA

#### Setup Steps

1. **Open Project**
   - Open your project in IntelliJ IDEA
   - If using Gradle, let IntelliJ import the Gradle project

2. **Configure Kotlin**
   - Go to `File → Settings → Languages & Frameworks → Kotlin`
   - Ensure Kotlin is properly configured

3. **Import Dependencies**
   - Wait for Gradle/Maven to sync
   - Verify dependencies are resolved in `External Libraries`

4. **Create Run Configuration**
   - Go to `Run → Edit Configurations`
   - Add new `Kotlin` configuration
   - Set main class to your main function

#### Recommended Plugins

- **Kotlin** (built-in)
- **Gradle** (built-in)
- **Maven** (built-in)
- **Rainbow Brackets** (optional)
- **String Manipulation** (optional)

### Eclipse

#### Setup Steps

1. **Install Kotlin Plugin**
   - Go to `Help → Eclipse Marketplace`
   - Search for "Kotlin" and install the plugin

2. **Import Project**
   - `File → Import → Existing Gradle Project` or `Existing Maven Project`
   - Select your project directory

3. **Configure Build Path**
   - Right-click project → `Properties → Java Build Path`
   - Ensure Kotlin source folders are included

### VS Code

#### Setup Steps

1. **Install Extensions**
   - Kotlin Language (by mathiasfrohlich)
   - Kotlin Extension Pack
   - Gradle for Java

2. **Configure Settings**
   ```json
   {
     "kotlin.languageServer.enabled": true,
     "kotlin.debugAdapter.enabled": true,
     "kotlin.trace.server": "messages"
   }
   ```

## 🎯 Backend Selection

Kastor RDF supports multiple backends. Choose based on your needs:

### Apache Jena Backend

**Best for**: General-purpose RDF applications, complex reasoning

```kotlin
dependencies {
    implementation("com.geoknoesis:kastor-rdf-jena:0.1.0")
}
```

**Features**:
- In-memory and TDB2 storage
- RDFS and OWL reasoning
- Advanced query optimization
- Large dataset support

### Eclipse RDF4J Backend

**Best for**: Enterprise applications, high performance

```kotlin
dependencies {
    implementation("com.geoknoesis:kastor-rdf-rdf4j:0.1.0")
}
```

**Features**:
- In-memory and native storage
- Excellent performance
- Enterprise features
- SPARQL endpoint support

### Using Multiple Backends

You can include both backends and choose at runtime:

```kotlin
dependencies {
    implementation("com.geoknoesis:kastor-rdf-jena:0.1.0")
    implementation("com.geoknoesis:kastor-rdf-rdf4j:0.1.0")
}
```

```kotlin
// Choose backend at runtime
val jenaRepo = Rdf.factory { 
    providerId = "jena"
    variantId = "memory"
}
val rdf4jRepo = Rdf.factory { 
    providerId = "rdf4j"
    variantId = "memory"
}
```

## ✅ Verification

### 1. Basic Test

Create a simple test to verify installation:

```kotlin
import com.geoknoesis.kastor.rdf.*

fun main() {
    println("🚀 Testing Kastor RDF Installation...")
    
    try {
        // Test repository creation
        val repo = Rdf.memory()
        println("✅ Repository created successfully")
        
        // Test data addition
        repo.add {
            val person = iri("http://example.org/person/test")
            person["http://example.org/person/name"] = "Test User"
        }
        println("✅ Data added successfully")
        
        // Test query
        val results = repo.select(SparqlSelectQuery("""
            SELECT ?name WHERE { 
                ?person <http://example.org/person/name> ?name 
            }
        """))
        
        val count = results.count()
        println("✅ Query executed successfully: $count results")
        
        // Test cleanup
        repo.close()
        println("✅ Repository closed successfully")
        
        println("\n🎉 Kastor RDF is working perfectly!")
        
    } catch (e: Exception) {
        println("❌ Installation test failed: ${e.message}")
        e.printStackTrace()
    }
}
```

### 2. Backend Test

Test specific backends:

```kotlin
fun testBackends() {
    println("🧪 Testing Backends...")
    
    // Test Jena
    try {
        val jenaRepo = Rdf.factory { 
            providerId = "jena"
            variantId = "memory"
        }
        jenaRepo.add {
            val person = iri("http://example.org/person/jena")
            person["http://example.org/person/name"] = "Jena User"
        }
        println("✅ Jena backend working")
        jenaRepo.close()
    } catch (e: Exception) {
        println("❌ Jena backend failed: ${e.message}")
    }
    
    // Test RDF4J
    try {
        val rdf4jRepo = Rdf.factory { 
            providerId = "rdf4j"
            variantId = "memory"
        }
        rdf4jRepo.add {
            val person = iri("http://example.org/person/rdf4j")
            person["http://example.org/person/name"] = "RDF4J User"
        }
        println("✅ RDF4J backend working")
        rdf4jRepo.close()
    } catch (e: Exception) {
        println("❌ RDF4J backend failed: ${e.message}")
    }
}
```

### 3. Performance Test

Test performance with larger datasets:

```kotlin
fun performanceTest() {
    println("⚡ Performance Test...")
    
    val repo = Rdf.memory()
    val startTime = System.currentTimeMillis()
    
    // Add 1000 triples
    repo.add {
        for (i in 1..1000) {
            val person = iri("http://example.org/person/person$i")
            person["http://example.org/person/name"] = "Person $i"
            person["http://example.org/person/age"] = 20 + (i % 50)
        }
    }
    
    val addTime = System.currentTimeMillis() - startTime
    println("✅ Added 1000 triples in ${addTime}ms")
    
    // Query test
    val queryStart = System.currentTimeMillis()
    val results = repo.select(SparqlSelectQuery("""
        SELECT ?name ?age WHERE { 
            ?person <http://example.org/person/name> ?name ;
                    <http://example.org/person/age> ?age 
        }
    """))
    
    val queryTime = System.currentTimeMillis() - queryStart
    println("✅ Queried ${results.count()} results in ${queryTime}ms")
    
    repo.close()
}
```

## 🔧 Troubleshooting

### Common Issues

#### 1. Dependency Resolution Failures

**Problem**: Cannot resolve dependencies

**Solution**:
```kotlin
// Check repositories
repositories {
    mavenCentral()
    // Add if needed
    maven { url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots") }
}

// Check version compatibility
dependencies {
    implementation("com.geoknoesis:kastor-rdf-core:0.1.0")
    // Ensure all modules use same version
}
```

#### 2. Kotlin Version Conflicts

**Problem**: Kotlin version mismatch

**Solution**:
```kotlin
// Use consistent Kotlin version
plugins {
    kotlin("jvm") version "1.9.0"
}

dependencies {
    // Ensure all Kotlin dependencies use same version
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.0")
}
```

#### 3. Java Version Issues

**Problem**: Java version incompatibility

**Solution**:
```kotlin
// Set Java toolchain
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

// Or in Maven
<properties>
    <maven.compiler.source>11</maven.compiler.source>
    <maven.compiler.target>11</maven.compiler.target>
</properties>
```

#### 4. IDE Issues

**Problem**: IDE doesn't recognize Kotlin

**Solution**:
- Restart IDE
- Invalidate caches and restart
- Re-import project
- Check Kotlin plugin installation

#### 5. Runtime Errors

**Problem**: `ClassNotFoundException` or similar

**Solution**:
- Check that all dependencies are included
- Ensure correct backend is selected
- Verify classpath includes all required JARs

### Getting Help

If you encounter issues:

1. **Check the logs** for detailed error messages
2. **Verify prerequisites** are met
3. **Try the verification tests** above
4. **Check GitHub Issues** for known problems
5. **Ask for help** in GitHub Discussions

## 🎯 Next Steps

After successful installation:

1. **[Quick Start Guide](quick-start.md)** - Get up and running
2. **[RDF Fundamentals](rdf-fundamentals.md)** - Learn RDF basics
3. **[Examples](examples.md)** - Explore code examples
4. **[Super Sleek API Guide](super-sleek-api-guide.md)** - Advanced features

## 🤝 Need Help?

- **Documentation**: [docs/](docs/)
- **Examples**: [examples/](examples/)
- **Issues**: [GitHub Issues](https://github.com/geoknoesis/kastor-rdf/issues)
- **Discussions**: [GitHub Discussions](https://github.com/geoknoesis/kastor-rdf/discussions)

---

**🎉 Congratulations! You've successfully installed Kastor RDF and are ready to build amazing RDF applications!**



