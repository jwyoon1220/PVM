package io.github.jwyoon1220.pvm.drivers.display

import io.github.jwyoon1220.pvm.api.VmOutput
import io.github.jwyoon1220.pvm.core.memory.MemoryBus

/**
 * Tracks a text-mode cursor and writes characters directly into a [MemoryBus]
 * VRAM region starting at [vramBase].
 *
 * This class is intentionally decoupled from Swing so it can be unit-tested in
 * headless environments and reused by any display implementation that needs HLE
 * character writes (e.g. [VgaTextFrame]).
 *
 * **No auto-scroll.** When the cursor moves past the last row it wraps to row
 * 0 — scroll is the guest's responsibility (BIOS INT 10h AH=06h).
 */
class VramCursor(
    internal val memory: MemoryBus,
    val cols: Int    = VgaTextFrame.COLS,
    val rows: Int    = VgaTextFrame.ROWS,
    val vramBase: Int = VgaTextFrame.VRAM_BASE
) : VmOutput {

    /** Attribute byte applied to every character written by [write]. */
    var attr: Int = 0x07   // light-grey on black (default CGA attribute)

    private var col = 0
    private var row = 0

    override fun write(ch: Char) {
        when (ch) {
            '\r' -> col = 0
            '\n' -> nextRow()
            '\b' -> if (col > 0) { col--; writeCell(0x20, attr) }
            else -> {
                writeCell(ch.code, attr)
                if (++col >= cols) nextRow()
            }
        }
    }

    // ── package-internal helpers used by VgaTextFrame ──────────────────────

    private fun writeCell(charCode: Int, attribute: Int) {
        val addr = vramBase + (row * cols + col) * 2
        memory.write8(addr,     charCode)
        memory.write8(addr + 1, attribute)
    }

    private fun nextRow() {
        col = 0
        row = if (row + 1 < rows) row + 1 else 0
    }
}
