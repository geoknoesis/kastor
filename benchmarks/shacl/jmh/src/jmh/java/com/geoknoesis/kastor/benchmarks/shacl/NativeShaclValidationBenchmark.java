package com.geoknoesis.kastor.benchmarks.shacl;

import com.geoknoesis.kastor.rdf.RdfGraph;
import com.geoknoesis.kastor.rdf.shacl.ShaclValidator;
import com.geoknoesis.kastor.rdf.shacl.ValidationReport;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Micro-benchmark: native SHACL validation on a small in-memory graph pair.
 *
 * <p>Run: {@code ./gradlew :benchmarks:shacl:jmh}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class NativeShaclValidationBenchmark {

  private ShaclValidator validator;
  private RdfGraph data;
  private RdfGraph shapes;

  @Setup
  public void setup() {
    validator = ShaclBenchmarkSupport.nativeValidator();
    data = ShaclBenchmarkSupport.loadGraphFromResource("/jmh-workload/data.ttl");
    shapes = ShaclBenchmarkSupport.loadGraphFromResource("/jmh-workload/shapes.ttl");
  }

  @Benchmark
  public void validateNative(final Blackhole bh) {
    ValidationReport report = validator.validate(data, shapes);
    bh.consume(report);
  }
}
