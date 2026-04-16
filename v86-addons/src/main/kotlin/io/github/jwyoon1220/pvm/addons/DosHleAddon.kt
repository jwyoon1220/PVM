package io.github.jwyoon1220.pvm.addons

import io.github.jwyoon1220.pvm.api.AddonContext
import io.github.jwyoon1220.pvm.api.VmAddon
import io.github.jwyoon1220.pvm.api.VmContext

/**
 * High-Level Emulation (HLE) addon for DOS INT 21h.
 */
class DosHleAddon : VmAddon {
    override val id = "dos-hle"

    override fun onEnable(context: AddonContext) {
        context.interrupts.register(0x21) { _, ctx -> handle(ctx) }
    }

    private fun handle(ctx: VmContext) {
        when (ctx.ah) {
            0x01 -> {
                val ch = System.`in`.read()
                ctx.al = ch and 0xFF
                print(ctx.al.toChar())
            }
            0x02 -> print(ctx.dl.toChar())
            0x09 -> {
                var addr = ctx.edx
                while (true) {
                    val ch = ctx.read8(addr++)
                    if (ch == '$'.code) break
                    print(ch.toChar())
                }
            }
            0x4C -> ctx.halted = true
            else -> throw UnsupportedOperationException(String.format("INT 21h AH=%02Xh not implemented", ctx.ah))
        }
    }
}
