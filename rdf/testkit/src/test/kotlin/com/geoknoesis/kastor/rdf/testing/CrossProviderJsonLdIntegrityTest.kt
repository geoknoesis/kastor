package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A JSON-LD parse never loses a statement silently. JSON-LD's toRdf algorithm skips every statement whose subject,
 * predicate, object, datatype or graph name is not an absolute IRI (or a blank node), and every value whose language
 * tag is not well-formed, with a logged warning at most. So whatever a document's own context does (`"@base": null`,
 * a relative `@base` or `@vocab`, scoped contexts, keyword aliases, `@reverse`, `@nest`, `@included`, containers,
 * keys its context leaves undefined),
 * each parse, on both providers, with and without a base IRI, through every parse API, must either produce every
 * statement of the document or fail with [RdfFormatException].
 *
 * Each case gives the outcome without a base IRI and with the base `http://b/`: [Refuse] (must fail), [Parse] (must
 * produce exactly that many triples), or [Either] (fail, or produce exactly that many).
 */
class CrossProviderJsonLdIntegrityTest {
    sealed interface Outcome
    object Refuse : Outcome { override fun toString() = "refused" }
    data class Parse(val triples: Int) : Outcome
    data class Either(val triples: Int) : Outcome

    /** One document (`'` stands for `"`). [named]: it has a named graph, which the graph parses refuse by design. */
    class Case(val name: String, json: String, val withoutBase: Outcome, val withBase: Outcome, val named: Boolean = false) {
        val doc = json.replace('\'', '"')
    }

