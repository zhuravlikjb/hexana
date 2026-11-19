package org.jetbrains.hexana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.io.LargeBytes
import org.jetbrains.hexana.io.readBytes
import org.jetbrains.hexana.shared.DATA_INSPECTOR_WINDOW
import org.jetbrains.hexana.shared.dataInspectorGroups
import org.jetbrains.jewel.ui.component.Text
import java.awt.Cursor

/**
 * A side panel that decodes the selected bytes — or, without a multi-byte selection, the bytes at
 * the cursor — as integers and floats (little- or big-endian), unsigned and signed LEB128, hex,
 * binary, octal, ASCII and UTF-8: the standard "Data Inspector" found in most hex editors. The
 * decodings come from the shared [dataInspectorGroups], so the VS Code / web panel shows the same.
 */
@Composable
internal fun DataInspectorPanel(
    state: HexViewStateHolder,
    bytes: LargeBytes,
) {
    val bytesPerRow by state.bytesPerRow
    val cursor = state.cursorPosition
    val selection = state.selection
    val totalBytes = bytes.size

    val hasSelection = selection.start.line >= 0 && selection.end.line >= 0 &&
        (selection.start.line != selection.end.line || selection.start.lineOffset != selection.end.lineOffset)
    val selectionStart = if (hasSelection) selection.start.getByteOffset(bytesPerRow) else -1L
    val selectionLength = if (hasSelection) {
        (selection.end.getByteOffset(bytesPerRow).coerceAtMost(totalBytes - 1) - selectionStart + 1).takeIf { it > 0 }
    } else {
        null
    }
    val offset = when {
        selectionLength != null -> selectionStart
        cursor.line >= 0 && cursor.lineOffset >= 0 -> cursor.getByteOffset(bytesPerRow)
        else -> -1L
    }

    var littleEndian by remember { mutableStateOf(true) }

    SelectionContainer {
    Column(
        modifier = Modifier
            .width(280.dp)
            .fillMaxHeight()
            .background(HexanaTheme.backgroundColor)
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        val headerStyle = TextStyle(
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = HexanaTheme.hexTextColor,
        )
        val labelStyle = TextStyle(
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = HexanaTheme.hexTextColor.copy(alpha = 0.6f),
        )
        val valueStyle = TextStyle(
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = HexanaTheme.hexTextColor,
        )

        Text("Data Inspector", style = headerStyle.copy(fontSize = 12.sp))
        Spacer(Modifier.height(8.dp))

        EndianSwitch(littleEndian, headerStyle) { littleEndian = it }
        Spacer(Modifier.height(8.dp))

        if (offset < 0 || offset >= totalBytes) {
            Text("No byte selected", style = labelStyle)
            return@Column
        }

        val window = (selectionLength ?: (totalBytes - offset))
            .coerceAtMost(DATA_INSPECTOR_WINDOW.toLong()).toInt()
        val raw = bytes.readBytes(offset, window)
        val groups = dataInspectorGroups(
            bytes = raw,
            offset = offset + state.displayBaseOffset,
            littleEndian = littleEndian,
            selectionLength = selectionLength,
        )
        groups.forEachIndexed { index, group ->
            if (index > 0) Spacer(Modifier.height(6.dp))
            Text(group.title, style = labelStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold))
            for (row in group.rows) InspectorRow(row.label, row.value, labelStyle, valueStyle)
        }
    }
    }
}

@Composable
private fun EndianSwitch(littleEndian: Boolean, style: TextStyle, onToggle: (Boolean) -> Unit) {
    val activeStyle = style.copy(fontWeight = FontWeight.Bold)
    val inactiveStyle = style.copy(color = style.color.copy(alpha = 0.5f))
    val handCursor = Modifier.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Little Endian",
            style = if (littleEndian) activeStyle else inactiveStyle,
            modifier = handCursor.then(Modifier.clickable { onToggle(true) }),
        )
        Text("/", style = style)
        Text(
            "Big Endian",
            style = if (!littleEndian) activeStyle else inactiveStyle,
            modifier = handCursor.then(Modifier.clickable { onToggle(false) }),
        )
    }
}

@Composable
private fun InspectorRow(label: String, value: String, labelStyle: TextStyle, valueStyle: TextStyle) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = labelStyle, modifier = Modifier.width(64.dp))
        Text(value, style = valueStyle, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}
