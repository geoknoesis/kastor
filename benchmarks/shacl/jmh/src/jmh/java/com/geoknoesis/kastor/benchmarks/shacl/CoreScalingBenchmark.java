package com.geoknoesis.kastor.benchmarks.shacl;

import com.geoknoesis.kastor.rdf.RdfGraph;
import com.geoknoesis.kastor.rdf.WeisfeilerLehmanIsomorphism;
import com.geoknoesis.kastor.rdf.shacl.ShaclValidator;
import com.geoknoesis.kastor.rdf.shacl.ValidationReport;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class CoreScalingBenchmark {
  @State(Scope.Thread)
  public static class Indexed {
    @Param({"1000", "10000", "100000"}) public int size;
    RdfGraph graph;
    @Setup public void setup() { graph = CoreBenchmarkSupport.graph(size); }
  }
  @State(Scope.Thread)
  public static class Symmetric {
    @Param({"100", "1000"}) public int size;
    RdfGraph left;
    RdfGraph right;
    WeisfeilerLehmanIsomorphism matcher = new WeisfeilerLehmanIsomorphism(1000000);
    @Setup public void setup() {
      left = CoreBenchmarkSupport.symmetric(size, "left");
      right = CoreBenchmarkSupport.symmetric(size, "right");
    }
  }
  @Benchmark public int indexedLookup(Indexed state) {
    return CoreBenchmarkSupport.lookup(state.graph, state.size);
  }
  @Benchmark public boolean symmetricIsomorphism(Symmetric state) {
    return state.matcher.areIsomorphic(state.left, state.right);
  }
  @State(Scope.Thread)
  public static class ValidationData {
    @Param({"1000", "10000", "100000"}) public int size;
    RdfGraph data;
    RdfGraph shapes;
    ShaclValidator validator;
    @Setup public void setup() {
      data = CoreBenchmarkSupport.validationData(size);
      shapes = CoreBenchmarkSupport.validationShapes();
      validator = ShaclBenchmarkSupport.nativeValidator();
    }
  }
  @Benchmark public ValidationReport classTargetValidation(ValidationData state) {
    return state.validator.validate(state.data, state.shapes);
  }
}
