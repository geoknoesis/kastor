package com.geoknoesis.kastor.benchmarks.shacl;

import com.geoknoesis.kastor.rdf.RdfGraph;
import com.geoknoesis.kastor.rdf.shacl.ShapeCacheControl;
import com.geoknoesis.kastor.rdf.shacl.ShaclValidator;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Native SHACL validation over non-trivial shapes: recursive {@code sh:node}, inline shapes, logical constraints,
 * disjoint qualified value shapes, complex property paths, recursion through {@code sh:or} and {@code sh:targetWhere}
 * (see {@link ComplexShapesBenchmarkSupport}).
 *
 * <p>{@code validateWarm} reuses the compiled shapes cache; {@code validateCold} clears it first so shape
 * compilation (including every referenced inline shape) is part of the measurement.
 *
 * <p>Run: {@code ./gradlew :benchmarks:shacl:jmh -Pjmh.includes=NativeComplexShapesBenchmark}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class NativeComplexShapesBenchmark {

  @Param({"recursiveNode", "inlineShapes", "logical", "qualified", "complexPaths", "monotoneRecursion", "targetWhere"})
  public String workload;

  @Param({"1000"})
  public int people;

  private ShaclValidator validator;
  private RdfGraph data;
  private RdfGraph shapes;

  @Setup
  public void setup() {
    validator = ShaclBenchmarkSupport.nativeValidator();
    data = ComplexShapesBenchmarkSupport.data(people);
    shapes = ComplexShapesBenchmarkSupport.shapes(workload);
  }

  @Benchmark
  public void validateWarm(final Blackhole bh) {
    bh.consume(validator.validate(data, shapes));
  }

  @Benchmark
  public void validateCold(final Blackhole bh) {
    if (validator instanceof ShapeCacheControl) {
      ((ShapeCacheControl) validator).clearCache();
    }
    bh.consume(validator.validate(data, shapes));
  }
}
