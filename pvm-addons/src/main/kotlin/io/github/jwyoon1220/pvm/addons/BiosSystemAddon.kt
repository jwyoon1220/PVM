package io.github.jwyoon1220.pvm.addons

import io.github.jwyoon1220.pvm.api.AddonContext
import io.github.jwyoon1220.pvm.api.VmAddon
import io.github.jwyoon1220.pvm.api.VmContext
import java.time.LocalDate
import java.time.LocalTime

/**
 * Addon covering BIOS system services not handled by other addons.
 *
 * Registers handlers for:
 * - **INT 15h** – Miscellaneous system services (extended memory, APM stubs)
 * - **INT 1Ah** – Time and date (timer ticks, RTC)
 * - **INT 19h** – Bootstrap loader (halts the VM in HLE mode)
 *
 * All times and dates are read from the host JVM clock and returned in
 * BCD format as required by the BIOS specification.
 */
class BiosSystemAddon : VmAddon {
    override val id = "bios-system"

    override fun onEnable(context: AddonContext) {
        context.interrupts.register(0x15) { _, ctx -> handleSystem(ctx) }
        context.interrupts.register(0x19) { _, ctx -> ctx.halted = true }  // Bootstrap loader → HLT
        context.interrupts.register(0x1A) { _, ctx -> handleTime(ctx) }
    }

    // ── INT 15h ── Miscellaneous system services ──────────────────────────────

    private fun handleSystem(ctx: VmContext) {
        when (ctx.ah) {
            0x53 -> { ctx.ah = 0x86; setCF(ctx) }  // APM — not supported
            0x83 -> { ctx.ah = 0x86; setCF(ctx) }  // Event wait — not supported
            0x84 -> { ctx.ah = 0x86; setCF(ctx) }  // Joystick — not supported
            0x86 -> { ctx.ah = 0x86; setCF(ctx) }  // Wait — not supported
            0x87 -> clearCF(ctx)                    // Move extended memory blocks (stub — succeeds)
            0x88 -> {                               // Get extended memory size (KB above 1 MB)
                ctx.ax = 0x3C00                     // 15 360 KB = 15 MB
                clearCF(ctx)
            }
            0x8A -> {                               // Get big extended memory size (bytes)
                ctx.edx = 15 * 1024 * 1024
                clearCF(ctx)
            }
            0xC0 -> { ctx.ah = 0x00; ctx.ebx = 0; clearCF(ctx) }  // Get ROM config (stub)
            0xE8 -> when (ctx.al) {
                0x01 -> {                           // E801: memory size for >64 MB config
                    ctx.ax = 0x3C00; ctx.bx = 0
                    ctx.cx = ctx.ax; ctx.dx = ctx.bx
                    clearCF(ctx)
                }
                0x20 -> setCF(ctx)                  // E820 memory map — not supported in HLE
                else -> { ctx.ah = 0x86; setCF(ctx) }
            }
            else -> { ctx.ah = 0x86; setCF(ctx) }  // Function not supported
        }
    }

    // ── INT 1Ah ── Time and date services ────────────────────────────────────

    private fun handleTime(ctx: VmContext) {
        when (ctx.ah) {
            0x00 -> getTimerTicks(ctx)
            0x01 -> clearCF(ctx)            // Set timer ticks — stub
            0x02 -> getRtcTime(ctx)
            0x03 -> clearCF(ctx)            // Set RTC time — stub
            0x04 -> getRtcDate(ctx)
            0x05 -> clearCF(ctx)            // Set RTC date — stub
            0x06 -> clearCF(ctx)            // Set alarm — stub
            0x07 -> clearCF(ctx)            // Cancel alarm — stub
            else -> { ctx.ah = 0x01; setCF(ctx) }
        }
    }

    /** AH=00h – Get timer ticks since midnight (~18.206 ticks/second). */
    private fun getTimerTicks(ctx: VmContext) {
        val t     = LocalTime.now()
        val ticks = t.toSecondOfDay() * 18206L / 1000L
        ctx.cx = ((ticks shr 16) and 0xFFFF).toInt()
        ctx.dx = (ticks and 0xFFFF).toInt()
        ctx.al = 0  // midnight has not passed since last call
    }

    /** AH=02h – Get RTC time as BCD values in CH/CL/DH. */
    private fun getRtcTime(ctx: VmContext) {
        val t = LocalTime.now()
        ctx.ch = bcd(t.hour)
        ctx.cl = bcd(t.minute)
        ctx.dh = bcd(t.second)
        ctx.dl = 0  // daylight saving time not in effect
        clearCF(ctx)
    }

    /** AH=04h – Get RTC date as BCD values in CH/CL/DH/DL. */
    private fun getRtcDate(ctx: VmContext) {
        val d = LocalDate.now()
        ctx.ch = bcd(d.year / 100)
        ctx.cl = bcd(d.year % 100)
        ctx.dh = bcd(d.monthValue)
        ctx.dl = bcd(d.dayOfMonth)
        clearCF(ctx)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Convert a decimal value (0–99) to BCD. */
    private fun bcd(v: Int): Int = (v / 10 shl 4) or (v % 10)

    private fun setCF(ctx: VmContext)   { ctx.eflags = ctx.eflags or 1 }
    private fun clearCF(ctx: VmContext) { ctx.eflags = ctx.eflags and 1.inv() }
}
