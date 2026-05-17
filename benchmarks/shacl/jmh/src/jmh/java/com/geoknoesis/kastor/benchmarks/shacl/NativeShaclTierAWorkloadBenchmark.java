package com.geoknoesis.kastor.benchmarks.shacl;

import com.geoknoesis.kastor.rdf.RdfGraph;
import com.geoknoesis.kastor.rdf.shacl.ShaclValidator;
import com.geoknoesis.kastor.rdf.shacl.ValidationReport;
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
 * Native validation driven by Tier A JSON under {@code workloads/tier-a/} (on JMH classpath as {@code
 * /tier-a/&lt;name&gt;.json}).
 *
 * <p>Paths inside JSON are resolved from the repository root (walk up from {@code user.dir} or {@code
 * -Dkastor.repo.root}).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class NativeShaclTierAWorkloadBenchmark {

  @Param({"w3c-minCount-001", "w3c-targetClass-001"})
  public String tierAJsonBaseName;

  private ShaclValidator validator;
  private RdfGraph data;
  private RdfGraph shapes;

  @Setup
  public void setup() {
    validator = ShaclBenchmarkSupport.nativeValidator();
    LoadedWorkload loaded =
        ShaclBenchmarkSupport.loadTierAWorkloadGraphs(tierAJsonBaseName);
    data = loaded.getData();
    shapes = loaded.getShapes();
  }

  @Benchmark
  public void validateNativeTierA(final Blackhole bh) {
    ValidationReport report = validator.validate(data, shapes);
    bh.consume(report);
  }
}
