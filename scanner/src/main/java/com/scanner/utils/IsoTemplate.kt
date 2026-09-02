package com.scanner.utils

object IsoTemplate {
    private const val SDK_HEADER = 24
    private const val STD_HEADER = 28
    private const val CBEFF_OFFSET = 12
    private const val CBEFF_SIZE = 4
    private const val VIEW_HEADER = 4
    private const val MINUTIA = 6

    /** Standards-conformant form, for storage, upload and export. */
    fun conformant(template: ByteArray): ByteArray {
        if (headerSize(template) != SDK_HEADER) return template
        val out = ByteArray(template.size + CBEFF_SIZE)
        template.copyInto(out, 0, 0, CBEFF_OFFSET)
        // CBEFF Product Identifier left zero: unregistered product.
        template.copyInto(out, CBEFF_OFFSET + CBEFF_SIZE, CBEFF_OFFSET)
        writeLength(out)
        return out
    }

    /** The SDK's own form, which is the only layout its matcher accepts. */
    fun sdk(template: ByteArray): ByteArray {
        if (headerSize(template) != STD_HEADER) return template
        val out = ByteArray(template.size - CBEFF_SIZE)
        template.copyInto(out, 0, 0, CBEFF_OFFSET)
        template.copyInto(out, CBEFF_OFFSET, CBEFF_OFFSET + CBEFF_SIZE)
        writeLength(out)
        return out
    }

    /** Header size implied by the record's own arithmetic, or null if neither fits. */
    private fun headerSize(t: ByteArray): Int? = listOf(STD_HEADER, SDK_HEADER).firstOrNull { hdr ->
        t.size > hdr + VIEW_HEADER &&
            hdr + VIEW_HEADER + (t[hdr + 3].toInt() and 0xFF) * MINUTIA + 2 == t.size
    }

    private fun writeLength(t: ByteArray) {
        t[8]  = (t.size ushr 24).toByte()
        t[9]  = (t.size ushr 16).toByte()
        t[10] = (t.size ushr 8).toByte()
        t[11] = t.size.toByte()
    }
}