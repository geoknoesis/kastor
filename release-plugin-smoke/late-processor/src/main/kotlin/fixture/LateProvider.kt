package fixture

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated

class LateProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
        private var generated = false
        override fun process(resolver: Resolver): List<KSAnnotated> {
            if (!generated) {
                generated = true
                environment.codeGenerator.createNewFile(Dependencies(true, *resolver.getAllFiles().toList().toTypedArray()), "fixture", "Late").writer().use {
                    it.write("""
                        package fixture
                        import com.geoknoesis.kastor.gen.annotations.Rdf
                        @Rdf(iri = "https://example.test/Late")
                        interface Late {
                            @Rdf(iri = "https://example.test/name")
                            val name: String
                        }
                    """.trimIndent())
                }
            }
            return emptyList()
        }
    }
}
