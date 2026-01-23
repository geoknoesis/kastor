# Package Reorganization Plan

**Status:** ✅ **COMPLETED**  
**Impact:** High (Code Organization)  
**Effort:** 2-3 days  
**Breaking Change:** Yes (requires version bump)

## Current Structure

```
com.geoknoesis.kastor.gen.processor
├── codegen/              # Internal - Code generators
│   ├── InstanceDslGenerator.kt
│   ├── InterfaceGenerator.kt
│   ├── OntologyWrapperGenerator.kt
│   ├── PropertyMethodGenerator.kt
│   ├── ValidationCodeGenerator.kt
│   └── WrapperGenerator.kt
├── parsers/              # Internal - File parsers
│   ├── JsonLdContextParser.kt
│   ├── OntologyExtractor.kt
│   └── ShaclParser.kt
├── utils/                # Internal - Utilities
│   ├── CodegenConstants.kt
│   ├── KotlinPoetUtils.kt
│   ├── NamingUtils.kt
│   ├── QNameResolver.kt
│   ├── TypeMapper.kt
│   └── VocabularyMapper.kt
├── model/                # Public API - Data models
│   ├── AnnotationTypes.kt
│   ├── ClassModel.kt
│   ├── DslModel.kt
│   ├── DslOptionsBuilder.kt
│   ├── ResultTypes.kt
│   └── ShaclModel.kt
├── extensions/           # Public API - Extension functions
│   ├── CollectionExtensions.kt
│   └── RequestExtensions.kt
├── exceptions/           # Public API - Exceptions
│   └── GenerationExceptions.kt
├── AnnotationParser.kt   # Internal
├── GenerationCoordinator.kt  # Internal
├── OntologyFileReader.kt # Internal
├── OntologyProcessor.kt  # Internal
└── OntoMapperProcessor.kt   # Internal
```

## Target Structure

```
com.geoknoesis.kastor.gen.processor
├── api/                  # Public API only
│   ├── model/           # Data models
│   │   ├── DslModel.kt
│   │   ├── DslOptionsBuilder.kt
│   │   ├── ResultTypes.kt
│   │   └── ShaclModel.kt
│   ├── builders/        # Builder classes
│   │   └── (extracted from model/)
│   ├── exceptions/      # Exception classes
│   │   └── GenerationExceptions.kt
│   └── extensions/      # Public extension functions
│       ├── CollectionExtensions.kt
│       └── RequestExtensions.kt
├── internal/            # Implementation details
│   ├── codegen/         # Code generators
│   │   ├── InstanceDslGenerator.kt
│   │   ├── InterfaceGenerator.kt
│   │   ├── OntologyWrapperGenerator.kt
│   │   ├── PropertyMethodGenerator.kt
│   │   ├── ValidationCodeGenerator.kt
│   │   └── WrapperGenerator.kt
│   ├── parsers/         # File parsers
│   │   ├── JsonLdContextParser.kt
│   │   ├── OntologyExtractor.kt
│   │   └── ShaclParser.kt
│   ├── utils/           # Utilities
│   │   ├── CodegenConstants.kt
│   │   ├── KotlinPoetUtils.kt
│   │   ├── NamingUtils.kt
│   │   ├── QNameResolver.kt
│   │   ├── TypeMapper.kt
│   │   └── VocabularyMapper.kt
│   ├── model/           # Internal models
│   │   ├── AnnotationTypes.kt
│   │   └── ClassModel.kt
│   └── core/            # Core processor classes
│       ├── AnnotationParser.kt
│       ├── GenerationCoordinator.kt
│       ├── OntologyFileReader.kt
│       ├── OntologyProcessor.kt
│       └── OntoMapperProcessor.kt
└── annotations/         # Annotations (already separate)
    └── OntologyAnnotations.kt
```

## Migration Steps

### Phase 1: Preparation
1. ✅ Identify all public vs internal classes
2. ✅ Document current package structure
3. ✅ Create migration plan (this document)

### Phase 2: Create New Structure
1. Create `api/` package structure
2. Create `internal/` package structure
3. Add package-info.kt files with documentation

### Phase 3: Move Files
1. Move public API files to `api/`
2. Move internal files to `internal/`
3. Update all imports throughout codebase
4. Update test imports

### Phase 4: Backward Compatibility
1. Create type aliases in old locations pointing to new locations
2. Add deprecation warnings
3. Update documentation

