package com.geoknoesis.kastor.rdf.hermit

import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.formats.TurtleDocumentFormat
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource
import org.semanticweb.owlapi.io.StringDocumentSource
import org.semanticweb.owlapi.model.IRI
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy
import org.semanticweb.owlapi.model.OWLOntology
import org.semanticweb.owlapi.model.OWLOntologyCreationException
import org.semanticweb.owlapi.model.OWLOntologyFactory
import org.semanticweb.owlapi.model.OWLOntologyID
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration
import org.semanticweb.owlapi.model.OWLOntologyManager

/**
 * How the HermiT reasoner hands a graph to the OWL API: as one in-memory Turtle document, **never following
 * `owl:imports`**.
 *
 * The OWL API resolves every import by default: it dereferences the IRI (HTTP, with redirects, or `file:`) and merges
 * what it finds into the imports closure. An import in the reasoned graph would then let the graph's data choose a
 * request (server-side request forgery), read a local file into the inferred triples, or hold a load past the caller's
 * deadline. So the manager made by [isolatedManager]:
 * - loads only document sources created by [inputDocument]: every ontology factory refuses any other source, so no
 *   import is ever fetched or read, whatever its IRI;
 * - ignores imports it cannot load ([MissingImportHandlingStrategy.SILENT]) instead of failing the call, and does not
 *   follow redirects.
 *
 * The `owl:imports` declarations stay in the loaded ontology; only their targets are not loaded. A caller that wants
 * an imported ontology reasoned over merges it into the graph first, fetched under its own policy (for example
 * `Rdf.parseFromUrl` with `UrlLoadOptions`).
 */
internal object HermitOntologyLoading {

    /** The base IRI of the serialized input document. */
    private val INPUT_IRI: IRI = IRI.create("urn:kastor:hermit-input")

    /** A new manager that loads only [inputDocument] sources and never resolves an import. */
    fun isolatedManager(): OWLOntologyManager = OWLManager.createOWLOntologyManager().apply {
        val factories = ontologyFactories.map { InputOnlyFactory(it) }.toSet()
        setOntologyFactories(factories)
        // Loading reads its configuration from the configurator; the loader configuration is set for any other path.
        ontologyConfigurator
            .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
            .setFollowRedirects(false)
        ontologyLoaderConfiguration = ontologyConfigurator.buildLoaderConfiguration()
    }

    /** [turtle] as a document an [isolatedManager] loads; its format is fixed, so no other parser is tried. */
    fun inputDocument(turtle: String): OWLOntologyDocumentSource = InputDocument(turtle)

    private class InputDocument(turtle: String) :
        StringDocumentSource(turtle, INPUT_IRI, TurtleDocumentFormat(), "text/turtle")

    /**
     * [delegate], except that it loads nothing but an [InputDocument]: any other source (an import's IRI) is refused
     * before it is opened. Creating an empty ontology reads nothing and is delegated unchanged.
     *
     * The refusal is an [OWLOntologyCreationException] thrown by [loadOWLOntology], because that is what the
     * [MissingImportHandlingStrategy.SILENT] strategy absorbs; declining in [canAttemptLoading] instead would make the
     * manager throw an unchecked "no factory" exception that fails the whole load.
     */
    private class InputOnlyFactory(private val delegate: OWLOntologyFactory) : OWLOntologyFactory by delegate {

        override fun canAttemptLoading(source: OWLOntologyDocumentSource): Boolean =
            source !is InputDocument || delegate.canAttemptLoading(source)

        override fun loadOWLOntology(
            manager: OWLOntologyManager,
            source: OWLOntologyDocumentSource,
            handler: OWLOntologyFactory.OWLOntologyCreationHandler,
            configuration: OWLOntologyLoaderConfiguration,
        ): OWLOntology {
            if (source !is InputDocument) throw OWLOntologyCreationException("Kastor's HermiT reasoner does not load ${source.documentIRI}")
            return delegate.loadOWLOntology(manager, source, handler, configuration)
        }

        override fun createOWLOntology(
            manager: OWLOntologyManager,
            id: OWLOntologyID,
            documentIRI: IRI,
            handler: OWLOntologyFactory.OWLOntologyCreationHandler,
        ): OWLOntology = delegate.createOWLOntology(manager, id, documentIRI, handler)
    }
}