    companion object {
        private const val BASE = "http://b/"

        val CASES = listOf(
            // "@base": null in every place a context can say it.
            Case("base null", "{'@context':{'@base':null},'@id':'s','http://ex/p':{'@id':'o'}}", Refuse, Refuse),
            Case("base null, last of a context array", "{'@context':[{'@base':'http://ex/'},{'@base':null}],'@id':'s','http://ex/p':'v'}", Refuse, Refuse),
            Case("base null in an embedded node context", "{'@id':'http://ex/s','http://ex/p':{'@context':{'@base':null},'@id':'o'}}", Refuse, Refuse),
            Case("base null, relative graph name", "{'@context':{'@base':null},'@id':'g','@graph':[{'@id':'http://ex/s','http://ex/p':'v'}]}", Refuse, Refuse, named = true),
            Case("base null, one relative object among absolute statements", "{'@context':{'@base':null},'@id':'http://ex/s','http://ex/p':{'@id':'o'},'http://ex/q':'v'}", Refuse, Refuse),
            Case("base null, relative type", "{'@context':{'@base':null},'@id':'http://ex/s','@type':'T'}", Refuse, Refuse),
            Case("base null, relative datatype", "{'@context':{'@base':null},'@id':'http://ex/s','http://ex/p':{'@value':'v','@type':'dt'}}", Refuse, Refuse),
            Case("base null, @type @id coercion", "{'@context':{'@base':null,'p':{'@id':'http://ex/p','@type':'@id'}},'@id':'http://ex/s','p':'o'}", Refuse, Refuse),
            Case("base null, @type @vocab coercion", "{'@context':{'@base':null,'p':{'@id':'http://ex/p','@type':'@vocab'}},'@id':'http://ex/s','p':'o'}", Refuse, Refuse),
            Case("base null in a property-scoped context", "{'@context':{'@base':'http://a/','k':{'@id':'http://ex/k','@context':{'@base':null}}},'@id':'http://ex/s','k':{'@id':'o','http://ex/p':'v'}}", Refuse, Refuse),
            Case("base null in a type-scoped context", "{'@context':{'T':{'@id':'http://ex/T','@context':{'@base':null}}},'@id':'http://a/s','@type':'T','http://ex/p':{'@id':'o'}}", Refuse, Refuse),
            Case("base null, second node of a top-level array", "[{'@id':'http://ex/a','http://ex/p':'v'},{'@context':{'@base':null},'@id':'s','http://ex/p':'v'}]", Refuse, Refuse),
            Case("base null, keyword aliases", "{'@context':{'@base':null,'id':'@id','type':'@type'},'id':'s','type':'http://ex/T','http://ex/p':'v'}", Refuse, Refuse),
            Case("base null, @reverse", "{'@context':{'@base':null},'@id':'http://ex/s','@reverse':{'http://ex/p':{'@id':'o'}}}", Refuse, Refuse),
            Case("base null, reverse term", "{'@context':{'@base':null,'r':{'@reverse':'http://ex/p'}},'@id':'http://ex/s','r':{'@id':'o'}}", Refuse, Refuse),
            Case("base null, @nest", "{'@context':{'@base':null,'n':'@nest'},'@id':'http://ex/s','n':{'http://ex/p':{'@id':'o'}}}", Refuse, Refuse),
            Case("base null, @list", "{'@context':{'@base':null},'@id':'http://ex/s','http://ex/p':{'@list':[{'@id':'o'}]}}", Refuse, Refuse),
            Case("base null, @included", "{'@context':{'@base':null},'@id':'http://ex/s','http://ex/p':'v','@included':[{'@id':'o','http://ex/p':'w'}]}", Refuse, Refuse),
            Case("base null, @graph container", "{'@context':{'g':{'@id':'http://ex/g','@container':'@graph','@context':{'@base':null}}},'@id':'http://ex/s','g':{'@id':'o','http://ex/p':'v'}}", Refuse, Refuse, named = true),
            Case("base null, @id container", "{'@context':{'@base':null,'m':{'@id':'http://ex/m','@container':'@id'}},'@id':'http://ex/s','m':{'o':{'http://ex/p':'v'}}}", Refuse, Refuse),
            Case("base null, @type container", "{'@context':{'@base':null,'m':{'@id':'http://ex/m','@container':'@type'}},'@id':'http://ex/s','m':{'T':{'@id':'http://ex/o'}}}", Refuse, Refuse),
            Case("base null with an empty vocabulary", "{'@context':{'@base':null,'@vocab':''},'@id':'http://ex/s','p':'v'}", Refuse, Refuse),
            Case("base null with an empty vocabulary, datatype", "{'@context':{'@base':null,'@vocab':''},'@id':'http://ex/s','http://ex/p':{'@value':'v','@type':'dt'}}", Refuse, Refuse),
            Case("base null, then @context null", "{'@context':[{'@base':null},null],'@id':'s','http://ex/p':'v'}", Refuse, Parse(1)),
            // Other ways to set the base.
            Case("relative base", "{'@context':{'@base':'rel/'},'@id':'s','http://ex/p':'v'}", Refuse, Parse(1)),
            Case("absolute base", "{'@context':{'@base':'http://a/'},'@id':'s','http://ex/p':'v'}", Parse(1), Parse(1)),
            Case("context reset by null", "{'@context':[{'@base':'http://a/'},null],'@id':'s','http://ex/p':'v'}", Refuse, Parse(1)),
            Case("remote context", "{'@context':'http://ctx.example/c.jsonld','@id':'http://ex/s','http://ex/p':'v'}", Refuse, Refuse),
            Case("imported context", "{'@context':{'@import':'http://ctx.example/c.jsonld','@base':null},'@id':'s','http://ex/p':'v'}", Refuse, Refuse),
            // Vocabularies.
            Case("empty vocabulary", "{'@context':{'@vocab':''},'@id':'http://ex/s','p':'v'}", Refuse, Parse(1)),
            Case("relative vocabulary", "{'@context':{'@vocab':'rel#'},'@id':'http://ex/s','p':'v'}", Refuse, Parse(1)),
            Case("absolute vocabulary", "{'@context':{'@vocab':'http://v/'},'@id':'http://ex/s','p':'v','@type':'T'}", Parse(2), Parse(2)),
            Case("undefined term", "{'@id':'http://ex/s','name':'v','http://ex/p':'w'}", Refuse, Refuse),
            Case("vocabulary null", "{'@context':{'@vocab':null},'@id':'http://ex/s','p':'v'}", Refuse, Refuse),
            Case("undefined term in a nested node", "{'@id':'http://ex/s','http://ex/p':{'@id':'http://ex/o','name':'v'}}", Refuse, Refuse),
            Case("undefined term under @reverse", "{'@id':'http://ex/s','@reverse':{'name':{'@id':'http://ex/o'}}}", Refuse, Refuse),
            Case("undefined term under @nest", "{'@context':{'n':'@nest'},'@id':'http://ex/s','n':{'name':'v'}}", Refuse, Refuse),
            Case("keyword-like key", "{'@id':'http://ex/s','@foo':'v','http://ex/p':'w'}", Either(1), Either(1)),
            Case("relative reverse property", "{'@context':{'@vocab':''},'@id':'http://ex/s','@reverse':{'p':{'@id':'http://ex/o'}}}", Refuse, Parse(1)),
            // Relative references of every shape.
            Case("@id ./x", "{'@id':'./x','http://ex/p':'v'}", Refuse, Parse(1)),
            Case("@id #x", "{'@id':'#x','http://ex/p':'v'}", Refuse, Parse(1)),
            Case("@id ?q", "{'@id':'?q','http://ex/p':'v'}", Refuse, Parse(1)),
            Case("@id empty", "{'@id':'','http://ex/p':'v'}", Refuse, Parse(1)),
            Case("@id bare name", "{'@id':'x','http://ex/p':'v'}", Refuse, Parse(1)),
            Case("@id ../x", "{'@id':'../x','http://ex/p':'v'}", Refuse, Parse(1)),
            Case("@id //host/x", "{'@id':'//host/x','http://ex/p':'v'}", Refuse, Parse(1)),
            Case("@id /x", "{'@id':'/x','http://ex/p':'v'}", Refuse, Parse(1)),
            Case("relative type", "{'@id':'http://ex/s','@type':'T'}", Refuse, Parse(1)),
            Case("relative datatype", "{'@id':'http://ex/s','http://ex/p':{'@value':'v','@type':'dt'}}", Refuse, Parse(1)),
            Case("relative @id in a default @graph", "{'@graph':[{'@id':'s','http://ex/p':'v'}]}", Refuse, Parse(1)),
            Case("relative @id in a named @graph", "{'@id':'http://ex/g','@graph':[{'@id':'s','http://ex/p':'v'}]}", Refuse, Parse(1), named = true),
            Case("relative @reverse object", "{'@id':'http://ex/s','@reverse':{'http://ex/p':{'@id':'o'}}}", Refuse, Parse(1)),
            Case("aliased keywords, relative", "{'@context':{'id':'@id','rev':'@reverse'},'id':'http://ex/s','rev':{'http://ex/p':{'id':'o'}}}", Refuse, Parse(1)),
            // Absolute forms that must parse.
            Case("compact IRI with an undefined prefix", "{'@id':'ex:foo','http://ex/p':'v'}", Parse(1), Parse(1)),
            Case("@nest", "{'@context':{'n':'@nest'},'@id':'http://ex/s','n':{'http://ex/p':'v'}}", Parse(1), Parse(1)),
            Case("list", "{'@id':'http://ex/s','http://ex/p':{'@list':['a','b']}}", Parse(5), Parse(5)),
            Case("empty list", "{'@id':'http://ex/s','http://ex/p':{'@list':[]}}", Parse(1), Parse(1)),
            Case("blank nodes and a blank graph name", "{'@id':'_:g','@graph':[{'@id':'_:s','http://ex/p':{'@id':'_:o'}}]}", Parse(1), Parse(1), named = true),
            Case("JSON literal", "{'@id':'http://ex/s','http://ex/p':{'@value':{'a':1},'@type':'@json'}}", Parse(1), Parse(1)),
            Case("property-valued index", "{'@context':{'m':{'@id':'http://ex/m','@container':'@index','@index':'http://ex/i'}},'@id':'http://ex/s','m':{'k':{'@id':'http://ex/o'}}}", Parse(2), Parse(2)),
            Case("well-formed language tag", "{'@id':'http://ex/s','http://ex/p':{'@value':'v','@language':'en-US'}}", Parse(1), Parse(1)),
            // Terms toRdf would skip for another reason than a relative reference.
            Case("ill-formed language tag", "{'@id':'http://ex/s','http://ex/p':{'@value':'v','@language':'not a tag!'}}", Refuse, Refuse),
            Case("language subtag longer than 8", "{'@id':'http://ex/s','http://ex/p':{'@value':'v','@language':'en-abcdefghij'}}", Either(1), Either(1)),
            Case("ill-formed default language", "{'@context':{'@language':'x y'},'@id':'http://ex/s','http://ex/p':'v'}", Refuse, Refuse),
            Case("blank-node predicate", "{'@id':'http://ex/s','_:p':'v'}", Refuse, Refuse),
            Case("IRI with a space", "{'@id':'http://ex/a b','http://ex/p':'v'}", Refuse, Refuse),
            Case("object IRI with a space", "{'@id':'http://ex/s','http://ex/p':{'@id':'http://ex/a b'}}", Refuse, Refuse),
            Case("property IRI with a space", "{'@id':'http://ex/s','http://ex/a b':'v'}", Refuse, Refuse),
            Case("datatype IRI with a space", "{'@id':'http://ex/s','http://ex/p':{'@value':'v','@type':'http://ex/a b'}}", Refuse, Refuse),
            Case("IRI with angle brackets", "{'@id':'http://ex/<x>','http://ex/p':'v'}", Refuse, Refuse),
            Case("IRI a:", "{'@id':'a:','http://ex/p':'v'}", Either(1), Either(1)),
            Case("IRI http://", "{'@id':'http://','http://ex/p':'v'}", Either(1), Either(1)),
            Case("graph name with a space", "{'@id':'http://ex/a b','@graph':[{'@id':'http://ex/s','http://ex/p':'v'}]}", Refuse, Refuse, named = true),
        )
    }

