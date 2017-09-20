package org.lwjgl.vulkangen

private val MERGE_SEQUENCE = "\"\"\" mergeLargeLiteral \"\"\""

private val Char.modifiedUTF8Length: Int
    get() {
        val i = this.toInt()
        return when {
            i == 0    -> 2
            i < 0x7F  -> 1
            i < 0x7FF -> 2
            else      -> 3
        }
    }

// Java classes cannot have string literals longer than 0xFFFF characters.
internal fun String.splitLargeLiteral(): String {
    val bytes = this.asSequence().sumBy { it.modifiedUTF8Length }
    if (bytes <= 0xFFFF) {
        return this
    }

    val batchCount = (bytes + 0xFFFE) / 0xFFFF
    val builder = StringBuilder(this.length + (batchCount - 1) * MERGE_SEQUENCE.length)

    var batchStart = 0
    var batchBytes = 0
    for (batchEnd in 0 until this.length) {
        batchBytes += this[batchEnd].modifiedUTF8Length
        if (0xFFFF < batchBytes) {
            builder.append(this, batchStart, batchEnd)
            builder.append(MERGE_SEQUENCE)

            batchStart = batchEnd
            batchBytes -= 0xFFFF
        }
    }

    return builder
        .append(this, batchStart, this.length)
        .toString()
}
