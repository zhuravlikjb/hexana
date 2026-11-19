package org.jetbrains.hexana.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataInspectorFormatTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun List<DataInspectorGroup>.value(label: String): String? =
        flatMap { it.rows }.firstOrNull { it.label == label }?.value

    @Test
    fun integersInBothByteOrders() {
        val raw = bytes(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x88)
        val le = dataInspectorGroups(raw, offset = 0, littleEndian = true)
        assertEquals("1", le.value("Int8"))
        assertEquals("513", le.value("UInt16"))
        assertEquals("67305985", le.value("UInt32"))
        assertEquals("-8644934341102468607", le.value("Int64"))
        assertEquals("9801809732607083009", le.value("UInt64"))

        val be = dataInspectorGroups(raw, offset = 0, littleEndian = false)
        assertEquals("258", be.value("UInt16"))
        assertEquals("16909060", be.value("Int32"))
        assertEquals("72623859790382984", be.value("Int64"))
    }

    @Test
    fun unsignedValuesOfAllOnes() {
        val groups = dataInspectorGroups(ByteArray(8) { -1 }, offset = 0, littleEndian = true)
        assertEquals("-1", groups.value("Int8"))
        assertEquals("255", groups.value("UInt8"))
        assertEquals("65535", groups.value("UInt16"))
        assertEquals("-1", groups.value("Int32"))
        assertEquals("4294967295", groups.value("UInt32"))
        assertEquals("18446744073709551615", groups.value("UInt64"))
    }

    @Test
    fun floats() {
        // 1.0f = 0x3F800000, 1.0 = 0x3FF0000000000000, both big-endian here.
        val f32 = dataInspectorGroups(bytes(0x3F, 0x80, 0x00, 0x00), offset = 0, littleEndian = false)
        assertEquals(1.0, f32.value("Float32")!!.toDouble())
        assertNull(f32.value("Float64"))

        val f64 = dataInspectorGroups(bytes(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xF0, 0x3F), offset = 0, littleEndian = true)
        assertEquals(1.0, f64.value("Float64")!!.toDouble())
    }

    @Test
    fun unsignedLeb128() {
        // The spec's example: 624485 = E5 8E 26.
        val leb = decodeLeb128(bytes(0xE5, 0x8E, 0x26, 0xFF), signed = false)
        assertEquals(624485L, leb.value)
        assertEquals(3, leb.byteCount)
        assertFalse(leb.incomplete)
        assertEquals(
            "624485 (3 bytes)",
            dataInspectorGroups(bytes(0xE5, 0x8E, 0x26), offset = 0, littleEndian = true).value("ULEB128"),
        )
    }

    @Test
    fun signedLeb128() {
        // -123456 = C0 BB 78.
        val leb = decodeLeb128(bytes(0xC0, 0xBB, 0x78), signed = true)
        assertEquals(-123456L, leb.value)
        assertEquals(3, leb.byteCount)
        assertEquals("-1 (1 byte)", dataInspectorGroups(bytes(0x7F), offset = 0, littleEndian = true).value("SLEB128"))
        assertEquals("127 (1 byte)", dataInspectorGroups(bytes(0x7F), offset = 0, littleEndian = true).value("ULEB128"))
    }

    @Test
    fun leb128MaxAndOverflow() {
        val max = bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01)
        val u = decodeLeb128(max, signed = false)
        assertEquals(ULong.MAX_VALUE, u.value.toULong())
        assertFalse(u.overflow)

        val tooBig = max.copyOf().also { it[9] = 0x02 }
        assertTrue(decodeLeb128(tooBig, signed = false).overflow)

        val minSigned = bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x7F)
        val s = decodeLeb128(minSigned, signed = true)
        assertEquals(Long.MIN_VALUE, s.value)
        assertFalse(s.overflow)
    }

    @Test
    fun incompleteLeb128() {
        assertTrue(decodeLeb128(bytes(0x80, 0x80), signed = false).incomplete)
        assertTrue(decodeLeb128(ByteArray(12) { 0x80.toByte() }, signed = true).incomplete)
        assertEquals("incomplete", dataInspectorGroups(bytes(0x80), offset = 0, littleEndian = true).value("ULEB128"))
    }

    @Test
    fun hexOfCursorWindowShowsTheWidestType() {
        val raw = ByteArray(20) { it.toByte() }
        val groups = dataInspectorGroups(raw, offset = 0x1F, littleEndian = true)
        assertEquals("0x1F", groups.value("Offset"))
        assertEquals("00 01 02 03 04 05 06 07", groups.value("Hex"))
        assertNull(groups.value("Length"))
    }

    @Test
    fun selectionLimitsRepresentationsToSelectedBytes() {
        val groups = dataInspectorGroups(bytes(0xE5, 0x8E, 0x26), offset = 4, littleEndian = true, selectionLength = 3)
        assertEquals("3 bytes", groups.value("Length"))
        assertEquals("E5 8E 26", groups.value("Hex"))
        assertEquals("36581", groups.value("UInt16"))
        assertNull(groups.value("UInt32"))
        assertNull(groups.value("Float32"))
        assertEquals("624485 (3 bytes)", groups.value("ULEB128"))
    }

    @Test
    fun longSelectionHexIsTruncated() {
        val groups = dataInspectorGroups(ByteArray(64), offset = 0, littleEndian = true, selectionLength = 1000)
        assertEquals((List(DATA_INSPECTOR_HEX_LIMIT) { "00" }.joinToString(" ")) + " …", groups.value("Hex"))
    }

    @Test
    fun textRows() {
        val groups = dataInspectorGroups("hé!".encodeToByteArray(), offset = 0, littleEndian = true)
        assertEquals("01101000", groups.value("Binary"))
        assertEquals("150", groups.value("Octal"))
        assertEquals("h", groups.value("ASCII"))
        assertEquals("hé!", groups.value("UTF-8"))
        assertEquals("·", dataInspectorGroups(bytes(0x00), offset = 0, littleEndian = true).value("ASCII"))
        assertNull(dataInspectorGroups(bytes(0x00), offset = 0, littleEndian = true).value("UTF-8"))
    }

    @Test
    fun emptyInputHasNoGroups() {
        assertTrue(dataInspectorGroups(ByteArray(0), offset = 0, littleEndian = true).isEmpty())
    }
}
