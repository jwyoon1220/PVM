package io.github.jwyoon1220.pvm.app

import io.github.jwyoon1220.pvm.addons.BiosKeyboardAddon
import io.github.jwyoon1220.pvm.addons.BiosVideoAddon
import io.github.jwyoon1220.pvm.addons.DosHleAddon
import io.github.jwyoon1220.pvm.api.VmOutput
import io.github.jwyoon1220.pvm.core.VMBuilder
import io.github.jwyoon1220.pvm.core.io.SwingTerminalOutput

fun main() {
    VMBuilder()
        .memorySize(1024 * 1024)
        .output(SwingTerminalOutput())
        .build()
        .use { vm ->
            // ── Memory watcher: observe every VRAM write ────────────────────
            vm.vmContext.memoryService.addWatcher(0xB8000..0xBFFFF) { e ->
                println("[MEM] VRAM write @0x${e.address.toString(16)}: 0x${e.value.toString(16)} (${e.byteCount}B)")
            }

            // ── Port watcher: observe keyboard controller port ──────────────
            vm.vmContext.portService.addWatcher(0x60) { e ->
                val dir = if (e.isWrite) "OUT" else "IN"
                println("[PORT] $dir 0x60 = 0x${e.value.toString(16)}")
            }

            // ── Interrupt watcher: trace every INT 10h (video) call ─────────
            vm.vmContext.interruptService.addWatcher(0x10) { e ->
                println("[INT] INT 10h — AH=0x${e.context.ah.toString(16)} AL=0x${e.context.al.toString(16)}")
            }

            // ── Register addons (handlers run during vm.run()) ──────────────
            vm.registerAddon(BiosVideoAddon())
            vm.registerAddon(BiosKeyboardAddon())
            vm.registerAddon(DosHleAddon())

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
    val msg = "Aoi Kaje!\r\n"
    val bytes = mutableListOf<Byte>()

    for (ch in msg) {
        bytes += 0xB4.toByte()
        bytes += 0x0E.toByte()
        bytes += 0xB0.toByte()
        bytes += ch.code.toByte()
        bytes += 0xCD.toByte()
        bytes += 0x10.toByte()
    }
    bytes += 0xF4.toByte()

    return bytes.toByteArray()
}
