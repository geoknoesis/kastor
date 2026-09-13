package com.geoknoesis.kastor.benchmarks.shacl;

import com.geoknoesis.kastor.ontoquality.embed.SimilarityIndex;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class SimilarityScalingBenchmark {
  @Param({"100", "1000"}) public int size;
  @Param({"false", "true"}) public boolean dense;
  SimilarityIndex index;
  @Setup public void setup() { index = SimilarityBenchmarkSupport.index(size, dense); }
  @Benchmark public int exactPairs() { return SimilarityBenchmarkSupport.count(index); }
}
