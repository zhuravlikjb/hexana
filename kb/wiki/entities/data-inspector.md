# Data Inspector (hex-view value inspector)

The side panel next to the hex view in IntelliJ (`idea-plugin/.../ui/DataInspectorPanel.kt`) and
VS Code / webapp (`web-ui/.../vscode/DataInspectorPanel.kt`). Tracks GitHub issue #31
("Interactive value inspector panel/popover", parent #30).

## Single decoding model

All decoding lives in `plugins-shared/.../shared/DataInspectorFormat.kt`; the hosts only pick the
input bytes and render `dataInspectorGroups(bytes, offset, littleEndian, selectionLength)` verbatim
(group title, then label/value rows). Before 2026-11-19 each host had its own copy of the integer
readers and the UTF-8 prefix decoder.

- **Input**: with a multi-byte selection, the selected bytes from its start (capped at
  `DATA_INSPECTOR_WINDOW` = 64) and `selectionLength` = the full length; otherwise up to 64 bytes
  from the cursor and `selectionLength = null`. IntelliJ shows `offset + displayBaseOffset`
  (partial/region views); web-ui has no base offset.
- **Groups**: Position (Offset, Length, Hex — the selection, or the 8 bytes the widest types read,
  max 16 bytes then `…`), Integers (Int8…UInt64), Floating point (Float32/64), LEB128 (ULEB128 /
  SLEB128 with byte count; `incomplete` when no terminator within the bytes or 10 bytes; `overflow`
  when the 10th byte carries more than bit 63), Text (Binary/Octal/ASCII of the first byte, UTF-8
  prefix up to 32 units).
- **Selections** restrict fixed-width types to those that fit (a 3-byte selection shows 8/16-bit only).
- Byte order is a per-panel Little/Big toggle, not shown side by side.
- Float formatting is the platform `toString` (JVM `1.0`, JS `1`); tests compare parsed values.
- Web-ui shows "Loading…" until at least `min(window, 10)` bytes of the paged source are loaded.

Tests: `plugins-shared/src/commonTest/.../DataInspectorFormatTest.kt` (JVM, JS, WasmJS).
Related: [[entities/partial-views]] (64-bit offsets, `LargeBytes`).

## Not done (issue #31 "popover")

The issue title mentions a popover; only the docked panel exists. No inline hover popover.