### Phase 5: Testing
1. Run all tests
2. Verify compilation
3. Check for any remaining references to old packages

### Phase 6: Cleanup
1. Remove deprecated type aliases (in next major version)
2. Update all documentation
3. Update migration guide

## Files to Move

### Public API (→ api/)
- `model/DslModel.kt` → `api/model/DslModel.kt`
- `model/DslOptionsBuilder.kt` → `api/model/DslOptionsBuilder.kt`
- `model/ResultTypes.kt` → `api/model/ResultTypes.kt`
- `model/ShaclModel.kt` → `api/model/ShaclModel.kt`
- `exceptions/GenerationExceptions.kt` → `api/exceptions/GenerationExceptions.kt`
- `extensions/CollectionExtensions.kt` → `api/extensions/CollectionExtensions.kt`
- `extensions/RequestExtensions.kt` → `api/extensions/RequestExtensions.kt`

### Internal (→ internal/)
- `codegen/*` → `internal/codegen/*`
- `parsers/*` → `internal/parsers/*`
- `utils/*` → `internal/utils/*`
- `model/AnnotationTypes.kt` → `internal/model/AnnotationTypes.kt`
- `model/ClassModel.kt` → `internal/model/ClassModel.kt`
- `AnnotationParser.kt` → `internal/core/AnnotationParser.kt`
- `GenerationCoordinator.kt` → `internal/core/GenerationCoordinator.kt`
- `OntologyFileReader.kt` → `internal/core/OntologyFileReader.kt`
- `OntologyProcessor.kt` → `internal/core/OntologyProcessor.kt`
- `OntoMapperProcessor.kt` → `internal/core/OntoMapperProcessor.kt`

## Backward Compatibility Strategy

### Option 1: Type Aliases (Recommended)
Create type aliases in old locations:

```kotlin
// Old location: com.geoknoesis.kastor.gen.processor.model.InstanceDslRequest
@file:Suppress("DEPRECATION")
package com.geoknoesis.kastor.gen.processor.model

@Deprecated(
    message = "Use com.geoknoesis.kastor.gen.processor.api.model.InstanceDslRequest instead",
    replaceWith = ReplaceWith(
        "com.geoknoesis.kastor.gen.processor.api.model.InstanceDslRequest",
        "com.geoknoesis.kastor.gen.processor.api.model.*"
    ),
    level = DeprecationLevel.WARNING
)
typealias InstanceDslRequest = com.geoknoesis.kastor.gen.processor.api.model.InstanceDslRequest
```

### Option 2: Re-export (Alternative)
Create wrapper files that re-export from new locations.

## Impact Assessment

### Breaking Changes
- ✅ All imports will need to be updated
- ✅ Public API surface remains the same (just different package)
- ✅ Type aliases provide backward compatibility

### Benefits
- ✅ Clear separation of public API vs internal implementation
- ✅ Better encapsulation
- ✅ Easier to maintain
- ✅ Better IDE support (can hide internal packages)

### Risks
- ⚠️ Large refactoring affecting many files
- ⚠️ Potential for missed imports
- ⚠️ Test files need updates
- ⚠️ Documentation needs updates

## Testing Strategy

1. **Unit Tests**: Update all test imports
2. **Integration Tests**: Verify end-to-end functionality
3. **Compilation**: Ensure all modules compile
4. **IDE**: Verify IDE can resolve all imports

## Timeline

- **Phase 1-2**: 4 hours (Structure creation)
- **Phase 3**: 8 hours (File moves and import updates)
- **Phase 4**: 4 hours (Backward compatibility)
- **Phase 5**: 4 hours (Testing)
- **Phase 6**: 2 hours (Cleanup)

**Total**: ~22 hours (2-3 days)

## Notes

- This is a **major refactoring** that should be done in a separate branch
- Requires **version bump** (major or minor depending on backward compatibility strategy)
- Should include **migration guide** for users
- Consider doing this as part of a larger release

## Decision

**Status**: ✅ **COMPLETED**

**Implementation Date**: 2024

**Summary**: Package reorganization has been successfully completed. All files have been moved to their new locations, imports updated, backward compatibility aliases created, and documentation updated. The reorganization provides clear separation between public API (`api/`) and internal implementation (`internal/`), improving maintainability and encapsulation.

**Migration Notes**:
- Old package imports continue to work with deprecation warnings
- Type aliases provide backward compatibility
- Users should migrate to new package structure in `api/` for future development
- Full migration guide available in this document

