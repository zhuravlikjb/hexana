package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.shared.DATA_INSPECTOR_WINDOW
import org.jetbrains.hexana.shared.dataInspectorGroups

/**
 * Data Inspector side panel for the VS Code hex viewer. Decodes the selected bytes — or, without a
 * multi-byte selection, the bytes at the cursor — through the shared [dataInspectorGroups]: integers
 * and floats (little- or big-endian), unsigned and signed LEB128, hex, binary, octal, ASCII and UTF-8.
 */
@Composable
fun DataInspectorPanel(
    bytes: HexBytes,
    selection: HexSelectionState,
) {
    val totalBytes = bytes.size
    val selectionLength = if (selection.hasSelection) {
        (selection.selectionEnd.coerceAtMost(totalBytes - 1) - selection.selectionStart + 1).takeIf { it > 0 }
    } else {
        null
    }
    val offset = if (selectionLength != null) selection.selectionStart else selection.cursorIndex

    var littleEndian by remember { mutableStateOf(true) }

    Div({
        classes("hexana-data-inspector")
        style {
            width(280.px)
            property("min-width", "280px")
            property("flex-shrink", "0")
            property("overflow-y", "auto")
            property("overflow-x", "hidden")
            property("border-left", "1px solid var(--hexana-border, #333)")
            property("padding", "8px")
            property("font-family", "monospace")
            property("font-size", "11px")
            property("user-select", "text")
        }
    }) {
        // Header
        Div({
            style {
                property("font-size", "12px")
                property("margin-bottom", "8px")
            }
        }) { Text("Data Inspector") }

        // Endian switch
        EndianSwitch(littleEndian) { littleEndian = it }

        Div({ style { property("margin-bottom", "8px") } }) {}

        if (offset < 0 || offset >= totalBytes) {
            Div({ style { property("opacity", "0.6") } }) {
                Text("No byte selected")
            }
            return@Div
        }

        val window = (selectionLength ?: (totalBytes - offset)).coerceAtMost(DATA_INSPECTOR_WINDOW.toLong()).toInt()
        // Only the loaded prefix: a page still being fetched ends the window early.
        val raw = (0 until window).asSequence().map { bytes.byteOrNull(offset + it) }.takeWhile { it != null }
            .map { it!! }.toList().toByteArray()
        if (raw.size < minOf(window, 10)) {
            Div({ style { property("opacity", "0.6") } }) { Text("Loading\u2026") }
            return@Div
        }

        dataInspectorGroups(raw, offset, littleEndian, selectionLength).forEachIndexed { index, group ->
            Div({
                style {
                    property("margin-top", if (index > 0) "6px" else "0")
                    property("font-size", "10px")
                    property("font-weight", "bold")
                    property("opacity", "0.6")
                }
            }) { Text(group.title) }
            for (row in group.rows) InspectorRow(row.label, row.value)
        }
    }
}

@Composable
private fun EndianSwitch(littleEndian: Boolean, onToggle: (Boolean) -> Unit) {
    Div({
        style {
            display(DisplayStyle.Flex)
            property("align-items", "center")
            property("gap", "4px")
        }
    }) {
        Span({
            style {
                property("font-weight", if (littleEndian) "bold" else "normal")
                property("opacity", if (littleEndian) "1" else "0.5")
                property("cursor", "pointer")
            }
            onClick { onToggle(true) }
        }) { Text("Little Endian") }

        Span { Text("/") }

        Span({
            style {
                property("font-weight", if (!littleEndian) "bold" else "normal")
                property("opacity", if (!littleEndian) "1" else "0.5")
                property("cursor", "pointer")
            }
            onClick { onToggle(false) }
        }) { Text("Big Endian") }
    }
}

@Composable
private fun InspectorRow(label: String, value: String) {
    Div({
        style {
            display(DisplayStyle.Flex)
            property("justify-content", "space-between")
            property("padding", "1px 0")
        }
    }) {
        Span({
            style {
                property("opacity", "0.6")
                property("flex-shrink", "0")
                property("margin-right", "8px")
            }
        }) { Text(label) }
        Span({
            style {
                property("text-align", "right")
                property("overflow-wrap", "anywhere")
            }
        }) { Text(value) }
    }
}
