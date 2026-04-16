package io.github.jwyoon1220.pvm.addons

import io.github.jwyoon1220.pvm.api.AddonContext
import io.github.jwyoon1220.pvm.api.VmAddon
import io.github.jwyoon1220.pvm.api.VmContext

private const val VRAM_BASE    = 0xB8000
private const val SCREEN_COLS  = 80
private const val SCREEN_ROWS  = 25
private const val SCREEN_CELLS = SCREEN_COLS * SCREEN_ROWS
private const val ATTR_DEFAULT = 0x07  // light-grey on black

/**
 * LLE/HLE hybrid addon for BIOS INT 10h (Video Services).
 *
 * Every character write updates both [VmContext.output] (backward-compatible
 * HLE terminal path) and the CGA/VGA text-mode VRAM region starting at
 * 0xB8000, so any registered [io.github.jwyoon1220.pvm.api.IMemoryService]
 * watcher (e.g. [io.github.jwyoon1220.pvm.drivers.display.VgaTextFrame])
 * is triggered automatically.
 *
 * Supported sub-functions:
 * - AH=00h Set video mode
 * - AH=01h Set cursor type
 * - AH=02h Set cursor position
 * - AH=03h Get cursor position
 * - AH=05h Set active display page
 * - AH=06h Scroll up window
 * - AH=07h Scroll down window
 * - AH=08h Read character and attribute at cursor
 * - AH=09h Write character and attribute at cursor
 * - AH=0Ah Write character only at cursor
 * - AH=0Bh Set border / palette (no-op in text mode)
 * - AH=0Eh Write character in TTY mode
 * - AH=0Fh Get current video mode
 * - AH=11h Load character set (no-op stub)
 * - AH=13h Write string
 */
class BiosVideoAddon : VmAddon {
    override val id = "bios-video"

    private var currentMode     = 0x03
    private var activePage      = 0
    private var cursorX         = 0
    private var cursorY         = 0
    private var cursorScanStart = 0x0E
    private var cursorScanEnd   = 0x0F

    override fun onEnable(context: AddonContext) {
        context.interrupts.register(0x10) { _, ctx -> handle(ctx) }
    }

    private fun handle(ctx: VmContext) {
        when (ctx.ah) {
            0x00 -> setVideoMode(ctx)
            0x01 -> setCursorType(ctx)
            0x02 -> setCursorPos(ctx)
            0x03 -> getCursorPos(ctx)
            0x05 -> { activePage = ctx.al and 0x07 }
            0x06 -> scrollUp(ctx)
            0x07 -> scrollDown(ctx)
            0x08 -> readCharAttr(ctx)
            0x09 -> writeCharAttr(ctx)
            0x0A -> writeCharOnly(ctx)
            0x0B -> { /* set border/palette — no-op in text mode */ }
            0x0E -> writeTty(ctx, ctx.al)
            0x0F -> getVideoMode(ctx)
            0x11 -> { /* load character set — no-op */ }
            0x13 -> writeString(ctx)
            else -> throw UnsupportedOperationException(
                String.format("INT 10h AH=%02Xh not implemented", ctx.ah)
            )
        }
    }

    // ── AH=00h ── Set video mode ─────────────────────────────────────────────

    private fun setVideoMode(ctx: VmContext) {
        currentMode = ctx.al
        cursorX = 0
        cursorY = 0
        // Clear VRAM to blank/default for the new mode
        for (cell in 0 until SCREEN_CELLS) {
            val addr = pageBase() + cell * 2
            ctx.write8(addr,     0x20)
            ctx.write8(addr + 1, ATTR_DEFAULT)
        }
    }

    // ── AH=01h ── Set cursor type ────────────────────────────────────────────

    private fun setCursorType(ctx: VmContext) {
        cursorScanStart = ctx.ch and 0x1F
        cursorScanEnd   = ctx.cl and 0x1F
    }

    // ── AH=02h ── Set cursor position ────────────────────────────────────────

    private fun setCursorPos(ctx: VmContext) {
        cursorY = ctx.dh.coerceIn(0, SCREEN_ROWS - 1)
        cursorX = ctx.dl.coerceIn(0, SCREEN_COLS - 1)
    }

