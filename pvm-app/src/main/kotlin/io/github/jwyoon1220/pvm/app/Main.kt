package io.github.jwyoon1220.pvm.app

import io.github.jwyoon1220.pvm.addons.BiosDiskAddon
import io.github.jwyoon1220.pvm.addons.BiosKeyboardAddon
import io.github.jwyoon1220.pvm.addons.BiosSystemAddon
import io.github.jwyoon1220.pvm.addons.BiosVideoAddon
import io.github.jwyoon1220.pvm.addons.DosHleAddon
import io.github.jwyoon1220.pvm.core.VMBuilder
import io.github.jwyoon1220.pvm.core.io.TerminalOutput
import io.github.jwyoon1220.pvm.drivers.display.VgaTextFrame

fun main() {
    VMBuilder()
        .memorySize(1024 * 1024)
        // TerminalOutput is the HLE fallback (used by DosHleAddon INT 21h writes).
        // VgaTextFrame below handles the LLE display driven by VRAM.
        .output(TerminalOutput())
        .build()
        .use { vm ->
            // ── LLE display: subscribe to VRAM writes, render at 60 fps ────
            val vgaFrame = VgaTextFrame(vm.memory)
            vgaFrame.register(vm.memoryService)
            vgaFrame.start()

            // ── Port watcher: observe keyboard controller port ──────────────
            vm.vmContext.portService.addWatcher(0x60) { e ->
                val dir = if (e.isWrite) "OUT" else "IN"
                println("[PORT] $dir 0x60 = 0x${e.value.toString(16)}")
            }

            // ── Interrupt watcher: trace INT 10h calls ──────────────────────
            vm.vmContext.interruptService.addWatcher(0x10) { e ->
                println("[INT] INT 10h — AH=0x${e.context.ah.toString(16)} AL=0x${e.context.al.toString(16)}")
            }

            // ── Register addons ──────────────────────────────────────────────
            vm.registerAddon(BiosVideoAddon())
            vm.registerAddon(BiosKeyboardAddon())
            vm.registerAddon(DosHleAddon())
            vm.registerAddon(BiosDiskAddon())
            vm.registerAddon(BiosSystemAddon())

            val program = buildBootProgram()
            vm.loadAt(0x7C00, program)

            vm.cpu.esp = 0x7BFC
            vm.cpu.eip = 0x7C00
            vm.cpu.cs  = 0x0000

            println("=== Parin-v86 starting (EIP=0x7C00) ===")
            vm.run()
            println()
            println("=== VM halted ===")
            vm.cpu.dump()
        }
}

private fun buildBootProgram(): ByteArray {
    val msg   = "Aoi Kaje!\r\n"
    val bytes = mutableListOf<Byte>()

    for (ch in msg) {
        bytes += 0xB4.toByte()        // MOV AH, 0Eh
        bytes += 0x0E.toByte()
        bytes += 0xB0.toByte()        // MOV AL, ch
        bytes += ch.code.toByte()
        bytes += 0xCD.toByte()        // INT 10h
        bytes += 0x10.toByte()
    }
    bytes += 0xF4.toByte()            // HLT

    return bytes.toByteArray()
}

