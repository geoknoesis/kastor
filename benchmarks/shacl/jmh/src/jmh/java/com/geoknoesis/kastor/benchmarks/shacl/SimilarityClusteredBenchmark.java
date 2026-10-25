package com.geoknoesis.kastor.benchmarks.shacl;

import com.geoknoesis.kastor.ontoquality.embed.SimilarityIndex;
import com.geoknoesis.kastor.rdf.RdfResource;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * Similarity search on embedding-like vectors (clustered and anisotropic, see
 * {@link SimilarityBenchmarkSupport#clusteredVectors}) at vocabulary sizes where the scaled limits matter, with the
 * limits onto-qa uses. Measures the search on a built tree ({@code exactSearch}, {@code approximateSearch}) and the
 * tree build plus the first search ({@code buildAndExactSearch}), which the cached-tree cases never pay. Not part of the
 * regression gate (whose cases match {@code .*ScalingBenchmark.*}): run it with
 * {@code java -jar <jmh jar> SimilarityClusteredBenchmark}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 1, time = 5)
@Measurement(iterations = 3, time = 10)
public class SimilarityClusteredBenchmark {
  @Param({"10000", "50000"}) public int size;
  Map<RdfResource, float[]> vectors;
  SimilarityIndex index;

  @Setup(Level.Trial)
  public void setup() {
    vectors = SimilarityBenchmarkSupport.clusteredVectors(size, 42);
    index = SimilarityBenchmarkSupport.newIndex(vectors);
    SimilarityBenchmarkSupport.countScaled(index, size, false);
  }

  @Benchmark public int exactSearch() { return SimilarityBenchmarkSupport.countScaled(index, size, false); }

  @Benchmark public int approximateSearch() { return SimilarityBenchmarkSupport.countScaled(index, size, true); }

  @Benchmark
  public int buildAndExactSearch() {
    return SimilarityBenchmarkSupport.countScaled(SimilarityBenchmarkSupport.newIndex(vectors), size, false);
  }
}
