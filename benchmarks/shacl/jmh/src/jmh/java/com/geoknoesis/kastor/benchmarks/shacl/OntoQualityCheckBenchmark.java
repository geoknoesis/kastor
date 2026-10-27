package com.geoknoesis.kastor.benchmarks.shacl;

import com.geoknoesis.kastor.ontoquality.QualityChecker;
import com.geoknoesis.kastor.ontoquality.QualityReport;
import com.geoknoesis.kastor.rdf.RdfGraph;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * The default {@code onto-qa check} ({@link QualityChecker#allBundled}): every bundled catalogue, whose detectors are
 * mostly SHACL-SPARQL constraints with {@code REGEX}, on a generated ontology. One validation runs a constraint query
 * per focus node, so this measures the per-focus-node cost of SPARQL constraints (parsing, pre-binding, pattern
 * compilation). Single-shot: one check of the larger size takes seconds.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@State(Scope.Thread)
public class OntoQualityCheckBenchmark {
  @Param({"500", "2000"}) public int classes;
  RdfGraph ontology;
  QualityChecker checker;
  @Setup public void setup() {
    ontology = OntoQualityBenchmarkSupport.ontology(classes);
    checker = OntoQualityBenchmarkSupport.checker();
  }
  @Benchmark public QualityReport defaultCheck() {
    return OntoQualityBenchmarkSupport.check(checker, ontology);
  }
}
