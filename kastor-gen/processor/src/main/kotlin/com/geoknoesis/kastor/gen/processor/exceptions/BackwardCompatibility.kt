@file:Suppress("DEPRECATION", "unused")
package com.geoknoesis.kastor.gen.processor.exceptions

import com.geoknoesis.kastor.gen.processor.api.exceptions.*

/**
 * Backward compatibility type aliases for package reorganization.
 * 
 * These type aliases allow code using the old package structure to continue working.
 * They are deprecated and will be removed in a future major version.
 * 
 * @deprecated Use com.geoknoesis.kastor.gen.processor.api.exceptions instead
 */
@Deprecated(
    message = "Use com.geoknoesis.kastor.gen.processor.api.exceptions instead",
    replaceWith = ReplaceWith(
        "com.geoknoesis.kastor.gen.processor.api.exceptions.*",
        "com.geoknoesis.kastor.gen.processor.api.exceptions"
    ),
    level = DeprecationLevel.WARNING
)
typealias GenerationException = com.geoknoesis.kastor.gen.processor.api.exceptions.GenerationException

@Deprecated(
    message = "Use com.geoknoesis.kastor.gen.processor.api.exceptions instead",
    replaceWith = ReplaceWith(
        "com.geoknoesis.kastor.gen.processor.api.exceptions.*",
        "com.geoknoesis.kastor.gen.processor.api.exceptions"
    ),
    level = DeprecationLevel.WARNING
)
typealias MissingShapeException = com.geoknoesis.kastor.gen.processor.api.exceptions.MissingShapeException

@Deprecated(
    message = "Use com.geoknoesis.kastor.gen.processor.api.exceptions instead",
    replaceWith = ReplaceWith(
        "com.geoknoesis.kastor.gen.processor.api.exceptions.*",
        "com.geoknoesis.kastor.gen.processor.api.exceptions"
    ),
    level = DeprecationLevel.WARNING
)
typealias InvalidConfigurationException = com.geoknoesis.kastor.gen.processor.api.exceptions.InvalidConfigurationException

@Deprecated(
    message = "Use com.geoknoesis.kastor.gen.processor.api.exceptions instead",
    replaceWith = ReplaceWith(
        "com.geoknoesis.kastor.gen.processor.api.exceptions.*",
        "com.geoknoesis.kastor.gen.processor.api.exceptions"
    ),
    level = DeprecationLevel.WARNING
)
typealias FileNotFoundException = com.geoknoesis.kastor.gen.processor.api.exceptions.FileNotFoundException

@Deprecated(
    message = "Use com.geoknoesis.kastor.gen.processor.api.exceptions instead",
    replaceWith = ReplaceWith(
        "com.geoknoesis.kastor.gen.processor.api.exceptions.*",
        "com.geoknoesis.kastor.gen.processor.api.exceptions"
    ),
    level = DeprecationLevel.WARNING
)
typealias ValidationException = com.geoknoesis.kastor.gen.processor.api.exceptions.ValidationException

@Deprecated(
    message = "Use com.geoknoesis.kastor.gen.processor.api.exceptions instead",
    replaceWith = ReplaceWith(
        "com.geoknoesis.kastor.gen.processor.api.exceptions.*",
        "com.geoknoesis.kastor.gen.processor.api.exceptions"
    ),
    level = DeprecationLevel.WARNING
)
typealias FileGenerationException = com.geoknoesis.kastor.gen.processor.api.exceptions.FileGenerationException

@Deprecated(
    message = "Use com.geoknoesis.kastor.gen.processor.api.exceptions instead",
    replaceWith = ReplaceWith(
        "com.geoknoesis.kastor.gen.processor.api.exceptions.*",
        "com.geoknoesis.kastor.gen.processor.api.exceptions"
    ),
    level = DeprecationLevel.WARNING
)
typealias ProcessingException = com.geoknoesis.kastor.gen.processor.api.exceptions.ProcessingException