    private sealed interface Result
    private data class Parsed(val triples: Int) : Result
    private data class Refused(val message: String) : Result

    private fun attempt(parse: () -> Int): Result = try {
        Parsed(parse())
    } catch (e: RdfFormatException) {
        Refused(e.message.orEmpty())
    }

    private class Api(val name: String, val graphOnly: Boolean, val parse: (String, String?) -> Int)

    private fun apis(provider: RdfProvider, repository: () -> RdfRepository) = listOf(
        Api("parseGraph", graphOnly = true) { doc, base -> provider.parseGraph(doc.byteInputStream(), "JSON-LD", base).size() },
        Api("openTripleStream", graphOnly = true) { doc, base ->
            provider.openTripleStream(doc.byteInputStream(), "JSON-LD", base).use { it.count() }
        },
        Api("parseDataset", graphOnly = false) { doc, base ->
            repository().use { repo ->
                provider.parseDataset(repo, doc.byteInputStream(), "JSON-LD", base)
                repo.defaultGraph.size() + repo.listGraphs().sumOf { repo.getGraph(it).size() }
            }
        },
    )

    private fun check(case: Case, api: Api, base: String?, expected: Outcome): String? {
        val result = attempt { api.parse(case.doc, base) }
        val outcome = if (api.graphOnly && case.named) Refuse else expected
        val ok = when (outcome) {
            Refuse -> result is Refused
            is Parse -> result == Parsed(outcome.triples)
            is Either -> result is Refused || result == Parsed(outcome.triples)
        }
        if (result is Refused && result.message.contains("x-kastor")) {
            return "${api.name} base=$base: internal name in the message: ${result.message}"
        }
        return if (ok) null else "${api.name} base=$base: expected $outcome, got $result"
    }

