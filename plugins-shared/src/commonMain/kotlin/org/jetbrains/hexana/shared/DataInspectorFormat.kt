package org.jetbrains.hexana.shared

/**
 * The UI-free model behind the hex viewers' Data Inspector side panel (IntelliJ and VS Code / web).
 *
 * Hosts read up to [DATA_INSPECTOR_WINDOW] bytes — the selected bytes when a multi-byte selection
 * exists, otherwise the bytes starting at the cursor — and render the returned groups verbatim, so
 * every host shows the same decodings with the same labels.
 */

/** The most bytes the inspector looks at: enough for a UTF-8 preview, LEB128 (≤ 10) and 64-bit types. */
const val DATA_INSPECTOR_WINDOW: Int = 64

/** At most this many bytes are spelled out in the `Hex` row; longer inputs end with an ellipsis. */
const val DATA_INSPECTOR_HEX_LIMIT: Int = 16

/** A LEB128 encoding of a 64-bit value takes at most this many bytes. */
private const val MAX_LEB128_BYTES = 10

data class DataInspectorRow(val label: String, val value: String)

data class DataInspectorGroup(val title: String, val rows: List<DataInspectorRow>)

/**
 * Decodes [bytes] into the inspector's groups.
 *
 * @param bytes the bytes to inspect, starting at [offset]: the selection (capped at
 *   [DATA_INSPECTOR_WINDOW]) when [selectionLength] is set, otherwise the window after the cursor.
 * @param offset the displayed offset of `bytes[0]`.
 * @param selectionLength total length of the selection, or `null` when only a cursor is set. With a
 *   selection, only representations that fit inside the selected bytes are shown.
 */
fun dataInspectorGroups(
    bytes: ByteArray,
    offset: Long,
    littleEndian: Boolean,
    selectionLength: Long? = null,
): List<DataInspectorGroup> {
    if (bytes.isEmpty()) return emptyList()
    val groups = ArrayList<DataInspectorGroup>(5)

    val position = ArrayList<DataInspectorRow>(3)
    position += DataInspectorRow("Offset", "0x" + offset.toString(16).uppercase())
    if (selectionLength != null) position += DataInspectorRow("Length", "$selectionLength bytes")
    // With a cursor, show the 8 bytes the widest types read; with a selection, the selection itself.
    val hexBytes = if (selectionLength != null) bytes else bytes.copyOf(minOf(bytes.size, 8))
    position += DataInspectorRow("Hex", dataInspectorHex(hexBytes, truncated = selectionLength != null && selectionLength > hexBytes.size))
    groups += DataInspectorGroup("Position", position)

    groups += DataInspectorGroup("Integers", integerRows(bytes, littleEndian))
    floatRows(bytes, littleEndian).takeIf { it.isNotEmpty() }?.let { groups += DataInspectorGroup("Floating point", it) }
    groups += DataInspectorGroup(
        "LEB128",
        listOf(
            DataInspectorRow("ULEB128", formatLeb128(decodeLeb128(bytes, signed = false), signed = false)),
            DataInspectorRow("SLEB128", formatLeb128(decodeLeb128(bytes, signed = true), signed = true)),
        ),
    )

    val text = ArrayList<DataInspectorRow>(4)
    val b = bytes[0].toInt() and 0xFF
    text += DataInspectorRow("Binary", b.toString(2).padStart(8, '0'))
    text += DataInspectorRow("Octal", b.toString(8))
    text += DataInspectorRow("ASCII", if (b in 0x20..0x7E) b.toChar().toString() else "·")
    decodeUtf8Prefix(bytes).takeIf { it.isNotEmpty() }?.let { text += DataInspectorRow("UTF-8", it) }
    groups += DataInspectorGroup("Text", text)
    return groups
}

/** `12 34 AB …` — upper-case byte pairs, at most [DATA_INSPECTOR_HEX_LIMIT] of them. */
fun dataInspectorHex(bytes: ByteArray, truncated: Boolean = false): String {
    val shown = minOf(bytes.size, DATA_INSPECTOR_HEX_LIMIT)
    val sb = StringBuilder(shown * 3 + 2)
    for (i in 0 until shown) {
        if (i > 0) sb.append(' ')
        val v = bytes[i].toInt() and 0xFF
        sb.append(HEX_DIGITS[v shr 4]).append(HEX_DIGITS[v and 0xF])
    }
    if (truncated || bytes.size > shown) sb.append(" …")
    return sb.toString()
}

private const val HEX_DIGITS = "0123456789ABCDEF"

private fun integerRows(bytes: ByteArray, littleEndian: Boolean): List<DataInspectorRow> {
    val rows = ArrayList<DataInspectorRow>(8)
    rows += DataInspectorRow("Int8", bytes[0].toString())
    rows += DataInspectorRow("UInt8", (bytes[0].toInt() and 0xFF).toString())
    if (bytes.size >= 2) {
        val v = readFixed(bytes, 2, littleEndian)
        rows += DataInspectorRow("Int16", v.toShort().toString())
        rows += DataInspectorRow("UInt16", v.toString())
    }
    if (bytes.size >= 4) {
        val v = readFixed(bytes, 4, littleEndian)
        rows += DataInspectorRow("Int32", v.toInt().toString())
        rows += DataInspectorRow("UInt32", v.toString())
    }
    if (bytes.size >= 8) {
        val v = readFixed(bytes, 8, littleEndian)
        rows += DataInspectorRow("Int64", v.toString())
        rows += DataInspectorRow("UInt64", v.toULong().toString())
    }
    return rows
}

