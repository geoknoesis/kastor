# Kastor Examples

This directory contains comprehensive examples demonstrating various features and use cases of the Kastor framework.

## 🎯 Available Examples

### 1. **DCAT-US 3.0 Kastor Gen Example** (`dcat-us/`)
Demonstrates how to use Kastor Gen to generate interfaces, wrappers, and vocabulary from DCAT-US 3.0 SHACL shapes.

**Features:**
- Automatic code generation from SHACL shapes
- Type-safe DCAT-US interfaces and data classes
- Builder patterns and DSL support
- SHACL validation integration
- Government data catalog compliance

**Key Files:**
- `DCAT_US_Example.kt` - Main example implementation
- `build.gradle.kts` - Kastor Gen configuration
- Custom Mustache templates for code generation

**Usage:**
```bash
cd examples/dcat-us
./gradlew generateKastorGenCode
./gradlew run
```

## 🚀 Getting Started

### Prerequisites
- Java 11 or higher
- Gradle 7.0 or higher
- Kotlin 1.9.20 or higher

### Running Examples

1. **Clone the repository**:
   ```bash
   git clone https://github.com/geoknoesis/kastor.git
   cd kastor
   ```

2. **Build the project**:
   ```bash
   ./gradlew build
   ```

3. **Run a specific example** (from project root):
   ```bash
   ./gradlew :examples:[example-name]:run
   ```

4. **Or run from example directory**:
   ```bash
   cd examples/[example-name]
   ../../gradlew run
   ```

## 📚 Example Categories

### **Kastor Gen Examples**
Examples demonstrating the Kastor Gen code generation capabilities:

- **[DCAT-US 3.0](dcat-us/)** - Government data catalog vocabulary
- More examples coming soon...

### **RDF API Examples**
Examples showing core RDF API usage:

- More examples coming soon...

### **SPARQL Examples**
Examples demonstrating SPARQL 1.2 features:

- More examples coming soon...

### **SHACL Validation Examples**
Examples showing SHACL validation integration:

- More examples coming soon...

## 🔧 Creating New Examples

To create a new example:

1. **Create a new directory**:
   ```bash
   mkdir examples/my-example
   ```

2. **Add to root settings.gradle.kts**:
   ```kotlin
   include(":examples:my-example")
   ```

3. **Set up the build file**:
   ```kotlin
   // examples/my-example/build.gradle.kts
   plugins {
      id("com.google.devtools.ksp") // If using Kastor Gen
   }
   
   dependencies {
       implementation(project(":rdf:core"))
       implementation(project(":kastor-gen:runtime"))
       ksp(project(":kastor-gen:processor")) // If using Kastor Gen
   }
   ```

3. **Create the example code**:
   ```kotlin
   // src/main/kotlin/MyExample.kt
   fun main() {
       // Your example code here
   }
   ```

4. **Add documentation**:
   - Create a `README.md` explaining the example
   - Include usage instructions and key features
   - Add links to related documentation

## 📖 Example Guidelines

### **Code Quality**
- Follow Kotlin coding conventions
- Include comprehensive comments
- Use meaningful variable and function names
- Include error handling where appropriate

### **Documentation**
- Provide clear README files
- Include usage examples
- Explain the purpose and benefits
- Link to related documentation

### **Testing**
- Include unit tests where appropriate
- Test edge cases and error conditions
- Ensure examples work with the latest framework version

### **Dependencies**
- Use the latest stable versions
- Minimize external dependencies
- Document any required setup

## 🤝 Contributing Examples

We welcome contributions of new examples! To contribute:

1. **Fork the repository**
2. **Create your example** following the guidelines above
3. **Test thoroughly** to ensure it works correctly
4. **Submit a pull request** with a clear description

### **Example Ideas**
- Kastor Gen with different vocabularies (Schema.org, Dublin Core, etc.)
- Complex SPARQL query examples
- SHACL validation scenarios
- Integration with popular RDF stores
- Performance benchmarking examples
- Real-world use cases and applications

## 📞 Support

For questions about examples or the Kastor framework:

- **Email**: stephanef@geoknoesis.com
- **Issues**: GitHub Issues
- **Documentation**: [Kastor Docs](https://docs.kastor.org)

## 📄 License

All examples are provided under the same license as the Kastor framework.

---

*Examples are developed by [GeoKnoesis LLC](https://geoknoesis.com) and maintained by Stephane Fellah.*
