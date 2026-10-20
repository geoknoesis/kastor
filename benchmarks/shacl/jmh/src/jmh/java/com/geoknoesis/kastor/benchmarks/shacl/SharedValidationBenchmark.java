package com.geoknoesis.kastor.benchmarks.shacl;

import com.geoknoesis.kastor.rdf.RdfGraph;
import com.geoknoesis.kastor.rdf.shacl.ShaclValidator;
import com.geoknoesis.kastor.rdf.shacl.ValidationReport;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Four workers contend on one validator and its prepared-shapes cache. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class SharedValidationBenchmark {
  @Param({"1000", "10000"}) public int size;
  RdfGraph data;
  RdfGraph shapes;
  ShaclValidator validator;

  @Setup public void setup() {
    data = CoreBenchmarkSupport.validationData(size);
    shapes = CoreBenchmarkSupport.validationShapes();
    validator = ShaclBenchmarkSupport.nativeValidator();
  }

  @Benchmark public ValidationReport sharedClassTargetValidation() {
    return validator.validate(data, shapes);
  }
}
