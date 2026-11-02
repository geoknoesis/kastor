package com.geoknoesis.kastor.rdf

import java.io.InputStream

/**
 * URL loading for the providers' own loads (a SPARQL `LOAD`): the code path of [Rdf.parseFromUrl], with its
 * [UrlLoadOptions] (schemes, address policy checked before every connection and redirect, size and time caps).
 */
@InternalKastorApi
object UrlLoadSupport {
    /**
     * Opens [url] under [options] and runs [read] on its body, with the final URL (after redirects) and the declared
     * content type (null when there is none). The body is closed afterwards.
     */
    fun <T> read(url: String, options: UrlLoadOptions, read: (body: InputStream, finalUrl: String, contentType: String?) -> T): T {
        val body = openRdfUrlStream(url, null, options)
        return body.read { read(it, body.baseIri, body.connection.contentType) }
    }
}
