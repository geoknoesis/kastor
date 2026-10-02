package com.example

/**
 * Compiled with the tests, so KSP runs started by the tests see this interface as a binary (class file) declaration,
 * not as a source: an `@Rdf` interface that extends it inherits default members from a library type.
 */
interface BinaryDefaults {
    val kindLabel: String get() = "binary"

    fun describe(): String = "described as $kindLabel"
}
