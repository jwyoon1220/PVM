package io.github.jwyoon1220.pvm.drivers.io

import io.github.jwyoon1220.pvm.api.OutputDevice
import io.github.jwyoon1220.pvm.api.VmOutput
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Swing-backed [VmOutput] + [OutputDevice] that renders characters onto a
 * fixed 80×25 character grid using [Graphics2D].
 *
 * **No auto-scroll.** This implementation emulates bare VGA text-mode
 * hardware: when the cursor reaches the last row it wraps back to row 0.
 * Scrolling is the responsibility of the guest software (e.g. via BIOS
 * INT 10h AH=06h "Scroll Up Window") — exactly as real hardware behaves.
 *
 * **The window is not shown until [start] is called.** Creating a
 * [SwingTerminalOutput] does not open any window, so it is safe to
 * instantiate it before calling [start].
 *
 * For LLE display backed by VRAM writes, prefer [io.github.jwyoon1220.pvm.drivers.display.VgaTextFrame] instead.
 */
class SwingTerminalOutput(
    private val columns: Int = 80,
    private val rows: Int = 25,
    title: String = "PVM Terminal"
) : JFrame(title), VmOutput, OutputDevice {

    // Character + colour buffers for the fixed-size screen
    private val charBuffer  = Array(rows) { CharArray(columns) { ' ' } }
    private val attrBuffer  = Array(rows) { IntArray(columns) { 0x07 } }

    private var cursorX = 0
    private var cursorY = 0

    private val renderPanel = object : JPanel() {
        init {
            background = Color.BLACK
            foreground = Color.GREEN
            font = Font(Font.MONOSPACED, Font.PLAIN, 16)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            )
            val fm        = g2.fontMetrics
            val charWidth = fm.charWidth('W')
            val charHeight = fm.height
            val ascent    = fm.ascent

            for (y in 0 until rows) {
                for (x in 0 until columns) {
                    val ch = charBuffer[y][x]
                    if (ch != ' ') {
                        g2.color = Color.GREEN
                        g2.drawString(ch.toString(), x * charWidth, y * charHeight + ascent)
                    }
                }
            }
            // Underline cursor (no blink)
            g2.color = Color.GREEN
            g2.fillRect(cursorX * charWidth, cursorY * charHeight + fm.descent, charWidth, 2)
        }
    }

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        add(renderPanel)
        val fm           = getFontMetrics(renderPanel.font)
        val windowWidth  = columns * fm.charWidth('W') + insets.left + insets.right + 20
        val windowHeight = rows * fm.height + insets.top + insets.bottom + 40
        setSize(windowWidth, windowHeight)
        setLocationRelativeTo(null)
        // Window is NOT shown here — call start() to make it visible.
    }

    /** Shows the terminal window. */
    override fun start() {
        SwingUtilities.invokeLater { isVisible = true }
    }

    /** Hides and disposes the terminal window. */
    override fun stop() {
        SwingUtilities.invokeLater { dispose() }
    }

    override fun write(ch: Char) {
        // VM thread → dispatch to EDT for all UI mutation.
        SwingUtilities.invokeLater {
            when (ch) {
                '\r'     -> cursorX = 0
                '\n'     -> advanceLine()
                '\b'     -> if (cursorX > 0) { cursorX--; charBuffer[cursorY][cursorX] = ' ' }
                else     -> {
                    charBuffer[cursorY][cursorX] = ch
                    cursorX++
                    if (cursorX >= columns) advanceLine()
                }
            }
            renderPanel.repaint()
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun advanceLine() {
        cursorX = 0
        cursorY++
        // Hardware wrap: no auto-scroll.  The guest (BIOS INT 10h AH=06h) is
        // responsible for scrolling; we simply wrap the cursor to the top row.
        if (cursorY >= rows) cursorY = 0
    }
}
