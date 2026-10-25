package com.geoknoesis.kastor.rdf.sparql

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** A response body that fails with [IOException] once more than [limit] bytes are read from it. */
internal class BoundedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L

    private fun counted(n: Long): Long {
        if (n > 0) {
            count += n
            if (count > limit) throw IOException("SPARQL response exceeds $limit bytes")
        }
        return n
    }

    override fun read(): Int {
        val b = `in`.read()
        if (b >= 0) counted(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = counted(`in`.read(b, off, len).toLong()).toInt()

    override fun skip(n: Long): Long = counted(`in`.skip(n))
}