    // ── AH=03h ── Get cursor position ────────────────────────────────────────

    private fun getCursorPos(ctx: VmContext) {
        ctx.dh = cursorY
        ctx.dl = cursorX
        ctx.ch = cursorScanStart
        ctx.cl = cursorScanEnd
    }

    // ── AH=06h ── Scroll up window ───────────────────────────────────────────

    private fun scrollUp(ctx: VmContext) {
        scrollWindow(
            ctx,
            lines  = ctx.al,
            fillAttr = ctx.bh,
            top    = ctx.ch.coerceIn(0, SCREEN_ROWS - 1),
            left   = ctx.cl.coerceIn(0, SCREEN_COLS - 1),
            bottom = ctx.dh.coerceIn(0, SCREEN_ROWS - 1),
            right  = ctx.dl.coerceIn(0, SCREEN_COLS - 1),
            up     = true
        )
    }

    // ── AH=07h ── Scroll down window ─────────────────────────────────────────

    private fun scrollDown(ctx: VmContext) {
        scrollWindow(
            ctx,
            lines    = ctx.al,
            fillAttr = ctx.bh,
            top      = ctx.ch.coerceIn(0, SCREEN_ROWS - 1),
            left     = ctx.cl.coerceIn(0, SCREEN_COLS - 1),
            bottom   = ctx.dh.coerceIn(0, SCREEN_ROWS - 1),
            right    = ctx.dl.coerceIn(0, SCREEN_COLS - 1),
            up       = false
        )
    }

    private fun scrollWindow(
        ctx: VmContext,
        lines: Int, fillAttr: Int,
        top: Int, left: Int, bottom: Int, right: Int,
        up: Boolean
    ) {
        if (top > bottom || left > right) return
        if (lines == 0 || lines > bottom - top) {
            // Clear the entire window
            for (row in top..bottom) fillRow(ctx, row, left, right, 0x20, fillAttr)
            return
        }
        if (up) {
            for (row in top..bottom) {
                val src = row + lines
                if (src <= bottom) copyRow(ctx, src, row, left, right)
                else               fillRow(ctx, row, left, right, 0x20, fillAttr)
            }
        } else {
            for (row in bottom downTo top) {
                val src = row - lines
                if (src >= top) copyRow(ctx, src, row, left, right)
                else            fillRow(ctx, row, left, right, 0x20, fillAttr)
            }
        }
    }

    private fun fillRow(ctx: VmContext, row: Int, left: Int, right: Int, ch: Int, attr: Int) {
        val base = pageBase()
        for (col in left..right) {
            val addr = base + (row * SCREEN_COLS + col) * 2
            ctx.write8(addr,     ch)
            ctx.write8(addr + 1, attr)
        }
    }

    private fun copyRow(ctx: VmContext, srcRow: Int, dstRow: Int, left: Int, right: Int) {
        val base = pageBase()
        for (col in left..right) {
            val s = base + (srcRow * SCREEN_COLS + col) * 2
            val d = base + (dstRow * SCREEN_COLS + col) * 2
            ctx.write8(d,     ctx.read8(s))
            ctx.write8(d + 1, ctx.read8(s + 1))
        }
    }

    // ── AH=08h ── Read character and attribute at cursor ─────────────────────

    private fun readCharAttr(ctx: VmContext) {
        val addr = pageBase() + (cursorY * SCREEN_COLS + cursorX) * 2
        ctx.al = ctx.read8(addr)
        ctx.ah = ctx.read8(addr + 1)
    }

    // ── AH=09h ── Write character and attribute at cursor ────────────────────

    private fun writeCharAttr(ctx: VmContext) {
        val ch   = ctx.al
        val attr = ctx.bl
        val count = ctx.cx
        var x = cursorX; var y = cursorY
        repeat(count.coerceAtLeast(1)) {
            val addr = pageBase() + (y * SCREEN_COLS + x) * 2
            ctx.write8(addr,     ch)
            ctx.write8(addr + 1, attr)
            if (++x >= SCREEN_COLS) { x = 0; if (++y >= SCREEN_ROWS) y = 0 }
        }
    }

