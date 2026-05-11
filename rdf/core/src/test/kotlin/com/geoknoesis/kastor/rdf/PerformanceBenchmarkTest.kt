package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.io.FileWriter
import kotlin.system.measureTimeMillis
import com.geoknoesis.kastor.rdf.vocab.XSD

/**
 * Performance benchmarks for large dataset operations.
 * 
 * These tests are marked with @Disabled by default as they are resource-intensive.
 * Enable them when you want to run performance benchmarks.
 * 
 * **Usage:**
 * ```bash
 * # Run all benchmarks
 * ./gradlew :rdf:core:test --tests "*PerformanceBenchmarkTest" -DenableBenchmarks=true
 * 
 * # Run specific benchmark
 * ./gradlew :rdf:core:test --tests "*PerformanceBenchmarkTest.benchmarkLargeGraphCreation"
 * ```
 */
class PerformanceBenchmarkTest {
    
    private val enableBenchmarks = System.getProperty("enableBenchmarks") == "true"
    
    @BeforeEach
    fun checkBenchmarksEnabled() {
        if (!enableBenchmarks) {
            println("Benchmarks are disabled. Set -DenableBenchmarks=true to run.")
        }
    }
    
    /**
     * Benchmark: Creating a large graph with many triples.
     * 
     * Measures time to create graphs of various sizes:
     * - Small: 1,000 triples
     * - Medium: 10,000 triples
     * - Large: 100,000 triples
     * - Very Large: 1,000,000 triples
     */
    @Test
    @Disabled("Enable with -DenableBenchmarks=true")
    fun benchmarkLargeGraphCreation() {
        if (!enableBenchmarks) return
        
        val sizes = listOf(1_000, 10_000, 100_000, 1_000_000)
        val results = mutableListOf<BenchmarkResult>()
        
        sizes.forEach { size ->
            val time = measureTimeMillis {
                val repo = Rdf.memory()
                repo.add {
                    repeat(size) { i ->
                        val subject = iri("http://example.org/resource/$i")
                        val predicate = iri("http://example.org/property/name")
                        subject - predicate - "Resource $i"
                    }
                }
            }
            
            val result = BenchmarkResult("Graph Creation", size, time)
            results.add(result)
            println("Created graph with $size triples in ${time}ms (${size.toDouble() / time} triples/ms)")
        }
        
        printBenchmarkResults(results)
    }
    
    /**
     * Benchmark: Querying large graphs.
     * 
     * Measures query performance on graphs of various sizes.
     */
    @Test
    @Disabled("Enable with -DenableBenchmarks=true")
    fun benchmarkLargeGraphQuery() {
        if (!enableBenchmarks) return
        
        val sizes = listOf(1_000, 10_000, 100_000)
        val results = mutableListOf<BenchmarkResult>()
        
        sizes.forEach { size ->
            // Create graph
            val repo = Rdf.memory()
            repo.add {
                repeat(size) { i ->
                    val subject = iri("http://example.org/resource/$i")
                    val predicate = iri("http://example.org/property/name")
                    val obj = "Resource $i"
                    subject - predicate - obj
                }
            }
            
            // Benchmark query
            val time = measureTimeMillis {
                val result = repo.select(SparqlSelectQuery("""
                    SELECT ?s ?o WHERE {
                        ?s <http://example.org/property/name> ?o .
                    } LIMIT 100
                """))
                result.forEach { /* consume results */ }
            }
            
            val result = BenchmarkResult("Graph Query", size, time)
            results.add(result)
            println("Queried graph with $size triples in ${time}ms")
        }
        
        printBenchmarkResults(results)
    }
    
    /**
     * Benchmark: Parsing large RDF files.
     * 
     * Measures parsing performance for various file sizes.
     */
    @Test
    @Disabled("Enable with -DenableBenchmarks=true")
    fun benchmarkLargeFileParsing() {
        if (!enableBenchmarks) return
        
        val sizes = listOf(1_000, 10_000, 100_000)
        val results = mutableListOf<BenchmarkResult>()
        
        sizes.forEach { size ->
            // Create temporary file
            val file = File.createTempFile("benchmark", ".ttl")
            try {
                FileWriter(file).use { writer ->
                    writer.write("@prefix ex: <http://example.org/> .\n")
                    repeat(size) { i ->
                        writer.write("ex:resource$i ex:name \"Resource $i\" .\n")
                    }
                }
                
                // Benchmark parsing
                val time = measureTimeMillis {
                    val graph = Rdf.parseFromFile(file.absolutePath, RdfFormat.TURTLE)
                    assertTrue(graph.getTriples().size >= size)
                }
                
                val result = BenchmarkResult("File Parsing", size, time)
                results.add(result)
                println("Parsed file with $size triples in ${time}ms (${size.toDouble() / time} triples/ms)")
            } finally {
                file.delete()
            }
        }
        
        printBenchmarkResults(results)
    }
    
