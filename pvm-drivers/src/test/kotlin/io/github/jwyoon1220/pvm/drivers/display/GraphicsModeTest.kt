package io.github.jwyoon1220.pvm.drivers.display

import io.github.jwyoon1220.pvm.addons.BiosVideoAddon
import io.github.jwyoon1220.pvm.core.VM
import io.github.jwyoon1220.pvm.core.memory.MemoryBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the VGA graphics / text-mode display pipeline.
 *
 * All tests are headless-safe: [VgaTextFrame.start] is never called, so no
 * Swing window is opened and no [java.awt.HeadlessException] is thrown.
 *
 * Two integration paths are covered:
 *
 * 1. **LLE (Low-Level Emulation)** — guest uses INT 10h (via [BiosVideoAddon]);
 *    the addon writes char+attribute bytes into [MemoryBus] VRAM; tests check
 *    the raw bytes at `0xB8000 + offset`.
 *
 * 2. **HLE (High-Level Emulation)** — host calls [VgaTextFrame.write] directly
 *    (as a [io.github.jwyoon1220.pvm.api.VmOutput]); the implementation writes
 *    bytes into [MemoryBus] VRAM; tests check the same addresses.
 */
class GraphicsModeTest {

    companion object {
        private const val VRAM = VgaTextFrame.VRAM_BASE
        private const val COLS = VgaTextFrame.COLS
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Cell address for (col, row) pair. */
    private fun cell(col: Int, row: Int) = VRAM + (row * COLS + col) * 2

    /** Run a small program using BiosVideoAddon, return the VM after HLT. */
    private fun runLle(vararg bytes: Byte): VM {
        val vm = VM()
        vm.registerAddon(BiosVideoAddon())
        bytes.forEachIndexed { i, b -> vm.memory.write8(i, b.toInt() and 0xFF) }
        vm.cpu.eip = 0
        vm.run()
        return vm
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LLE path: INT 10h → BiosVideoAddon → MemoryBus VRAM
    // ═══════════════════════════════════════════════════════════════════════

    // ── AH=09h: write char + colour attribute ────────────────────────────────

    @Test fun `LLE AH=09h writes char and attribute at cursor 0,0`() {
        // AH=09h AL='A' BL=0x4F (bright-white on red) ECX=1  INT 10h  HLT
        val vm = runLle(
            0xB4.toByte(), 0x09,
            0xB0.toByte(), 'A'.code.toByte(),
            0xB3.toByte(), 0x4F,
            0xB9.toByte(), 0x01, 0x00, 0x00, 0x00,
            0xCD.toByte(), 0x10,
            0xF4.toByte()
        )
        assertEquals('A'.code, vm.memory.read8(cell(0, 0)), "char at (0,0)")
        assertEquals(0x4F,     vm.memory.read8(cell(0, 0) + 1), "attr at (0,0)")
        vm.close()
    }

    @Test fun `LLE AH=09h writes char N times without moving cursor`() {
        // Write 'X' 5 times at cursor (0,0)
        val vm = runLle(
            0xB4.toByte(), 0x09,
            0xB0.toByte(), 'X'.code.toByte(),
            0xB3.toByte(), 0x07,
            0xB9.toByte(), 0x05, 0x00, 0x00, 0x00,
            0xCD.toByte(), 0x10,
            0xF4.toByte()
        )
        for (col in 0..4) assertEquals('X'.code, vm.memory.read8(cell(col, 0)), "char at (0,$col)")
        vm.close()
    }

    // ── AH=09h with different colours ────────────────────────────────────────

    @Test fun `LLE AH=09h yellow-on-green attribute 0x2E stored correctly`() {
        val vm = runLle(
            0xB4.toByte(), 0x09,
            0xB0.toByte(), 'K'.code.toByte(),
            0xB3.toByte(), 0x2E,
            0xB9.toByte(), 0x01, 0x00, 0x00, 0x00,
            0xCD.toByte(), 0x10,
            0xF4.toByte()
        )
        assertEquals('K'.code, vm.memory.read8(cell(0, 0)), "char")
        assertEquals(0x2E,     vm.memory.read8(cell(0, 0) + 1), "attr 0x2E")
        vm.close()
    }

    @Test fun `LLE AH=09h cyan-on-blue attribute 0x1B stored correctly`() {
        val vm = runLle(
            0xB4.toByte(), 0x09,
            0xB0.toByte(), 'J'.code.toByte(),
            0xB3.toByte(), 0x1B,
            0xB9.toByte(), 0x01, 0x00, 0x00, 0x00,
            0xCD.toByte(), 0x10,
            0xF4.toByte()
        )
        assertEquals('J'.code, vm.memory.read8(cell(0, 0)), "char")
        assertEquals(0x1B,     vm.memory.read8(cell(0, 0) + 1), "attr 0x1B")
        vm.close()
    }

    // ── AH=02h + AH=09h: set cursor then write ───────────────────────────────

    @Test fun `LLE set cursor to row 3 col 10, then write coloured char`() {
        val vm = runLle(
            // AH=02h: set cursor (row=3, col=10)
            0xB4.toByte(), 0x02,
            0xB6.toByte(), 0x03,
            0xB2.toByte(), 0x0A,
            0xCD.toByte(), 0x10,
            // AH=09h: write 'Z' with attr 0x70
            0xB4.toByte(), 0x09,
            0xB0.toByte(), 'Z'.code.toByte(),
            0xB3.toByte(), 0x70,
            0xB9.toByte(), 0x01, 0x00, 0x00, 0x00,
            0xCD.toByte(), 0x10,
            0xF4.toByte()
        )
        assertEquals('Z'.code, vm.memory.read8(cell(10, 3)), "char at (10,3)")
        assertEquals(0x70,     vm.memory.read8(cell(10, 3) + 1), "attr at (10,3)")
        vm.close()
    }

    // ── AH=06h: scroll clears region with chosen attribute ───────────────────

    @Test fun `LLE AH=06h AL=0 clears window and fills with given attribute`() {
        val vm = VM()
        vm.registerAddon(BiosVideoAddon())
        // Pre-fill cell (0,0) with 'A' + attr 0x0F
        vm.memory.write8(VRAM, 'A'.code)
        vm.memory.write8(VRAM + 1, 0x0F)

        val prog = byteArrayOf(
            0xB4.toByte(), 0x06,   // AH=06h
            0xB0.toByte(), 0x00,   // AL=0 (clear)
            0xB7.toByte(), 0x4F,   // BH=0x4F fill attr (bright-white on red)
            0xB5.toByte(), 0x00, 0xB1.toByte(), 0x00,   // CH=0, CL=0
            0xB6.toByte(), 0x18, 0xB2.toByte(), 0x4F,   // DH=24, DL=79
            0xCD.toByte(), 0x10,
            0xF4.toByte()
        )
        prog.forEachIndexed { i, b -> vm.memory.write8(i, b.toInt() and 0xFF) }
        vm.cpu.eip = 0
        vm.run()

        assertEquals(0x20, vm.memory.read8(VRAM),     "cleared to space")
        assertEquals(0x4F, vm.memory.read8(VRAM + 1), "fill attr 0x4F")
        vm.close()
    }

    // ── Full colour banner: set-mode → multiple rows with different colours ──

    @Test fun `LLE full colour banner writes correct chars and attrs to VRAM`() {
        val vm = VM()
        vm.registerAddon(BiosVideoAddon())

        val prog = mutableListOf<Byte>()
        // AH=00h: set video mode 03h
        prog.addAll(byteArrayOf(0xB4.toByte(), 0x00, 0xB0.toByte(), 0x03, 0xCD.toByte(), 0x10).asList())

        data class Row(val ch: Char, val attr: Int, val col: Int, val row: Int)
        val cells = listOf(
            Row('P', 0x4F, 0, 1),
            Row('W', 0x2E, 0, 2),
            Row('8', 0x1B, 0, 3)
        )
        for (c in cells) {
            // Set cursor
            prog.addAll(byteArrayOf(
                0xB4.toByte(), 0x02,
                0xB6.toByte(), c.row.toByte(),
                0xB2.toByte(), c.col.toByte(),
                0xCD.toByte(), 0x10
            ).asList())
            // Write char
            prog.addAll(byteArrayOf(
                0xB4.toByte(), 0x09,
                0xB0.toByte(), c.ch.code.toByte(),
                0xB3.toByte(), c.attr.toByte(),
                0xB9.toByte(), 0x01, 0x00, 0x00, 0x00,
                0xCD.toByte(), 0x10
            ).asList())
        }
        prog.add(0xF4.toByte())

        prog.forEachIndexed { i, b -> vm.memory.write8(i, b.toInt() and 0xFF) }
        vm.cpu.eip = 0
        vm.run()

        for (c in cells) {
            assertEquals(c.ch.code, vm.memory.read8(cell(c.col, c.row)),       "char row=${c.row}")
            assertEquals(c.attr,    vm.memory.read8(cell(c.col, c.row) + 1),   "attr row=${c.row}")
        }
        vm.close()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HLE path: VramCursor.write(ch) → MemoryBus VRAM
    // (VramCursor is the headless-safe delegate used by VgaTextFrame.write)
    // ═══════════════════════════════════════════════════════════════════════

    /** Create a standalone [VramCursor] backed by a fresh [MemoryBus]. */
    private fun makeCursor(): VramCursor {
        val memory = MemoryBus()
        return VramCursor(memory)
    }

    @Test fun `HLE write ASCII puts char at VRAM with default attribute`() {
        val cursor = makeCursor()
        cursor.write('H')
        assertEquals('H'.code, cursor.memory.read8(cell(0, 0)), "char at (0,0)")
        assertEquals(0x07,     cursor.memory.read8(cell(0, 0) + 1), "default attr 0x07")
        cursor.memory.close()
    }

    @Test fun `HLE write string places consecutive chars on same row`() {
        val cursor = makeCursor()
        "HELLO".forEach { cursor.write(it) }
        "HELLO".forEachIndexed { i, ch ->
            assertEquals(ch.code, cursor.memory.read8(cell(i, 0)), "char at ($i,0)")
        }
        cursor.memory.close()
    }

    @Test fun `HLE CRLF advances to next row column 0`() {
        val cursor = makeCursor()
        cursor.write('A')
        cursor.write('\r')
        cursor.write('\n')
        cursor.write('B')
        assertEquals('A'.code, cursor.memory.read8(cell(0, 0)), "'A' at row 0")
        assertEquals('B'.code, cursor.memory.read8(cell(0, 1)), "'B' at row 1")
        cursor.memory.close()
    }

    @Test fun `HLE backspace erases previous character`() {
        val cursor = makeCursor()
        cursor.write('X')
        cursor.write('\b')
        assertEquals(0x20, cursor.memory.read8(cell(0, 0)), "cell should be space after backspace")
        cursor.memory.close()
    }

    @Test fun `HLE custom attribute applied via attr property`() {
        val cursor = makeCursor()
        cursor.attr = 0x4F   // bright-white on red
        cursor.write('!')
        assertEquals('!'.code, cursor.memory.read8(cell(0, 0)), "char")
        assertEquals(0x4F,     cursor.memory.read8(cell(0, 0) + 1), "custom attr")
        cursor.memory.close()
    }

    @Test fun `HLE cursor wraps to row 0 at bottom without scrolling`() {
        val cursor = makeCursor()
        // Write ROWS newlines — cursor should wrap back to row 0
        repeat(VgaTextFrame.ROWS) { cursor.write('\n') }
        cursor.write('W')
        assertEquals('W'.code, cursor.memory.read8(cell(0, 0)), "cursor wrapped to row 0")
        cursor.memory.close()
    }

    // ── VgaColors palette sanity ──────────────────────────────────────────────

    @Test fun `VgaColors palette has 16 entries`() {
        assertEquals(16, VgaColors.PALETTE.size)
    }

    @Test fun `VgaColors palette black is 0x000000 and white is 0xFFFFFF`() {
        assertEquals(0x000000, VgaColors.PALETTE[0],  "index 0 = black")
        assertEquals(0xFFFFFF, VgaColors.PALETTE[15], "index 15 = bright white")
    }

    @Test fun `VgaColors palette standard CGA colours are present`() {
        assertTrue(VgaColors.PALETTE[1] != 0,   "index 1 (dark blue) non-zero")
        assertTrue(VgaColors.PALETTE[4] != 0,   "index 4 (dark red) non-zero")
        assertTrue(VgaColors.PALETTE[14] != 0,  "index 14 (yellow) non-zero")
    }
}
