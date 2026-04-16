package io.github.jwyoon1220.pvm.drivers.display

import io.github.jwyoon1220.pvm.api.IMemoryService
import io.github.jwyoon1220.pvm.api.OutputDevice
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
 * Typical setup:
 * ```kotlin
 * val frame = VgaTextFrame(vm.memory)
 * frame.register(vm.memoryService)   // subscribe to VRAM writes
 * frame.start()
 * ```
 *
 * A 60 fps Swing [Timer] re-renders only dirty cells into a [BufferedImage]
 * back-buffer, then repaints the window.  The dirty set is populated by the
 * [IMemoryService] watcher installed via [register].
 */
class VgaTextFrame(private val memory: MemoryBus) : JFrame("Parin-v86"), OutputDevice {

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
