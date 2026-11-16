package com.geoknoesis.kastor.rdf.lens

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.lit
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.geoknoesis.lens.BooleanPredicate
import org.geoknoesis.lens.CompareOperator
import org.geoknoesis.lens.Filters
import org.geoknoesis.lens.Lens
import org.geoknoesis.lens.LensDsl
import org.geoknoesis.lens.LensOptimizer
import org.geoknoesis.lens.NetworkClosure
import org.geoknoesis.lens.NetworkLens
import org.geoknoesis.lens.NetworkLensCompiler
import org.geoknoesis.lens.SparqlCompiler
import org.geoknoesis.lens.Value
import org.geoknoesis.lens.ValueExpression
import org.geoknoesis.lens.lens
import org.geoknoesis.lens.lensDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Composition rewrites: the expected canonical shapes, and a randomized check of
 * compiled queries against an independent in-memory evaluator on both providers.
 */
class LensCompositionOptimizationProviderTest {
    private fun ex(local: String) = "urn:compose:$local"
    private val rdfType = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val integer = "http://www.w3.org/2001/XMLSchema#integer"
    private val age = ex("age")
    private val link = ex("link")
    private val tag = ex("tag")
    private val classes = listOf("A", "B", "C").map { ex(it) }
    private val tags = listOf("red", "blue").map { ex(it) }
    private val nodes = (0 until 14).map { Iri(ex("n$it")) }

    private fun ageIs(op: CompareOperator, k: Int): BooleanPredicate =
        Filters.compare(Filters.path(age), op, Filters.constant(Value.TypedLiteral(k.toString(), integer)))

    private val a = LensDsl.type(classes[0])
    private val adult = ageIs(CompareOperator.GT, 18)
    private val senior = ageIs(CompareOperator.GE, 60)

    @Test fun intersectionOfLensesSharingABaseScansItOnce() {
        val composed = LensDsl.and(a, LensDsl.filter(a, adult), LensDsl.filter(a, senior))
        val explained = SparqlCompiler().explain(composed)
        assertEquals(Lens.Filter(a, BooleanPredicate.All(listOf(adult, senior))), explained.plan.lens)
        assertTrue("pull-restrictions-out-of-intersection" in explained.plan.rules)
        assertEquals(1, Regex(" a <").findAll(explained.sparql).count())
    }

    @Test fun intersectionCompilesToOneFlatGroup() {
        val b = LensDsl.type(classes[1])
        val restricted = LensDsl.eq(b, tag, Value.Iri(tags[0]))
        assertEquals(
            "SELECT DISTINCT ?node WHERE {\n?node a <${classes[0]}> .\n?node a <${classes[1]}> .\n" +
                "?node <$tag> <${tags[0]}> .\n} ORDER BY ?node",
            SparqlCompiler().select(LensDsl.and(a, restricted))
        )
    }

    @Test fun unionOverASharedInputBecomesOneFilter() {
        val plan = LensOptimizer().optimize(LensDsl.or(LensDsl.filter(a, adult), LensDsl.filter(a, senior)))
        assertEquals(Lens.Filter(a, BooleanPredicate.Any(listOf(adult, senior))), plan.lens)
        assertTrue("merge-union-filters-over-shared-input" in plan.rules)

        val absorbed = LensOptimizer().optimize(LensDsl.or(LensDsl.filter(a, adult), a))
        assertEquals(a, absorbed.lens)
        assertTrue("absorb-filtered-union-operand" in absorbed.rules)
    }

    @Test fun differenceAgainstAFilteredSelfBecomesANegatedFilter() {
        val plan = LensOptimizer().optimize(LensDsl.minus(a, LensDsl.filter(a, adult)))
        assertEquals(Lens.Filter(a, BooleanPredicate.Not(adult)), plan.lens)
        assertTrue("rewrite-difference-as-negated-filter" in plan.rules)

        // Only the clauses the right side adds are negated.
        val narrowed = LensOptimizer().optimize(
            LensDsl.minus(LensDsl.filter(a, adult), LensDsl.filter(a, Filters.all(adult, senior)))
        )
        assertEquals(Lens.Filter(a, BooleanPredicate.All(listOf(adult, BooleanPredicate.Not(senior)))), narrowed.lens)

        // A constant-only comparison can raise a SPARQL error, so it must stay NOT EXISTS.
        val constants = Filters.compare(
            Filters.constant(Value.TypedLiteral("x", integer)), CompareOperator.LT,
            Filters.constant(Value.TypedLiteral("1", integer))
        )
        val kept = LensOptimizer().optimize(LensDsl.minus(a, LensDsl.filter(a, constants)))
        assertEquals(Lens.Difference(a, Lens.Filter(a, constants)), kept.lens)
    }

