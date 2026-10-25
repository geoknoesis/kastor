package com.geoknoesis.kastor.gen.processor.internal.core

import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias

/** [type] with type aliases replaced by the type they stand for (a nullable alias or use stays nullable). */
internal fun expandAliases(type: KSType): KSType {
  var current = type
  var nullable = type.isMarkedNullable
  val seen = HashSet<KSDeclaration>()
  while (true) {
    val alias = current.declaration as? KSTypeAlias ?: break
    if (!seen.add(alias)) break
    current = alias.type.resolve()
    nullable = nullable || current.isMarkedNullable
  }
  return if (nullable) current.makeNullable() else current
}
