package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.processor.api.model.NumericBoundKind

internal val NumericBoundKind.operator: String
    get() = when (this) {
        NumericBoundKind.MIN_INCLUSIVE -> ">="
        NumericBoundKind.MAX_INCLUSIVE -> "<="
        NumericBoundKind.MIN_EXCLUSIVE -> ">"
        NumericBoundKind.MAX_EXCLUSIVE -> "<"
    }

internal val NumericBoundKind.violationOperator: String
    get() = when (this) {
        NumericBoundKind.MIN_INCLUSIVE -> "<"
        NumericBoundKind.MAX_INCLUSIVE -> ">"
        NumericBoundKind.MIN_EXCLUSIVE -> "<="
        NumericBoundKind.MAX_EXCLUSIVE -> ">="
    }