    // ── AH=0Ah ── Write character only at cursor ─────────────────────────────

    private fun writeCharOnly(ctx: VmContext) {
        val ch    = ctx.al
        val count = ctx.cx
        var x = cursorX; var y = cursorY
        repeat(count.coerceAtLeast(1)) {
            val addr = pageBase() + (y * SCREEN_COLS + x) * 2
            ctx.write8(addr, ch)   // attribute byte unchanged
            if (++x >= SCREEN_COLS) { x = 0; if (++y >= SCREEN_ROWS) y = 0 }
        }
    }

    // ── AH=0Eh ── Write character in TTY mode ────────────────────────────────

    private fun writeTty(ctx: VmContext, charCode: Int) {
        when (val ch = charCode.toChar()) {
            '\r' -> {
                ctx.output.write('\r')
                cursorX = 0
            }
            '\n' -> {
                ctx.output.write('\n')
                advanceLine()
            }
            '\u0008' -> {
                ctx.output.write('\b')
                if (cursorX > 0) cursorX--
            }
            else -> {
                ctx.output.write(ch)
                writeVram(ctx, charCode, ATTR_DEFAULT)
                advanceCursor()
            }
        }
    }

    // ── AH=0Fh ── Get current video mode ─────────────────────────────────────

    private fun getVideoMode(ctx: VmContext) {
        ctx.al = currentMode
        ctx.ah = SCREEN_COLS
        ctx.bh = activePage
    }

    // ── AH=13h ── Write string ───────────────────────────────────────────────
    //
    // AL = write mode:
    //   bit 0 – 0: chars only (attr from BL)  1: chars + attr pairs in string
    //   bit 1 – 0: don't update cursor         1: update cursor after write
    // BH = page, BL = attribute (modes 0/2), CX = char count,
    // DH = row, DL = col, ES:BP = string address (flat BP used, ES=0 assumed).

    private fun writeString(ctx: VmContext) {
        val mode  = ctx.al
        val attr  = ctx.bl
        val count = ctx.cx
        var x     = ctx.dl.coerceIn(0, SCREEN_COLS - 1)
        var y     = ctx.dh.coerceIn(0, SCREEN_ROWS - 1)
        // ES:BP → flat address (assumes ES=0 in flat memory model)
        var addr  = ctx.ebp and 0xFFFF
        var i = 0
        while (i < count) {
            val ch      = ctx.read8(addr++)
            val curAttr = if (mode and 1 != 0) ctx.read8(addr++) else attr
            i++
            when (ch.toChar()) {
                '\r' -> { x = 0 }
                '\n' -> { if (++y >= SCREEN_ROWS) y = 0 }
                '\u0008' -> { if (x > 0) x-- }
                else -> {
                    val cellAddr = pageBase() + (y * SCREEN_COLS + x) * 2
                    ctx.write8(cellAddr,     ch)
                    ctx.write8(cellAddr + 1, curAttr)
                    if (++x >= SCREEN_COLS) { x = 0; if (++y >= SCREEN_ROWS) y = 0 }
                }
            }
        }
        if (mode and 2 != 0) { cursorX = x; cursorY = y }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Physical base address of the active display page. */
    private fun pageBase(): Int = VRAM_BASE + activePage * SCREEN_CELLS * 2

    private fun writeVram(ctx: VmContext, charCode: Int, attr: Int) {
        val offset = (cursorY * SCREEN_COLS + cursorX) * 2
        ctx.write8(pageBase() + offset,     charCode)
        ctx.write8(pageBase() + offset + 1, attr)
    }

    private fun advanceCursor() {
        if (++cursorX >= SCREEN_COLS) { cursorX = 0; advanceLine() }
    }

    private fun advanceLine() {
        // Hardware behaviour: no auto-scroll; cursor wraps to row 0.
        // Guest software uses AH=06h to scroll the window.
        if (++cursorY >= SCREEN_ROWS) cursorY = 0
    }
}
