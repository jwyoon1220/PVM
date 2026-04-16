package io.github.jwyoon1220.pvm.core.io

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
 * JFrame을 상속받아 Swing의 Graphics2D로 문자를 직접 렌더링하는 커스텀 VmOutput 구현체입니다.
 */
class SwingTerminalOutput(
    private val columns: Int = 80,
    private val rows: Int = 24,
    title: String = "PVM Swing Output"
) : JFrame(title), VmOutput {

    // 화면에 그려질 글자들을 저장하는 2차원 버퍼
    private val screenBuffer = Array(rows) { CharArray(columns) { ' ' } }
    private var cursorX = 0
    private var cursorY = 0

    // 실제 렌더링을 담당할 내부 패널 (JFrame에 직접 그리는 것보다 깜빡임 방지 및 Inset 관리에 유리함)
    private val renderPanel = object : JPanel() {
        init {
            background = Color.BLACK
            foreground = Color.GREEN
            font = Font(Font.MONOSPACED, Font.PLAIN, 16)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2d = g as Graphics2D

            // 안티앨리어싱 적용하여 글자를 부드럽게 렌더링
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val fm = g2d.fontMetrics
            val charWidth = fm.charWidth('W') // 고정폭 폰트 기준 문자 너비
            val charHeight = fm.height
            val ascent = fm.ascent

            // 버퍼에 있는 문자들을 화면에 그림
            for (y in 0 until rows) {
                for (x in 0 until columns) {
                    val ch = screenBuffer[y][x]
                    if (ch != ' ') {
                        g2d.drawString(ch.toString(), x * charWidth, y * charHeight + ascent)
                    }
                }
            }

            // 커서 렌더링 (깜빡임 효과는 생략된 밑줄 형태의 커서)
            g2d.fillRect(cursorX * charWidth, cursorY * charHeight + fm.descent, charWidth, 2)
        }
    }

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        add(renderPanel)

        // 폰트 크기에 맞춰 창 크기 자동 계산
        val fm = getFontMetrics(renderPanel.font)
        val windowWidth = (columns * fm.charWidth('W')) + insets.left + insets.right + 20
        val windowHeight = (rows * fm.height) + insets.top + insets.bottom + 40

        setSize(windowWidth, windowHeight)
        setLocationRelativeTo(null) // 화면 중앙 배치
        isVisible = true
    }

    override fun write(ch: Char) {
        // VM 스레드에서 호출될 수 있으므로, UI 업데이트는 Event Dispatch Thread(EDT)에서 처리
        SwingUtilities.invokeLater {
            when (ch) {
                '\n' -> newLine()
                '\r' -> cursorX = 0
                '\b' -> { // 백스페이스 처리
                    if (cursorX > 0) {
                        cursorX--
                        screenBuffer[cursorY][cursorX] = ' '
                    }
                }
                else -> {
                    screenBuffer[cursorY][cursorX] = ch
                    cursorX++
                    // 줄 끝에 도달하면 자동 줄바꿈
                    if (cursorX >= columns) {
                        newLine()
                    }
                }
            }
            renderPanel.repaint() // 변경된 버퍼를 다시 그리도록 요청
        }
    }

    private fun newLine() {
        cursorX = 0
        cursorY++
        // 화면 맨 아래를 벗어나면 위로 스크롤 (줄바꿈 로직)
        if (cursorY >= rows) {
            for (i in 1 until rows) {
                System.arraycopy(screenBuffer[i], 0, screenBuffer[i - 1], 0, columns)
            }
            screenBuffer[rows - 1].fill(' ')
            cursorY = rows - 1
        }
    }
}