    @Test fun traversedEdgesUseOneBranchPerDepthAndInducedJoinsNodeSets() {
        val network = NetworkLens(seeds = a, predicate = link, maxDepth = 3)
        val traversed = NetworkLensCompiler().compile(network).edges
        assertEquals(3, Regex("\\?subject <urn:compose:link> \\?object").findAll(traversed).count())
        assertEquals(1, Regex("BIND").findAll(traversed).count())

        val induced = NetworkLensCompiler().compile(network.copy(closure = NetworkClosure.INDUCED)).edges
        assertTrue(induced.contains("SELECT DISTINCT ?subject WHERE"))
        assertTrue(induced.contains("SELECT DISTINCT ?object WHERE"))
        assertFalse(induced.contains("FILTER EXISTS"))
    }

    // ---- randomized equivalence against an independent evaluator ----

    private class Data(
        val types: Map<Iri, Set<String>>,
        val ages: Map<Iri, List<Int>>,
        val links: Map<Iri, Set<Iri>>,
        val tags: Map<Iri, Set<String>>
    )

    private fun data(): Data {
        val random = Random(7)
        return Data(
            types = nodes.associateWith { classes.filter { random.nextInt(3) > 0 }.toSet() },
            ages = nodes.associateWith { List(random.nextInt(3)) { random.nextInt(10, 90) } },
            links = nodes.associateWith { nodes.filter { random.nextInt(5) == 0 }.toSet() },
            tags = nodes.associateWith { tags.filter { random.nextBoolean() }.toSet() }
        )
    }

    private fun load(repo: RdfRepository, data: Data) {
        val graph = repo.editDefaultGraph()
        for (node in nodes) {
            data.types.getValue(node).forEach { graph.addTriple(RdfTriple(node, rdfType, Iri(it))) }
            data.ages.getValue(node).forEach {
                graph.addTriple(RdfTriple(node, Iri(age), lit(it.toString(), Iri(integer))))
            }
            data.links.getValue(node).forEach { graph.addTriple(RdfTriple(node, Iri(link), it)) }
            data.tags.getValue(node).forEach { graph.addTriple(RdfTriple(node, Iri(tag), Iri(it))) }
        }
    }

    private fun holds(p: BooleanPredicate, node: Iri, data: Data): Boolean = when (p) {
        is BooleanPredicate.Compare -> {
            val k = ((p.right as ValueExpression.Constant).value as Value.TypedLiteral).lexical.toInt()
            // Path(age) reads the node's own ages; SequencePath(link, age) those of linked nodes.
            val values = if (p.left is ValueExpression.SequencePath) {
                data.links.getValue(node).flatMap { data.ages.getValue(it) }
            } else data.ages.getValue(node)
            values.any {
                when (p.operator) {
                    CompareOperator.EQ -> it == k
                    CompareOperator.NE -> it != k
                    CompareOperator.LT -> it < k
                    CompareOperator.LE -> it <= k
                    CompareOperator.GT -> it > k
                    CompareOperator.GE -> it >= k
                }
            }
        }
        is BooleanPredicate.All -> p.clauses.all { holds(it, node, data) }
        is BooleanPredicate.Any -> p.clauses.any { holds(it, node, data) }
        is BooleanPredicate.Not -> !holds(p.inner, node, data)
    }

