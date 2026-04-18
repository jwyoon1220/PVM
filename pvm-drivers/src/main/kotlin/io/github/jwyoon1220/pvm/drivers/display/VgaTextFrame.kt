package io.github.jwyoon1220.pvm.drivers.display

import io.github.jwyoon1220.pvm.api.IMemoryService
import io.github.jwyoon1220.pvm.api.OutputDevice
import io.github.jwyoon1220.pvm.api.VmOutput
import io.github.jwyoon1220.pvm.core.memory.MemoryBus
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * VGA text-mode display (80×25) backed by VRAM at physical address 0xB8000.
 *
 * This class serves as **both** an LLE display driver (reads VRAM via
 * [MemoryBus]) and an HLE [VmOutput] sink.  When [write] is called by guest
 * software that bypasses BIOS interrupts, characters are written directly into
 * the VRAM region of [MemoryBus], triggering the dirty watcher so the next
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
 *
 * ### Rendering architecture
 * A [JPanel] named [canvas] is used as the content pane.  Its
 * [JPanel.paintComponent] draws [backBuffer] at (0,0) in content-pane
 * coordinates.  This is necessary because overriding [JFrame.paint] draws to
 * the frame's graphics context *behind* the content pane, so the opaque content
 * pane would paint over it and nothing would appear on screen.
 *
 * ### Thread safety
 * The [dirty] flag is an [AtomicBoolean] so it can be set safely from the VM
 * execution thread (via the VRAM watcher) and read/cleared from the Swing EDT
 * (inside [renderFrame]).
 */
class VgaTextFrame(internal val memory: MemoryBus) : JFrame("Parin-v86"), OutputDevice, VmOutput {

    companion object {
        const val VRAM_BASE  = 0xB8000
        const val COLS       = 80
        const val ROWS       = 25
        const val CELLS      = COLS * ROWS
        const val VRAM_BYTES = CELLS * 2
        const val CHAR_W     = 9
        const val CHAR_H     = 16
    }

    /** Off-screen back-buffer; drawn onto [canvas] on every render tick. */
    internal val backBuffer = BufferedImage(COLS * CHAR_W, ROWS * CHAR_H, BufferedImage.TYPE_INT_RGB)

    /**
     * Set to `true` (atomically) whenever any VRAM cell changes.
     * The Swing EDT clears it at the start of each render tick via
     * [AtomicBoolean.getAndSet] so that writes arriving *during* a render are
     * not silently dropped — they will trigger the *next* tick.
     */
    internal val dirty = AtomicBoolean(true)   // start dirty → full render on first tick

    private val font        = Font(Font.MONOSPACED, Font.PLAIN, CHAR_H - 2)
    private var renderTimer: Timer? = null

    /**
     * Content-pane canvas.  Overrides [JPanel.paintComponent] to blit
     * [backBuffer] at (0, 0) in content-pane coordinates — the only correct
     * place to render in a Swing [JFrame].
     */
    private val canvas = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            // No super.paintComponent — we own the entire surface.
            g.drawImage(backBuffer, 0, 0, null)
        }
    }

    // ── HLE VmOutput cursor state ─────────────────────────────────────────────
    // Delegates to VramCursor, which writes char+attr bytes directly into the
    // MemoryBus VRAM region.  The dirty watcher installed via register() sets
    // the dirty flag so the next render tick picks up the change.
    private val hleCursor = VramCursor(memory)

    /** Text attribute byte used for HLE writes: light-grey (7) on black (0). */
    var hleAttr: Int
        get() = hleCursor.attr
        set(v) { hleCursor.attr = v }

    /**
     * Subscribes to VRAM writes in [0xB8000, 0xB8000+VRAM_BYTES).
     * Any write in the region sets [dirty] so the next render tick redraws
     * the entire screen from the current VRAM contents.
     * Must be called before [start].
     */
    fun register(memoryService: IMemoryService) {
        memoryService.addWatcher(VRAM_BASE until VRAM_BASE + VRAM_BYTES) { _ ->
            dirty.set(true)
        }
    }

    /**
     * HLE [VmOutput] implementation.
     *
     * Delegates to [VramCursor], which writes the character directly into
     * [MemoryBus] at the current cursor position inside the VRAM region.
     * The [IMemoryService] watcher installed via [register] sets [dirty]
     * so the cell is included in the next render tick.
     *
     * Control characters: `\r` carriage-return, `\n` line-feed (no auto-scroll,
     * wraps to row 0), `\b` backspace (erase previous cell).
     */
    override fun write(ch: Char) = hleCursor.write(ch)

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        isResizable = false
        canvas.preferredSize = Dimension(COLS * CHAR_W, ROWS * CHAR_H)
        contentPane = canvas
        pack()
    }

    override fun start() {
        SwingUtilities.invokeLater {
            isVisible = true
            renderTimer = Timer(16) { renderFrame() }
            renderTimer!!.start()
        }
    }

    /**
     * Stops the render timer and disposes the window.
     *
     * Uses [SwingUtilities.invokeAndWait] to guarantee that any pending
     * [start] task on the EDT is processed and the timer is stopped *before*
     * this method returns.  This prevents a race where the caller closes the
     * [MemoryBus] while the EDT is still executing a render tick that reads
     * from it.
     */
    override fun stop() {
        if (SwingUtilities.isEventDispatchThread()) {
            renderTimer?.stop()
            renderTimer = null
            dispose()
        } else {
            SwingUtilities.invokeAndWait {
                renderTimer?.stop()
                renderTimer = null
            }
            SwingUtilities.invokeLater { dispose() }
        }
    }

    /**
     * Renders all VRAM cells to [backBuffer] and repaints [canvas].
     * Only runs when [dirty] is `true`; clears the flag atomically so writes
     * arriving during the render are captured in the next tick.
     * Called on the Swing EDT by [renderTimer].
     */
    internal fun renderFrame() {
        if (!dirty.getAndSet(false)) return

        val g2 = backBuffer.createGraphics()
        g2.font = font

        for (cell in 0 until CELLS) {
            val col      = cell % COLS
            val row      = cell / COLS
            val addr     = VRAM_BASE + cell * 2
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
        }
        g2.dispose()

        canvas.repaint()
    }
}
