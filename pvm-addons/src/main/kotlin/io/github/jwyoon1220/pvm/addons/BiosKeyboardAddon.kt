package io.github.jwyoon1220.pvm.addons

import io.github.jwyoon1220.pvm.api.AddonContext
import io.github.jwyoon1220.pvm.api.VmAddon
import io.github.jwyoon1220.pvm.api.VmContext

/**
 * HLE addon for BIOS INT 16h (Keyboard Services).
 */
class BiosKeyboardAddon : VmAddon {
    override val id = "bios-keyboard"

    override fun onEnable(context: AddonContext) {
        context.interrupts.register(0x16) { _, ctx -> handle(ctx) }
    }

    private fun handle(ctx: VmContext) {
        when (ctx.ah) {
            0x00 -> {
                ctx.al = ctx.input.read() and 0xFF
                ctx.ah = 0
            }
            0x01 -> {
                if (ctx.input.hasInput()) {
                    ctx.eflags = ctx.eflags and (1 shl 6).inv()
                } else {
                    ctx.eflags = ctx.eflags or (1 shl 6)
                }
            }
            else -> throw UnsupportedOperationException(String.format("INT 16h AH=%02Xh not implemented", ctx.ah))
        }
    }
}
