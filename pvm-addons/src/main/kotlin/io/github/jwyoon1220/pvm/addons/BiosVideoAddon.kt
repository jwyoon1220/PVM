package io.github.jwyoon1220.pvm.addons

import io.github.jwyoon1220.pvm.api.AddonContext
import io.github.jwyoon1220.pvm.api.VmAddon
import io.github.jwyoon1220.pvm.api.VmContext

private const val VRAM_BASE   = 0xB8000
private const val SCREEN_COLS = 80
private const val SCREEN_ROWS = 25
private const val ATTR_DEFAULT = 0x07  // light-grey on black

/**
 * BIOS INT 10h (Video Services) addon.
 *
 * After rendering each character to [VmContext.output] the addon also writes
 * the character + attribute bytes into the CGA/VGA text-mode VRAM region
 * (0xB8000–0xBFFFF) via [VmContext.write8], so any registered [IMemoryService]
 * watcher fires as expected.
 */
class BiosVideoAddon : VmAddon {
    override val id = "bios-video"

    private var currentMode = 0x03
    private var cursorX = 0
    private var cursorY = 0

    override fun onEnable(context: AddonContext) {
        context.interrupts.register(0x10) { _, ctx -> handle(ctx) }
    }

    private fun handle(ctx: VmContext) {
        when (ctx.ah) {
            0x00 -> {
                currentMode = ctx.al
                cursorX = 0
                cursorY = 0
            }
            0x02 -> {
                // Set cursor position: DH = row, DL = col
                cursorY = ctx.dh.coerceIn(0, SCREEN_ROWS - 1)
                cursorX = ctx.dl.coerceIn(0, SCREEN_COLS - 1)
            }
            0x03 -> {
                // Get cursor position
                ctx.dh = cursorY
                ctx.dl = cursorX
                ctx.ch = 0x0E  // cursor start line
                ctx.cl = 0x0F  // cursor end line
            }
            0x0E -> writeTty(ctx, ctx.al)
            0x0F -> { ctx.al = currentMode; ctx.ah = SCREEN_COLS; ctx.bh = 0 }
            else -> throw UnsupportedOperationException(
                String.format("INT 10h AH=%02Xh not implemented", ctx.ah)
            )
        }
    }

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
                writeVram(ctx, charCode)
                advanceCursor()
            }
        }
    }

    /** Write character + attribute byte to VRAM at current cursor position. */
    private fun writeVram(ctx: VmContext, charCode: Int) {
        val offset = (cursorY * SCREEN_COLS + cursorX) * 2
        ctx.write8(VRAM_BASE + offset,     charCode)
        ctx.write8(VRAM_BASE + offset + 1, ATTR_DEFAULT)
    }

    private fun advanceCursor() {
        cursorX++
        if (cursorX >= SCREEN_COLS) {
            cursorX = 0
            advanceLine()
        }
    }

    private fun advanceLine() {
        cursorY++
        if (cursorY >= SCREEN_ROWS) cursorY = 0  // wrap (no scroll needed for emulator)
    }
}
