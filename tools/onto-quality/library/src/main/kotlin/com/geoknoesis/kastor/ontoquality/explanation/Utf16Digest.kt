package com.geoknoesis.kastor.ontoquality.explanation

import java.security.MessageDigest

/** Hash raw code units without charset replacement of distinct unpaired surrogates. */
internal fun MessageDigest.updateUtf16Units(text: String, buffer: ByteArray) {
    require(buffer.size >= 2)
    var offset = 0
    while (offset < text.length) {
        val count = minOf(buffer.size / 2, text.length - offset)
        for (i in 0 until count) {
            val unit = text[offset + i].code
            buffer[2 * i] = (unit ushr 8).toByte()
            buffer[2 * i + 1] = unit.toByte()
        }
        update(buffer, 0, count * 2)
        offset += count
    }
}
