package io.github.jwyoon1220.pvm.addons

import io.github.jwyoon1220.pvm.api.AddonContext
import io.github.jwyoon1220.pvm.api.VmAddon
import io.github.jwyoon1220.pvm.api.VmContext

/**
 * HLE addon for BIOS INT 10h (Video Services).
 */
class BiosVideoAddon : VmAddon {
    override val id = "bios-video"
    private var currentMode = 0x03

    override fun onEnable(context: AddonContext) {
        context.interrupts.register(0x10) { _, ctx -> handle(ctx) }
    }

    private fun handle(ctx: VmContext) {
        when (ctx.ah) {
            0x00 -> currentMode = ctx.al
            0x0E -> {
                when (val ch = ctx.al.toChar()) {
                    '\r' -> ctx.output.write('\r')
                    '\n' -> ctx.output.write('\n')
                    '\u0008' -> ctx.output.write('\b')
                    else -> ctx.output.write(ch)
                }
            }
            0x0F -> { ctx.al = currentMode; ctx.ah = 80; ctx.bh = 0 }
            else -> throw UnsupportedOperationException(String.format("INT 10h AH=%02Xh not implemented", ctx.ah))
        }
    }
}
