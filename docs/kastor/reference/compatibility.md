# Compatibility Matrix

{% include version-banner.md %}

## Overview

This document provides detailed compatibility information for Kastor RDF SDK, including supported platforms, dependencies, and provider compatibility.

## Platform Compatibility

### JVM Platforms

| Platform | Minimum Version | Recommended Version | Status |
|----------|----------------|---------------------|--------|
| Java     | 17             | 17+                 | ✅ Supported |
| Kotlin/JVM | 1.9+        | 2.0+                | ✅ Supported |

### Android

| Android API Level | Minimum | Recommended | Status |
|-------------------|---------|-------------|--------|
| Android API       | 26+     | 33+         | ⚠️ Limited Support |

**Note**: Android support is limited due to `ServiceLoader` limitations. See [Android/KMP Guide](../guides/android-kmp.md) for details.

### Kotlin Multiplatform (KMP)

| Target Platform | Status | Notes |
|-----------------|--------|-------|
| JVM             | ✅ Supported | Full support |
| Android         | ⚠️ Limited | ServiceLoader limitations |
| Native          | ❌ Not Supported | Not currently supported |
| JS              | ❌ Not Supported | Not currently supported |

## Dependency Versions

### Core Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Kotlin     | 2.3.21  | Language runtime (repo build; align with your toolchain) |
| SLF4J      | 2.0.13  | Logging framework |
| JUnit 5   | 5.10.3  | Testing framework |

### Provider Dependencies

#### Jena Provider

| Dependency | Version | Purpose |
|------------|---------|---------|
| Apache Jena | 4.x     | RDF store and query engine |

**Compatibility**: Compatible with Jena 4.0.0 and later.

#### RDF4J Provider

| Dependency | Version | Purpose |
|------------|---------|---------|
| Eclipse RDF4J | 4.x | RDF store and query engine |

**Compatibility**: Compatible with RDF4J 4.0.0 and later.

#### SPARQL Provider

| Dependency | Version | Purpose |
|------------|---------|---------|
| HTTP Client | Java 11+ | SPARQL endpoint communication |

**Compatibility**: Works with any SPARQL 1.1 compliant endpoint.

## RDF Format Support

### Supported Formats

| Format | Parser | Serializer | Provider Support |
|-------|--------|------------|------------------|
| Turtle (TTL) | ✅ | ✅ | Jena, RDF4J |
| N-Triples (NT) | ✅ | ✅ | Jena, RDF4J |
| RDF/XML | ✅ | ✅ | Jena, RDF4J |
| JSON-LD | ✅ | ✅ | Jena, RDF4J |
| TRIG | ✅ | ✅ | Jena, RDF4J |
| N-Quads (NQ) | ✅ | ✅ | Jena, RDF4J |

**Note**: Format support depends on the underlying provider. Check provider capabilities for specific format support.

## Provider Compatibility Matrix

### Jena Provider

| Feature | Jena 4.0 | Jena 4.1 | Jena 4.2+ | Notes |
|---------|----------|----------|-----------|-------|
| In-Memory Store | ✅ | ✅ | ✅ | Full support |
| TDB2 Store | ✅ | ✅ | ✅ | Full support |
| SPARQL Queries | ✅ | ✅ | ✅ | Full support |
| Transactions | ✅ | ✅ | ✅ | Full support |
| SHACL Validation | ✅ | ✅ | ✅ | Via shacl-validation module |

### RDF4J Provider

| Feature | RDF4J 4.0 | RDF4J 4.1 | RDF4J 4.2+ | Notes |
|---------|-----------|-----------|------------|-------|
| Memory Store | ✅ | ✅ | ✅ | Full support |
| Native Store | ✅ | ✅ | ✅ | Full support |
| SPARQL Queries | ✅ | ✅ | ✅ | Full support |
| Transactions | ✅ | ✅ | ✅ | Full support |
| SHACL Validation | ✅ | ✅ | ✅ | Via shacl-validation module |

### SPARQL Provider

| Feature | SPARQL 1.1 | Notes |
|---------|------------|-------|
| SELECT Queries | ✅ | Full support |
| ASK Queries | ✅ | Full support |
| CONSTRUCT Queries | ⚠️ | Limited (parsing not fully implemented) |
| DESCRIBE Queries | ⚠️ | Limited (parsing not fully implemented) |
| UPDATE Queries | ✅ | Full support |
| Transactions | ❌ | Not supported (endpoint-dependent) |