    private fun evaluate(lens: Lens, data: Data): Set<Iri> = when (lens) {
        is Lens.Type -> nodes.filter { lens.classIri in data.types.getValue(it) }.toSet()
        is Lens.Equals -> evaluate(lens.input, data)
            .filter { (lens.value as Value.Iri).value in data.tags.getValue(it) }.toSet()
        is Lens.Exists -> {
            val targets = evaluate(lens.target, data)
            evaluate(lens.input, data).filter { n -> data.links.getValue(n).any { it in targets } }.toSet()
        }
        is Lens.Intersect -> lens.parts.map { evaluate(it, data) }.reduce { x, y -> x intersect y }
        is Lens.Union -> lens.parts.flatMap { evaluate(it, data) }.toSet()
        is Lens.Difference -> evaluate(lens.left, data) - evaluate(lens.right, data)
        is Lens.Filter -> evaluate(lens.input, data).filter { holds(lens.condition, it, data) }.toSet()
    }

    private fun randomPredicate(random: Random, depth: Int): BooleanPredicate =
        when (if (depth == 0) 0 else random.nextInt(5)) {
            1 -> Filters.not(randomPredicate(random, depth - 1))
            2 -> Filters.all(randomPredicate(random, depth - 1), randomPredicate(random, depth - 1))
            3 -> Filters.any(randomPredicate(random, depth - 1), randomPredicate(random, depth - 1))
            else -> {
                val op = CompareOperator.entries[random.nextInt(6)]
                val k = listOf(18, 40, 60)[random.nextInt(3)]
                if (random.nextInt(4) == 0) Filters.compare(
                    Filters.sequence(link, age), op, Filters.constant(Value.TypedLiteral(k.toString(), integer))
                ) else ageIs(op, k)
            }
        }

    /** Operands are drawn from a pool, so compositions frequently share sub-lenses. */
    private fun randomLenses(count: Int): List<Lens> {
        val random = Random(42)
        val pool = classes.map { LensDsl.type(it) }.toMutableList()
        fun pick() = pool[random.nextInt(pool.size)]
        repeat(count) {
            pool.add(when (random.nextInt(8)) {
                0 -> LensDsl.and(pick(), pick())
                1 -> LensDsl.and(pick(), pick(), pick())
                2 -> LensDsl.or(pick(), pick())
                3 -> LensDsl.minus(pick(), pick())
                4 -> LensDsl.eq(pick(), tag, Value.Iri(tags[random.nextInt(2)]))
                5 -> LensDsl.exists(pick(), link, pick())
                else -> LensDsl.filter(pick(), randomPredicate(random, 2))
            })
        }
        return pool
    }

    private fun size(lens: Lens): Int = 1 + when (lens) {
        is Lens.Type -> 0
        is Lens.Equals -> size(lens.input)
        is Lens.Exists -> size(lens.input) + size(lens.target)
        is Lens.Difference -> size(lens.left) + size(lens.right)
        is Lens.Intersect -> lens.parts.sumOf { size(it) }
        is Lens.Union -> lens.parts.sumOf { size(it) }
        is Lens.Filter -> size(lens.input)
    }

    private fun verifyRandomized(factory: () -> RdfRepository) {
        val data = data()
        factory().use { repo ->
            load(repo, data)
            val bridge = KastorLensProviderBridge(repo)
            val compiler = SparqlCompiler()
            var nonEmpty = 0
            for (lens in randomLenses(400).filter { size(it) <= 60 }) {
                val expected = evaluate(lens, data)
                if (expected.isNotEmpty()) nonEmpty++
                val explained = compiler.explain(lens)
                assertEquals(expected, bridge.nodes(explained.sparql)) {
                    "lens=$lens\nplan=${explained.plan.lens}\nsparql=${explained.sparql}"
                }
            }
            assertTrue(nonEmpty > 50) { "Fixture too sparse to be a meaningful oracle: $nonEmpty" }
        }
    }

    @Test fun jenaMatchesReferenceEvaluator() = verifyRandomized { JenaRepository.MemoryRepository() }
    @Test fun rdf4jMatchesReferenceEvaluator() = verifyRandomized { Rdf4jRepository.MemoryRepository() }