    private val providers: List<Pair<RdfProvider, () -> RdfRepository>> = listOf(
        JenaProvider() to { JenaRepository.MemoryRepository() },
        Rdf4jProvider() to { Rdf4jRepository.MemoryRepository() },
    )

    @TestFactory
    fun `JSON-LD statements are never dropped silently`() = providers.flatMap { (provider, repository) ->
        CASES.map { case ->
            DynamicTest.dynamicTest("${provider.id}: ${case.name}") {
                val failures = apis(provider, repository).flatMap { api ->
                    listOfNotNull(check(case, api, null, case.withoutBase), check(case, api, BASE, case.withBase))
                }
                if (failures.isNotEmpty()) fail("${case.doc}\n" + failures.joinToString("\n"))
            }
        }
    }

    @TestFactory
    fun `a refusal names what is wrong`() = providers.map { (provider, _) ->
        DynamicTest.dynamicTest("${provider.id}: messages") {
            val relative = assertRefused(provider, "{'@context':{'@base':null},'@id':'http://ex/s','http://ex/p':{'@id':'sub/o'}}", BASE)
            assertTrue(relative.contains("relative IRI") && relative.contains("'sub/o'"), relative)
            val language = assertRefused(provider, "{'@id':'http://ex/s','http://ex/p':{'@value':'v','@language':'not a tag!'}}", null)
            assertTrue(language.contains("language tag") && language.contains("not a tag!"), language)
            val malformed = assertRefused(provider, "{'@id':'http://ex/a b','http://ex/p':'v'}", null)
            assertTrue(malformed.contains("http://ex/a b"), malformed)
        }
    }

    private fun assertRefused(provider: RdfProvider, json: String, base: String?): String {
        val result = attempt { provider.parseGraph(json.replace('\'', '"').byteInputStream(), "JSON-LD", base).size() }
        return (result as? Refused)?.message ?: fail("expected a refusal of $json, got $result")
    }
}