## Kotlin Version Compatibility

### Kotlin Language Features

Kastor uses the following Kotlin features:

- **Coroutines**: Not used (synchronous API)
- **Serialization**: Used for JSON-LD context parsing
- **Inline Classes**: Used for `Iri` value class
- **Sealed Classes**: Used for error handling
- **Extension Functions**: Used extensively for DSL

### Kotlin Version Requirements

| Kastor Version | Minimum Kotlin | Recommended Kotlin | Breaking Changes |
|----------------|----------------|-------------------|-----------------|
| 0.1.x          | 1.9+           | 2.3.x             | None |
| 1.0.x (planned) | 2.0+          | 2.3.x+            | TBD |

## Java Version Compatibility

### Java Language Features

Kastor uses the following Java features:

- **Modules**: Not used (traditional classpath)
- **Records**: Not used (Kotlin data classes)
- **Text Blocks**: Not used (Kotlin multiline strings)
- **Pattern Matching**: Not used (Kotlin when expressions)

### Java Version Requirements

| Kastor Version | Minimum Java | Recommended Java | JVM Target |
|----------------|--------------|-------------------|------------|
| 0.1.x          | 17           | 17+                | 17 |
| 1.0.x (planned) | 17           | 21+                | 17+ |

## Build Tool Compatibility

### Gradle

| Gradle Version | Status | Notes |
|----------------|--------|-------|
| 8.0+           | ✅ Supported | Recommended |
| 7.6+           | ⚠️ May Work | Not tested |
| 7.0-           | ❌ Not Supported | Too old |

### Maven

| Maven Version | Status | Notes |
|---------------|--------|-------|
| 3.8+          | ✅ Supported | Recommended |
| 3.6+          | ⚠️ May Work | Not tested |
| 3.5-          | ❌ Not Supported | Too old |

## IDE Compatibility

### IntelliJ IDEA / Android Studio

| IDE Version | Status | Notes |
|-------------|--------|-------|
| 2023.1+     | ✅ Supported | Recommended |
| 2022.3+     | ⚠️ May Work | Not tested |
| 2022.2-     | ❌ Not Supported | Too old |

### Eclipse

| Eclipse Version | Status | Notes |
|-----------------|--------|-------|
| 2023-09+        | ⚠️ Limited | Kotlin support varies |
| Older           | ❌ Not Recommended | Limited Kotlin support |

## Known Compatibility Issues

### ServiceLoader on Android/KMP

**Issue**: `ServiceLoader` is not fully supported on Android and Kotlin Multiplatform.

**Impact**: Provider auto-discovery may not work.

**Solution**: Use explicit provider registration. See [Android/KMP Guide](../guides/android-kmp.md).

### RDF4J Native Store on Windows

**Issue**: RDF4J Native Store may have issues on Windows with long file paths.

**Impact**: Repository creation may fail.

**Solution**: Use shorter paths or Jena TDB2 store instead.

## Testing Compatibility

### Test Frameworks

| Framework | Version | Status |
|-----------|---------|--------|
| JUnit 5   | 5.10.3  | ✅ Supported |
| Kotlin Test | 2.3.21 | ✅ Supported |
| Mockito   | N/A     | Not used |

### Test Runners

| Runner | Status | Notes |
|--------|--------|-------|
| Gradle Test | ✅ Supported | Default |
| IntelliJ Test | ✅ Supported | IDE integration |
| Maven Surefire | ✅ Supported | Maven builds |

## Migration Compatibility

### Upgrading Between Versions

See [Versioning Policy](versioning-policy.md) for detailed migration information.

### Breaking Changes

Breaking changes are documented in:
- Release notes
- Migration guides
- Changelog

## Reporting Compatibility Issues

If you encounter compatibility issues:

1. **Check Documentation**: Review this document and related guides
2. **Check Known Issues**: Review GitHub issues for known problems
3. **Report Issue**: Open a GitHub issue with:
   - Platform details (OS, Java version, Kotlin version)
   - Kastor version
   - Error messages or logs
   - Steps to reproduce

## Related Documentation

- [Versioning Policy](versioning-policy.md) - Versioning strategy and guarantees
- [Installation Guide](../getting-started/installation.md) - Installation instructions
- [Android/KMP Guide](../guides/android-kmp.md) - Android and KMP specific guidance

