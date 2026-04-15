package io.github.jwyoon1220.pvm.hardware.interrupt

import io.github.jwyoon1220.pvm.hardware.CPU
import io.github.jwyoon1220.pvm.hardware.Memory

// ─── INT 10h — BIOS Video Services ──────────────────────────────────────────

/**
 * BIOS INT 10h — Video Services.
 *
 * Supported functions (selected by AH):
 *  - AH=00h : Set video mode (acknowledged; mode stored in AL, no-op otherwise)
 *  - AH=0Eh : Teletype output — writes AL as a character to stdout
 *  - AH=0Fh : Get current video mode — returns AH=80 (columns), AL=03h (mode 3 text), BH=00h
 */
object BiosInt10Handler : InterruptHandler {
    private var currentMode: Int = 0x03  // Default: mode 3 (80×25 text)

    override fun handle(intNum: Int, cpu: CPU, memory: Memory) {
        when (cpu.ah) {
            0x00 -> {
                // Set video mode
                currentMode = cpu.al
            }
            0x0E -> {
                // Teletype output
                val ch = cpu.al.toChar()
                when (ch) {
                    '\r' -> print('\r')
                    '\n' -> println()
                    '\u0008' -> print('\u0008')  // BS
                    else -> print(ch)
                }
            }
            0x0F -> {
                // Get current video mode
                cpu.al = currentMode
                cpu.ah = 80  // 80 columns
                cpu.bh = 0   // display page 0
            }
            else -> throw UnsupportedOperationException(
                String.format("INT 10h: AH=%02Xh not implemented", cpu.ah)
            )
        }
    }
}

// ─── INT 16h — BIOS Keyboard Services ────────────────────────────────────────

/**
 * BIOS INT 16h — Keyboard Services.
 *
 * Supported functions (selected by AH):
 *  - AH=00h : Wait for keystroke — blocks until a key is pressed;
 *              returns AH=scan code (0 for plain ASCII), AL=ASCII character.
 *  - AH=01h : Check keystroke buffer — sets ZF=1 (buffer empty) because we
 *              do not maintain a look-ahead buffer in this implementation.
 */
object BiosInt16Handler : InterruptHandler {
    override fun handle(intNum: Int, cpu: CPU, memory: Memory) {
        when (cpu.ah) {
            0x00 -> {
                // Wait for and read one keystroke
                val ch = System.in.read()
                cpu.al = ch and 0xFF
                cpu.ah = 0  // simplified: no extended scan code
            }
            0x01 -> {
                // Check keystroke buffer — report buffer empty (ZF = 1)
                cpu.eflags = cpu.eflags or (1 shl 6)
            }
            else -> throw UnsupportedOperationException(
                String.format("INT 16h: AH=%02Xh not implemented", cpu.ah)
            )
        }
    }
}
