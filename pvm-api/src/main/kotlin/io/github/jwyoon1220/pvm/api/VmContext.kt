package io.github.jwyoon1220.pvm.api

/**
 * Facade over the live VM state, passed to [InterruptHandler] and [PortIOHandler].
 * Implementations are provided by pvm-core.
 */
interface VmContext {
    // General-purpose registers
    var eax: Int; var ebx: Int; var ecx: Int; var edx: Int
    var esi: Int; var edi: Int; var esp: Int; var ebp: Int
    var eip: Int; var eflags: Int

    // Layered 8/16-bit access
    var ax: Int; var al: Int; var ah: Int
    var bx: Int; var bl: Int; var bh: Int
    var cx: Int; var cl: Int; var ch: Int
    var dx: Int; var dl: Int; var dh: Int

    var halted: Boolean

    /** Injected I/O — swap out for Swing, LWJGL, terminal, etc. */
    val input: VmInput
    val output: VmOutput

    fun read8(address: Int): Int
    fun read16(address: Int): Int
    fun read32(address: Int): Int
    fun write8(address: Int, value: Int)
    fun write16(address: Int, value: Int)
    fun write32(address: Int, value: Int)
}
