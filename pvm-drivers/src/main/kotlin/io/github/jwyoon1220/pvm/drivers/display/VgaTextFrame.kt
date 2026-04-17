package io.github.jwyoon1220.pvm.drivers.display

import io.github.jwyoon1220.pvm.api.IMemoryService
import io.github.jwyoon1220.pvm.api.OutputDevice
import io.github.jwyoon1220.pvm.api.VmOutput
import io.github.jwyoon1220.pvm.core.memory.MemoryBus
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.image.BufferedImage
import java.util.BitSet
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * VGA text-mode display (80×25) backed by VRAM at physical address 0xB8000.
 *
 * This class serves as **both** an LLE display driver (reads VRAM via
 * [MemoryBus]) and an HLE [VmOutput] sink.  When [write] is called by guest
 * software that bypasses BIOS interrupts, characters are written directly into
 * the VRAM region of [MemoryBus], triggering the dirty-bit watcher so the next
 * 60 fps render tick picks them up automatically.
 *
 * Typical setup (replaces both TerminalOutput and a separate display object):
 * ```kotlin
 * val display = VgaTextFrame(vm.memory)
 * display.register(vm.memoryService)   // subscribe to VRAM writes for rendering
 * // use as VmOutput in the VM builder:
 * VMBuilder().output(display).build()
 * display.start()                      // show window and start 60 fps timer
 * ```
 *
 * The window is **not visible until [start] is called**, so creating a
 * [VgaTextFrame] for unit-testing purposes (without a display) is safe as long
 * as [start] is not invoked.
 */
class VgaTextFrame(private val memory: MemoryBus) : JFrame("Parin-v86"), OutputDevice, VmOutput {

    companion object {
        const val VRAM_BASE  = 0xB8000
        const val COLS       = 80
        const val ROWS       = 25
        const val CELLS      = COLS * ROWS
        const val VRAM_BYTES = CELLS * 2
        const val CHAR_W     = 9
        const val CHAR_H     = 16
    }

    private val backBuffer  = BufferedImage(COLS * CHAR_W, ROWS * CHAR_H, BufferedImage.TYPE_INT_RGB)
    private val dirtyBits   = BitSet(CELLS)
    private val font        = Font(Font.MONOSPACED, Font.PLAIN, CHAR_H - 2)
    private var renderTimer: Timer? = null

    // ── HLE VmOutput cursor state ─────────────────────────────────────────────
    // Delegates to VramCursor, which writes char+attr bytes directly into the
    // MemoryBus VRAM region.  The dirty-bit watcher installed via register()
    // marks the affected cells for the next render tick — no separate code path.
    private val hleCursor = VramCursor(memory)

    /** Text attribute byte used for HLE writes: light-grey (7) on black (0). */
    var hleAttr: Int
        get() = hleCursor.attr
        set(v) { hleCursor.attr = v }

    /**
     * Subscribes to VRAM writes in [0xB8000, 0xB8000+VRAM_BYTES).
     * Must be called before [start].
     */
    fun register(memoryService: IMemoryService) {
        memoryService.addWatcher(VRAM_BASE until VRAM_BASE + VRAM_BYTES) { event ->
            val startCell = (event.address - VRAM_BASE) / 2
            val endCell   = (event.address - VRAM_BASE + event.byteCount - 1) / 2
            for (cell in startCell..endCell) {
                if (cell in 0 until CELLS) dirtyBits.set(cell)
            }
        }
    }

    /**
     * HLE [VmOutput] implementation.
     *
     * Delegates to [VramCursor], which writes the character directly into
     * [MemoryBus] at the current cursor position inside the VRAM region.
     * The [IMemoryService] watcher installed via [register] marks the affected
     * cell dirty so it is included in the next render tick.
     *
     * Control characters: `\r` carriage-return, `\n` line-feed (no auto-scroll,
     * wraps to row 0), `\b` backspace (erase previous cell).
     */
    override fun write(ch: Char) = hleCursor.write(ch)

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        isResizable = false
        contentPane.preferredSize = Dimension(COLS * CHAR_W, ROWS * CHAR_H)
        pack()
        dirtyBits.set(0, CELLS)  // force full repaint on first frame
    }

    override fun start() {
        SwingUtilities.invokeLater {
            isVisible = true
            renderTimer = Timer(16) { renderFrame() }
            renderTimer!!.start()
        }
    }

    override fun stop() {
        renderTimer?.stop()
        SwingUtilities.invokeLater { dispose() }
    }

    private fun renderFrame() {
        if (dirtyBits.isEmpty) return

        val g2 = backBuffer.createGraphics()
        g2.font = font

        var cell = dirtyBits.nextSetBit(0)
        while (cell >= 0) {
            val col = cell % COLS
            val row = cell / COLS
            val addr = VRAM_BASE + cell * 2
            val charCode = memory.read8(addr)
            val attr     = memory.read8(addr + 1)
            val fgIdx    = attr and 0x0F
            val bgIdx    = (attr shr 4) and 0x07

            val x = col * CHAR_W
            val y = row * CHAR_H

            g2.color = java.awt.Color(VgaColors.PALETTE[bgIdx])
            g2.fillRect(x, y, CHAR_W, CHAR_H)

            if (charCode >= 0x20) {
                g2.color = java.awt.Color(VgaColors.PALETTE[fgIdx])
                g2.drawString(charCode.toChar().toString(), x, y + CHAR_H - 3)
            }

            cell = dirtyBits.nextSetBit(cell + 1)
        }
        g2.dispose()
        dirtyBits.clear()

        repaint()
    }

    override fun paint(g: Graphics) {
        g.drawImage(backBuffer, insets.left, insets.top, null)
    }
}
