package com.geoknoesis.kastor.benchmarks.shacl

import java.nio.file.Files
import java.nio.file.Path

internal fun resolveRepositoryRoot(start: Path = Path.of(System.getProperty("user.dir"))): Path {
  var dir: Path? = start
  repeat(8) {
    val candidate = dir ?: return@repeat
    if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
      return candidate.toAbsolutePath().normalize()
    }
    dir = candidate.parent
  }
  error(
      "Could not find settings.gradle.kts walking up from $start (user.dir=${System.getProperty("user.dir")}). " +
          "Set -Dkastor.repo.root=/path/to/kastor for JMH forks if needed.",
  )
}