    /**
     * Benchmark: Streaming parse performance.
     * 
     * Compares streaming vs non-streaming parsing for large files.
     */
    @Test
    @Disabled("Enable with -DenableBenchmarks=true")
    fun benchmarkStreamingParse() {
        if (!enableBenchmarks) return
        
        val size = 100_000
        val file = File.createTempFile("benchmark", ".ttl")
        try {
            FileWriter(file).use { writer ->
                writer.write("@prefix ex: <http://example.org/> .\n")
                repeat(size) { i ->
                    writer.write("ex:resource$i ex:name \"Resource $i\" .\n")
                }
            }
            
            // Benchmark non-streaming
            val nonStreamingTime = measureTimeMillis {
                val graph = Rdf.parseFromFile(file.absolutePath, RdfFormat.TURTLE)
                graph.getTriples().forEach { /* consume */ }
            }
            
            // Benchmark streaming
            val streamingTime = measureTimeMillis {
                Rdf.parseStreaming(file.inputStream(), RdfFormat.TURTLE)
                    .forEach { /* consume */ }
            }
            
            println("Non-streaming parse: ${nonStreamingTime}ms")
            println("Streaming parse: ${streamingTime}ms")
            println("Speedup: ${nonStreamingTime.toDouble() / streamingTime}x")
        } finally {
            file.delete()
        }
    }
    
    /**
     * Benchmark: Serialization performance.
     * 
     * Measures serialization time for graphs of various sizes.
     */
    @Test
    @Disabled("Enable with -DenableBenchmarks=true")
    fun benchmarkSerialization() {
        if (!enableBenchmarks) return
        
        val sizes = listOf(1_000, 10_000, 100_000)
        val formats = listOf(RdfFormat.TURTLE, RdfFormat.N_TRIPLES, RdfFormat.JSON_LD)
        val results = mutableListOf<BenchmarkResult>()
        
        sizes.forEach { size ->
            // Create graph
            val repo = Rdf.memory()
            repo.add {
                repeat(size) { i ->
                    val subject = iri("http://example.org/resource/$i")
                    val predicate = iri("http://example.org/property/name")
                    val obj = "Resource $i"
                    subject - predicate - obj
                }
            }
            
            formats.forEach { format ->
                val time = measureTimeMillis {
                    val serialized = repo.defaultGraph.serialize(format)
                    assertTrue(serialized.isNotEmpty())
                }
                
                val result = BenchmarkResult("Serialization ($format)", size, time)
                results.add(result)
                println("Serialized $size triples to $format in ${time}ms")
            }
        }
        
        printBenchmarkResults(results)
    }
    
    /**
     * Benchmark: Batch operations vs individual operations.
     * 
     * Compares performance of batch addTriples() vs individual add() calls.
     */
    @Test
    @Disabled("Enable with -DenableBenchmarks=true")
    fun benchmarkBatchOperations() {
        if (!enableBenchmarks) return
        
        val size = 10_000
        val triples = (0 until size).map { i ->
            RdfTriple(
                iri("http://example.org/resource/$i"),
                iri("http://example.org/property/name"),
                TypedLiteral("Resource $i", XSD.string)
            )
        }
        
        // Benchmark individual operations
        val individualTime = measureTimeMillis {
            val repo = Rdf.memory()
            triples.forEach { triple ->
                repo.add {
                    triple.subject - triple.predicate - triple.obj
                }
            }
        }
        
        // Benchmark batch operations
        val batchTime = measureTimeMillis {
            val repo = Rdf.memory()
            repo.add {
                triples.forEach { triple ->
                    triple.subject - triple.predicate - triple.obj
                }
            }
        }
        
        println("Individual operations: ${individualTime}ms")
        println("Batch operations: ${batchTime}ms")
        println("Speedup: ${individualTime.toDouble() / batchTime}x")
        
        assertTrue(batchTime < individualTime, "Batch operations should be faster")
    }
    
    private fun printBenchmarkResults(results: List<BenchmarkResult>) {
        println("\n=== Benchmark Results ===")
        results.forEach { result ->
            println("${result.operation}: ${result.size} triples in ${result.timeMs}ms (${result.throughput()} triples/ms)")
        }
        println("========================\n")
    }
    
    private data class BenchmarkResult(
        val operation: String,
        val size: Int,
        val timeMs: Long
    ) {
        fun throughput(): Double = size.toDouble() / timeMs
    }
}