private fun floatRows(bytes: ByteArray, littleEndian: Boolean): List<DataInspectorRow> {
    val rows = ArrayList<DataInspectorRow>(2)
    if (bytes.size >= 4) rows += DataInspectorRow("Float32", Float.fromBits(readFixed(bytes, 4, littleEndian).toInt()).toString())
    if (bytes.size >= 8) rows += DataInspectorRow("Float64", Double.fromBits(readFixed(bytes, 8, littleEndian)).toString())
    return rows
}

/** Reads a [width]-byte unsigned integer (as the low bits of a [Long]) from the start of [bytes]. */
private fun readFixed(bytes: ByteArray, width: Int, littleEndian: Boolean): Long {
    var result = 0L
    for (i in 0 until width) {
        val b = bytes[if (littleEndian) width - 1 - i else i].toLong() and 0xFF
        result = (result shl 8) or b
    }
    return result
}

/** A LEB128 decoded from the start of the inspected bytes. */
data class DataInspectorLeb128(
    val value: Long,
    val byteCount: Int,
    /** No terminating byte (continuation bit clear) within the available bytes or the 10-byte limit. */
    val incomplete: Boolean,
    /** Terminated, but the encoded value does not fit a 64-bit integer. */
    val overflow: Boolean,
)

/** Decodes an unsigned ([signed] = false) or signed LEB128 from the start of [bytes]. */
fun decodeLeb128(bytes: ByteArray, signed: Boolean): DataInspectorLeb128 {
    var result = 0L
    var shift = 0
    var count = 0
    var last = 0
    val limit = minOf(bytes.size, MAX_LEB128_BYTES)
    while (count < limit) {
        last = bytes[count].toInt() and 0xFF
        count++
        if (shift < 64) result = result or ((last and 0x7F).toLong() shl shift)
        shift += 7
        if (last and 0x80 == 0) {
            if (signed && shift < 64 && last and 0x40 != 0) result = result or (-1L shl shift)
            // The 10th byte carries only bit 63: anything else in it is lost.
            val overflow = count == MAX_LEB128_BYTES &&
                if (signed) last != 0x00 && last != 0x7F else last > 0x01
            return DataInspectorLeb128(result, count, incomplete = false, overflow = overflow)
        }
    }
    return DataInspectorLeb128(result, count, incomplete = true, overflow = false)
}

private fun formatLeb128(leb: DataInspectorLeb128, signed: Boolean): String = when {
    leb.incomplete -> "incomplete"
    else -> {
        val value = if (signed) leb.value.toString() else leb.value.toULong().toString()
        val size = if (leb.byteCount == 1) "1 byte" else "${leb.byteCount} bytes"
        if (leb.overflow) "$value ($size, overflow)" else "$value ($size)"
    }
}

/**
 * Decodes a valid UTF-8 prefix from [data], stopping at the first byte that cannot start or continue
 * a valid UTF-8 sequence, at a control character other than tab / LF / CR, or after 32 UTF-16 units.
 */
fun decodeUtf8Prefix(data: ByteArray): String {
    val sb = StringBuilder()
    var i = 0
    while (i < data.size) {
        val b0 = data[i].toInt() and 0xFF
        val seqLen: Int
        val lead: Int
        when {
            b0 <= 0x7F -> { seqLen = 1; lead = b0 }
            b0 in 0xC2..0xDF -> { seqLen = 2; lead = b0 and 0x1F }
            b0 in 0xE0..0xEF -> { seqLen = 3; lead = b0 and 0x0F }
            b0 in 0xF0..0xF4 -> { seqLen = 4; lead = b0 and 0x07 }
            else -> break
        }
        if (i + seqLen > data.size) break
        var cp = lead
        var valid = true
        for (j in 1 until seqLen) {
            val cont = data[i + j].toInt() and 0xFF
            if (cont and 0xC0 != 0x80) { valid = false; break }
            cp = (cp shl 6) or (cont and 0x3F)
        }
        if (!valid) break
        // Reject overlong encodings, surrogates and out-of-range code points.
        if (seqLen == 2 && cp < 0x80) break
        if (seqLen == 3 && cp < 0x800) break
        if (seqLen == 4 && cp < 0x10000) break
        if (cp in 0xD800..0xDFFF) break
        if (cp > 0x10FFFF) break
        if (cp < 0x20 && cp != 0x09 && cp != 0x0A && cp != 0x0D) break
        if (cp <= 0xFFFF) {
            sb.append(cp.toChar())
        } else {
            val adjusted = cp - 0x10000
            sb.append((0xD800 + (adjusted shr 10)).toChar())
            sb.append((0xDC00 + (adjusted and 0x3FF)).toChar())
        }
        i += seqLen
        if (sb.length >= 32) break
    }
    return sb.toString()
}
