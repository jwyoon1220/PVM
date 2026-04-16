package io.github.jwyoon1220.pvm.app

import io.github.jwyoon1220.pvm.addons.BiosKeyboardAddon
import io.github.jwyoon1220.pvm.addons.BiosVideoAddon
import io.github.jwyoon1220.pvm.addons.DosHleAddon
import io.github.jwyoon1220.pvm.core.VM
import io.github.jwyoon1220.pvm.core.memory.MemoryBus

fun main() {
    VM(memory = MemoryBus(1024 * 1024)).use { vm ->
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
    val msg = "Hello, BIOS!\r\n"
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