    /** Bounded walk over the fixture, independent of the compiler: OUT direction only. */
    private fun walkOracle(network: NetworkLens, data: Data): KastorLensProviderBridge.Graph {
        val eligible = network.traversalNode?.let { evaluate(it, data) } ?: nodes.toSet()
        val output = network.outputNode?.let { evaluate(it, data) } ?: nodes.toSet()
        var frontier = evaluate(network.seeds, data) intersect eligible
        val reached = frontier.toMutableSet()
        val witnessed = LinkedHashSet<Pair<Iri, Iri>>()
        repeat(network.maxDepth) {
            val next = LinkedHashSet<Iri>()
            for (s in frontier) for (o in data.links.getValue(s)) if (o in eligible) {
                witnessed.add(s to o)
                next.add(o)
            }
            reached.addAll(next)
            frontier = next
        }
        val resultNodes = reached intersect output
        val candidates = if (network.closure == NetworkClosure.TRAVERSED) witnessed
            else nodes.flatMap { s -> data.links.getValue(s).map { s to it } }
        val edges = candidates.filter { it.first in resultNodes && it.second in resultNodes }
            .map { KastorLensProviderBridge.Edge(it.first, Iri(link), it.second) }.toSet()
        return KastorLensProviderBridge.Graph(resultNodes, edges)
    }

    /** Guards compile inline or inside EXISTS depending on their shape; both must agree with the walk. */
    private fun verifyGuardedNetworks(factory: () -> RdfRepository) {
        val data = data()
        val b = LensDsl.type(classes[1])
        val c = LensDsl.type(classes[2])
        val guards = listOf(
            null,
            b,
            LensDsl.or(b, c),
            LensDsl.filter(b, adult),
            LensDsl.exists(LensDsl.or(a, b), link, c),
            LensDsl.minus(LensDsl.and(b, c), LensDsl.eq(b, tag, Value.Iri(tags[0])))
        )
        factory().use { repo ->
            load(repo, data)
            val bridge = KastorLensProviderBridge(repo)
            var nonTrivial = 0
            for (traversal in guards) for (output in guards) for (depth in 0..3)
                for (closure in NetworkClosure.entries) {
                    val network = NetworkLens(
                        seeds = a, predicate = link, maxDepth = depth,
                        traversalNode = traversal, outputNode = output, closure = closure
                    )
                    val compiled = NetworkLensCompiler().compile(network)
                    val expected = walkOracle(network, data)
                    if (expected.edges.isNotEmpty()) nonTrivial++
                    assertEquals(expected, bridge.network(compiled.nodes, compiled.edges)) {
                        "network=$network nodes=${compiled.nodes} edges=${compiled.edges}"
                    }
                }
            assertTrue(nonTrivial > 40) { "Fixture too sparse to exercise network guards: $nonTrivial" }
        }
    }

    @Test fun jenaGuardedNetworksMatchWalk() = verifyGuardedNetworks { JenaRepository.MemoryRepository() }
    @Test fun rdf4jGuardedNetworksMatchWalk() = verifyGuardedNetworks { Rdf4jRepository.MemoryRepository() }

    @Test fun optimizationIsIdempotent() {
        val optimizer = LensOptimizer()
        for (lens in randomLenses(400).filter { size(it) <= 60 }) {
            val once = optimizer.optimize(lens).lens
            assertEquals(once, optimizer.optimize(once).lens) { "lens=$lens" }
        }
    }

    @Test fun preparedLensRunsRepeatedlyAndEnforcesLimits() {
        val data = data()
        JenaRepository.MemoryRepository().use { repo ->
            load(repo, data)
            val definitions = lensDefinitions {
                define(ex("adults"), lens { type(classes[0]); where { path(age) gt 18 } })
            }
            val executor = KastorRdfLensExecutor(repo, definitions)
            val prepared = executor.prepareValidated(Iri(ex("adults")))
            val expected = KastorRdfLensExecutor.Result.Nodes(evaluate(LensDsl.filter(a, adult), data))
            assertTrue(expected.nodes.size > 1)
            assertEquals(expected, prepared.execute())
            assertEquals(expected, prepared.execute())
            assertEquals(expected, executor.execute(Iri(ex("adults"))))
            assertEquals(
                "Node result exceeds limit 1",
                runCatching { prepared.execute(KastorLensProviderBridge.Limits(maxNodes = 1)) }
                    .exceptionOrNull()?.message
            )
        }
    }
}
