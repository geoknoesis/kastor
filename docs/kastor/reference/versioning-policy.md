# Versioning Policy

{% include version-banner.md %}

## Overview

Kastor RDF SDK follows [Semantic Versioning (Semver)](https://semver.org/) to ensure predictable versioning and clear communication about API changes. This document outlines our versioning strategy, backward compatibility guarantees, and deprecation policies.

## Semantic Versioning

Kastor uses the `MAJOR.MINOR.PATCH` version format:

- **MAJOR** (X.0.0): Breaking changes that require code modifications
- **MINOR** (0.X.0): New features and enhancements that are backward compatible
- **PATCH** (0.0.X): Bug fixes and patches that are backward compatible

### Version Format

```
<MAJOR>.<MINOR>.<PATCH>[-<PRERELEASE>][+<BUILD>]
```

Examples:
- `0.1.0` - Initial release
- `0.2.0` - Minor release with new features
- `0.1.1` - Patch release with bug fixes
- `1.0.0-rc.1` - Release candidate
- `1.0.0+20240101` - Build metadata

## Backward Compatibility Guarantees

### Public API Stability

**Kastor guarantees backward compatibility within the same MAJOR version:**

- ✅ **Public API methods** remain stable and functional
- ✅ **Public API signatures** do not change (parameter types, return types, method names)
- ✅ **Public data classes** maintain their structure (properties, types)
- ✅ **Public interfaces** maintain their contracts
- ✅ **Public constants** remain available and unchanged

### What Constitutes a Breaking Change?

A **MAJOR** version bump is required for:

1. **Removed APIs**: Public methods, classes, or interfaces removed
2. **Signature Changes**: Method parameters or return types changed
3. **Behavior Changes**: Public API behavior changes in incompatible ways
4. **Package Changes**: Public API moved to different packages (with migration period)
5. **Dependency Changes**: Minimum Kotlin/Java version requirements increased

### What Does NOT Require a MAJOR Bump?

These changes are allowed in **MINOR** or **PATCH** releases:

- ✅ **New APIs**: New methods, classes, or interfaces added
- ✅ **Deprecated APIs**: APIs marked as deprecated (removed in next MAJOR)
- ✅ **Internal Changes**: Implementation details changed (not affecting public API)
- ✅ **Bug Fixes**: Correcting incorrect behavior to match documented behavior
- ✅ **Performance Improvements**: Optimizations that don't change behavior
- ✅ **New Optional Parameters**: Adding optional parameters with defaults

## Deprecation Strategy

### Deprecation Process

When an API needs to be removed or replaced, we follow this process:

1. **Mark as Deprecated**: Use Kotlin's `@Deprecated` annotation
2. **Provide Migration Path**: Include `replaceWith` parameter with replacement API
3. **Document in Release Notes**: Announce deprecation in release notes
4. **Maintain for One MAJOR Version**: Keep deprecated API for at least one MAJOR version
5. **Remove in Next MAJOR**: Remove deprecated API in next MAJOR version

### Deprecation Example

```kotlin
@Deprecated(
    message = "Use Rdf.memory() instead",
    replaceWith = ReplaceWith("Rdf.memory()", "com.geoknoesis.kastor.rdf.Rdf"),
    level = DeprecationLevel.WARNING
)
fun createMemoryRepository(): RdfRepository {
    return Rdf.memory()
}
```

### Deprecation Levels

- **WARNING**: API is deprecated but still functional (default)
- **ERROR**: API is deprecated and will cause compilation errors
- **HIDDEN**: API is hidden from IDE autocomplete (rarely used)

## Version Compatibility

### Kotlin Version Compatibility

| Kastor Version | Minimum Kotlin | Recommended Kotlin | Notes |
|----------------|----------------|-------------------|-------|
| 0.1.x          | 1.9+           | 2.1.0             | Initial release |
| 1.0.x          | 2.0+           | 2.1.0+            | Stable release (planned) |

### Java Version Compatibility

| Kastor Version | Minimum Java | Recommended Java | Notes |
|----------------|--------------|------------------|-------|
| 0.1.x          | 17+          | 17+              | JVM target: 17 |
| 1.0.x          | 17+          | 21+              | JVM target: 17+ (planned) |

### Provider Compatibility

Kastor providers (Jena, RDF4J, SPARQL) have their own versioning:

| Provider Module | Backend Library | Compatibility |
|-----------------|-----------------|---------------|
| `rdf:jena`      | Apache Jena 4.x | Compatible with Jena 4.0+ |
| `rdf:rdf4j`     | Eclipse RDF4J 4.x | Compatible with RDF4J 4.0+ |
| `rdf:sparql`    | HTTP/SPARQL 1.1 | Compatible with SPARQL 1.1 endpoints |

**Note**: Provider modules may have different version numbers than the core SDK. Check individual provider documentation for specific compatibility requirements.

## Generated Code Stability

### Code Generation Determinism

Kastor Gen guarantees **deterministic code generation**:

- ✅ **Same Input → Same Output**: Identical SHACL shapes and JSON-LD contexts produce identical generated code
- ✅ **Stable API**: Generated interfaces and classes maintain stable structure across regenerations
- ✅ **Reproducible Builds**: Code generation is deterministic and reproducible

### Generated Code Versioning

Generated code follows these principles:

1. **No Breaking Changes in MINOR/PATCH**: Generated code structure remains stable
2. **New Features in MINOR**: New generation features added without breaking existing code
3. **Breaking Changes in MAJOR**: Structural changes to generated code require MAJOR version bump

### Ontology Evolution

When your ontology (SHACL shapes or JSON-LD context) changes:

- **New Properties**: Added without breaking existing code
- **Removed Properties**: May cause compilation errors (requires code update)
- **Renamed Properties**: Treated as removal + addition (requires code update)
- **Type Changes**: May cause compilation errors (requires code update)

**Best Practice**: Version your ontologies and regenerate code when ontology changes.

## Migration Guides

### Upgrading Between Versions

When upgrading Kastor versions:

1. **Check Release Notes**: Review breaking changes and new features
2. **Update Dependencies**: Update version in `build.gradle.kts` or `pom.xml`
3. **Fix Deprecation Warnings**: Address any deprecated API usage
4. **Run Tests**: Ensure all tests pass with new version
5. **Review Generated Code**: Regenerate code if using Kastor Gen

### Example: Upgrading from 0.1.0 to 0.2.0

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.geoknoesis.kastor:rdf:core:0.2.0")
    implementation("com.geoknoesis.kastor:rdf:jena:0.2.0")
}
```

**Note**: Minor and patch releases should be drop-in replacements. If you encounter issues, check the release notes for breaking changes.

### Example: Upgrading from 0.x to 1.0.0

When upgrading to 1.0.0 (when released):

1. **Review Breaking Changes**: Check migration guide for 1.0.0
2. **Update Deprecated APIs**: Replace deprecated APIs with new ones
3. **Update Code**: Make necessary code changes for breaking changes
4. **Test Thoroughly**: Run comprehensive tests

## Version Support Policy

### Supported Versions

- **Current Version**: Latest stable release (actively maintained)
- **Previous MAJOR Version**: Supported for security fixes only (6 months)
- **Older Versions**: Not supported (no updates or fixes)

### Support Timeline

| Version | Release Date | End of Support | Status |
|---------|--------------|----------------|--------|
| 0.1.x   | TBD          | TBD            | Current |
| 1.0.x   | TBD          | TBD            | Planned |

**Note**: Support timelines are subject to change. Check release notes for specific support periods.

## Pre-Release Versions

### Release Candidates

Release candidates (RC) are pre-release versions for testing:

- Format: `X.Y.Z-rc.N` (e.g., `1.0.0-rc.1`)
- **Not recommended for production**
- May contain bugs or incomplete features
- API may change before final release

### Snapshots

Snapshot versions are development builds:

- Format: `X.Y.Z-SNAPSHOT`
- **Not recommended for production**
- May be unstable or broken
- API may change at any time

## Versioning Best Practices

### For Library Developers

1. **Follow Semver**: Use MAJOR.MINOR.PATCH format
2. **Document Breaking Changes**: Clearly document in release notes
3. **Deprecate Before Removing**: Give users time to migrate
4. **Maintain Changelog**: Keep detailed changelog of changes
5. **Tag Releases**: Tag releases in version control

### For Library Users

1. **Pin Versions**: Use specific versions in production (avoid `+` ranges)
2. **Test Upgrades**: Test upgrades in development before production
3. **Monitor Deprecations**: Address deprecation warnings promptly
4. **Read Release Notes**: Review release notes before upgrading
5. **Report Issues**: Report compatibility issues or bugs

## Changelog and Release Notes

### Changelog Format

Release notes follow this format:

```markdown
## [Version] - YYYY-MM-DD

### Added
- New features

### Changed
- Changes to existing features

### Deprecated
- Deprecated APIs

### Removed
- Removed APIs

### Fixed
- Bug fixes

### Security
- Security fixes
```

### Accessing Release Notes

- **GitHub Releases**: Check GitHub releases page
- **Changelog File**: See `CHANGELOG.md` in repository
- **Documentation**: See version-specific documentation

## Questions and Support

If you have questions about versioning:

1. **Check Documentation**: Review this document and related guides
2. **Check Release Notes**: Review release notes for your version
3. **Open an Issue**: Report versioning concerns or questions
4. **Contact Maintainers**: Reach out to maintainers for clarification

## Related Documentation

- [Installation Guide](../getting-started/installation.md) - How to install and configure Kastor
- [Compatibility Matrix](compatibility.md) - Detailed compatibility information
- [Migration Guides](../guides/migration.md) - Version-specific migration guides
- [API Reference](api-reference.md) - Complete API documentation

