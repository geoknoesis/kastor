package fixture

import com.geoknoesis.kastor.gen.annotations.Rdf
import com.geoknoesis.kastor.gen.runtime.*
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import fixture.domain.Human

@Rdf(iri = "https://example.test/Source")
interface Source {
    @Rdf(iri = "https://example.test/next")
    val next: Late
}

fun main() {
    val graph = MemoryGraph()
    val source = Iri("https://example.test/source")
    val late = Iri("https://example.test/late")
    val name = Iri("https://example.test/name")
    graph.addTriple(RdfTriple(source, Iri("https://example.test/next"), late))
    graph.addTriple(RdfTriple(late, name, Literal("late-round")))
    check(OntoMapper.materialize(RdfRef(source, graph), Source::class.java).next.name == "late-round")
    check(OntoMapper.materialize(RdfRef(late, graph), Human::class.java).name == "late-round")
    println("Published plugin marker, generated domain, and cooperating KSP rounds: OK")
}
