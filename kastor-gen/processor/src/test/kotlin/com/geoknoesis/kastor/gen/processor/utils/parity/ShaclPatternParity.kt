package com.geoknoesis.kastor.gen.processor.utils.parity

import com.geoknoesis.kastor.gen.processor.internal.utils.ShaclPatterns

/*
 * The tests in this package are the tables of the Kastor SHACL validator's pattern compiler
 * (rdf/shacl/validation: ShaclPatternCompatibilityTest, ShaclPatternTranslationTest, ShaclPatternDollarTest), copied
 * with only the package and the imports changed, so that the validator and the code generator are pinned by the same
 * cases: a pattern means the same, and is rejected alike, in both. These two declarations map the validator's names
 * to the generator's translation.
 */

internal typealias ShapeCompileException = ShaclPatterns.PatternRejectedException

internal fun compileShaclPattern(pattern: String, flags: String?): Regex = ShaclPatterns.compile(pattern, flags)
