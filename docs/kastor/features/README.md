# Kastor Features

This section covers the advanced features and capabilities of the Kastor RDF framework.

## 🌟 Available Features

### **Reasoning and Inference**
- [Reasoning](reasoning.md) - RDFS/OWL reasoning capabilities with pluggable reasoners

### **Data Validation**
- [SHACL Validation](shacl-validation.md) - Constraint validation using SHACL shapes

## 🔧 Feature Architecture

Kastor features are built using a provider-based architecture:

- **Provider Interface**: Common interface for all feature implementations
- **Registry System**: Automatic discovery and registration of providers
- **Configuration**: Flexible configuration for different use cases
- **Capability Detection**: Runtime detection of supported features

## 🚀 Getting Started with Features

1. **Check Capabilities**: Use `getCapabilities()` to see what features are available
2. **Configure**: Set up feature-specific configuration
3. **Use**: Access features through the unified API

## 📚 Related Documentation

- [Getting Started](../getting-started/README.md) - Basic setup and configuration
- [Core API](../api/core-api.md) - API reference for all features
- [Best Practices](../guides/best-practices.md) - Guidelines for using features effectively
- [Troubleshooting](../guides/troubleshooting.md) - Common issues with features



