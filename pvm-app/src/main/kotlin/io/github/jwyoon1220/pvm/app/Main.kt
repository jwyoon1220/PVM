package io.github.jwyoon1220.pvm.app

import io.github.jwyoon1220.pvm.addons.BiosDiskAddon
import io.github.jwyoon1220.pvm.addons.BiosKeyboardAddon
import io.github.jwyoon1220.pvm.addons.BiosSystemAddon
import io.github.jwyoon1220.pvm.addons.BiosVideoAddon
import io.github.jwyoon1220.pvm.addons.DosHleAddon
import io.github.jwyoon1220.pvm.core.VM
import io.github.jwyoon1220.pvm.core.memory.MemoryBus
import io.github.jwyoon1220.pvm.drivers.display.VgaTextFrame

/**
 * Parin-v86 launcher — graphics mode demo.
 *
 * [VgaTextFrame] now implements both [io.github.jwyoon1220.pvm.api.VmOutput]
 * (HLE character writes via [VgaTextFrame.write]) and
 * [io.github.jwyoon1220.pvm.api.OutputDevice] (LLE VRAM-backed rendering), so
 * it is the single display object for both paths.
 *
 * Because [VgaTextFrame] needs the [MemoryBus] at construction time (to write
 * HLE chars directly into VRAM), and [VM] also needs the same [MemoryBus],
 * we create [MemoryBus] first and wire it into both.
 *
 * The boot program below is a pure INT 10h demo:
 *  - AH=00h  — set 80x25 text mode
 *  - AH=09h  — write coloured characters N times at cursor
 *  - AH=02h/03h — set/get cursor position
 *  - AH=0Eh  — TTY-style write for the final status line
 */
fun main() {
    // Create MemoryBus first so we can wire it into both the display and the VM.
    val memory  = MemoryBus(1024 * 1024)

    // Unified display: handles both LLE VRAM rendering and HLE VmOutput writes.
    val display = VgaTextFrame(memory)

    // Pass display as VmOutput so DosHleAddon INT 21h writes also land here.
    val vm = VM(memory = memory, vmOutput = display)

    vm.use {
        // Subscribe display to VRAM writes for LLE rendering.
        display.register(vm.memoryService)

        // Interrupt watcher: trace INT 10h calls to stdout for debugging.
        vm.vmContext.interruptService.addWatcher(0x10) { e ->
            println("[INT] INT 10h AH=0x${e.context.ah.toString(16).padStart(2,'0')} AL=0x${e.context.al.toString(16).padStart(2,'0')}")
        }

        vm.registerAddon(BiosVideoAddon())
        vm.registerAddon(BiosKeyboardAddon())
        vm.registerAddon(DosHleAddon())
        vm.registerAddon(BiosDiskAddon())
        vm.registerAddon(BiosSystemAddon())

        val program = buildGraphicsModeProgram()
        vm.loadAt(0x7C00, program)

        vm.cpu.esp = 0x7BFC
        vm.cpu.eip = 0x7C00
        vm.cpu.cs  = 0x0000

        // Show the VGA window and start the 60 fps render timer.
        display.start()

        println("=== Parin-v86 starting (EIP=0x7C00, graphics mode demo) ===")
        vm.run()
        println()
        println("=== VM halted ===")
        vm.cpu.dump()

        // Stop the render timer and dispose the window BEFORE vm.close() releases
        // the MemoryBus arena — otherwise the Swing EDT can fire a render tick on
        // the already-closed MemoryBus and throw IllegalStateException.
        display.stop()
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Graphics mode demo program
//
// Attribute byte:  bits 7-4 = background colour index
//                  bits 3-0 = foreground colour index
//   0x4F = bright-white (0xF) on red  (4)
//   0x2E = yellow       (0xE) on green(2)
//   0x1B = cyan         (0xB) on blue (1)
//   0x70 = black        (0x0) on light-grey (7)
//
// INT 10h AH=09h does NOT advance the cursor.  Rather than using ADD/INC
// (arithmetic opcodes that may not yet be implemented), the Kotlin builder
// tracks the column index and emits an explicit AH=02h set-cursor before
// every character write.
// ────────────────────────────────────────────────────────────────────────────

private fun buildGraphicsModeProgram(): ByteArray {
    val bytes = mutableListOf<Byte>()

    fun emit(vararg b: Byte) = bytes.addAll(b.asList())

    // Set video mode 03h (80x25 colour text, clears screen)
    emit(0xB4.toByte(), 0x00, 0xB0.toByte(), 0x03, 0xCD.toByte(), 0x10)

    data class ColourRow(val text: String, val attr: Int, val row: Int)
    val rows = listOf(
        ColourRow("  Parin-v86 VGA Text Mode Demo  ", 0x4F, 1),
        ColourRow("  Written in Kotlin -- WORA JVM ", 0x2E, 2),
        ColourRow("  80 x 25  CGA/VGA Colour Text  ", 0x1B, 3),
        ColourRow("  Press any key to continue...  ", 0x70, 5)
    )

    for (row in rows) {
        for ((col, ch) in row.text.withIndex()) {
            // AH=02h: set cursor to exact (row, col) — avoids unsupported ADD opcode
            emit(0xB4.toByte(), 0x02, 0xB6.toByte(), row.row.toByte(), 0xB2.toByte(), col.toByte(), 0xCD.toByte(), 0x10)
            // AH=09h: write char+attr, CX=1
            emit(
                0xB4.toByte(), 0x09,
                0xB0.toByte(), ch.code.toByte(),
                0xB3.toByte(), row.attr.toByte(),
                0xB9.toByte(), 0x01, 0x00, 0x00, 0x00,
                0xCD.toByte(), 0x10
            )
        }
    }

    // TTY-write the final status line (AH=0Eh)
    for (ch in "\r\n\r\nBoot finished. VM halting.\r\n") {
        emit(0xB4.toByte(), 0x0E, 0xB0.toByte(), ch.code.toByte(), 0xCD.toByte(), 0x10)
    }

    bytes.add(0xF4.toByte())   // HLT
    return bytes.toByteArray()
}
