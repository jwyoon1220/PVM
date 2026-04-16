package io.github.jwyoon1220.pvm.addons

import io.github.jwyoon1220.pvm.api.AddonContext
import io.github.jwyoon1220.pvm.api.VmAddon
import io.github.jwyoon1220.pvm.api.VmContext

/**
 * LLE addon for BIOS INT 13h (Disk Services).
 *
 * Geometry defaults to a 1.44 MB 3.5" floppy:
 * 80 cylinders × 2 heads × 18 sectors/track = 2880 sectors × 512 bytes.
 *
 * Pass a [ByteArray] disk image to enable read/write; without one, reads
 * return zeroes and writes are no-ops (drive not ready).
 *
 * Supported sub-functions:
 * - AH=00h Reset disk
 * - AH=02h Read sectors  (CHS → ES:BX buffer)
 * - AH=03h Write sectors (CHS ← ES:BX buffer)
 * - AH=04h Verify sectors (stub — always succeeds)
 * - AH=08h Get drive parameters
 * - AH=15h Get disk type
 * - AH=16h Detect disk change
 */
class BiosDiskAddon(diskImage: ByteArray? = null) : VmAddon {
    override val id = "bios-disk"

    companion object {
        const val SECTOR_BYTES      = 512
        const val SECTORS_PER_TRACK = 18
        const val HEADS             = 2
        const val CYLINDERS         = 80
    }

    /** Mutable working copy of the supplied disk image (supports writes). */
    private val disk: ByteArray? = diskImage?.copyOf()

    override fun onEnable(context: AddonContext) {
        context.interrupts.register(0x13) { _, ctx -> handle(ctx) }
    }

    private fun handle(ctx: VmContext) {
        when (ctx.ah) {
            0x00 -> { ctx.ah = 0x00; clearCF(ctx) }   // Reset disk — always succeeds
            0x02 -> readSectors(ctx)
            0x03 -> writeSectors(ctx)
            0x04 -> { ctx.ah = 0x00; clearCF(ctx) }   // Verify — success stub
            0x08 -> getDriveParams(ctx)
            0x15 -> { ctx.ah = 0x02; clearCF(ctx) }   // Disk type: floppy with change-line
            0x16 -> { ctx.ah = 0x01; clearCF(ctx) }   // Disk change: change occurred (safe default)
            else -> { ctx.ah = 0x01; setCF(ctx) }     // Invalid command
        }
    }

    // ── AH=02h ── Read sectors ───────────────────────────────────────────────
    // AL  = number of sectors to read (≥1)
    // CH  = cylinder (bits 7-0)
    // CL  = sector (bits 5-0, 1-based) | cylinder high (bits 7-6 → cyl bits 9-8)
    // DH  = head
    // DL  = drive
    // ES:BX = destination buffer (flat: ES=0 assumed)

    private fun readSectors(ctx: VmContext) {
        val count   = ctx.al
        val cyl     = (ctx.ch and 0xFF) or ((ctx.cl shr 6 and 0x03) shl 8)
        val sec     = ctx.cl and 0x3F
        val head    = ctx.dh and 0xFF
        val bufAddr = ctx.ebx and 0xFFFF
        val lba     = chsToLba(cyl, head, sec)
        val start   = lba * SECTOR_BYTES
        val length  = count * SECTOR_BYTES

        if (disk == null) {
            // No disk image — return zeroes (emulates empty drive)
            repeat(length) { ctx.write8(bufAddr + it, 0) }
            ctx.al = count; ctx.ah = 0x00; clearCF(ctx)
            return
        }
        if (start < 0 || start + length > disk.size) {
            ctx.ah = 0x04; ctx.al = 0; setCF(ctx); return  // sector not found
        }
        repeat(length) { ctx.write8(bufAddr + it, disk[start + it].toInt() and 0xFF) }
        ctx.al = count; ctx.ah = 0x00; clearCF(ctx)
    }

    // ── AH=03h ── Write sectors ──────────────────────────────────────────────

    private fun writeSectors(ctx: VmContext) {
        val count   = ctx.al
        val cyl     = (ctx.ch and 0xFF) or ((ctx.cl shr 6 and 0x03) shl 8)
        val sec     = ctx.cl and 0x3F
        val head    = ctx.dh and 0xFF
        val bufAddr = ctx.ebx and 0xFFFF
        val lba     = chsToLba(cyl, head, sec)
        val start   = lba * SECTOR_BYTES
        val length  = count * SECTOR_BYTES

        if (disk == null) { ctx.ah = 0x03; ctx.al = 0; setCF(ctx); return }  // write-protected
        if (start < 0 || start + length > disk.size) {
            ctx.ah = 0x04; ctx.al = 0; setCF(ctx); return
        }
        repeat(length) { disk[start + it] = ctx.read8(bufAddr + it).toByte() }
        ctx.al = count; ctx.ah = 0x00; clearCF(ctx)
    }

    // ── AH=08h ── Get drive parameters ───────────────────────────────────────

    private fun getDriveParams(ctx: VmContext) {
        ctx.ah = 0x00
        ctx.bl = 0x04                                               // drive type: 3.5" HD
        ctx.ch = (CYLINDERS - 1) and 0xFF                          // max cylinder (low byte)
        ctx.cl = (((CYLINDERS - 1) shr 8) shl 6) or SECTORS_PER_TRACK
        ctx.dh = HEADS - 1                                          // max head
        ctx.dl = 0x01                                               // number of drives
        clearCF(ctx)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Convert CHS (0-based cylinder, 0-based head, 1-based sector) to LBA. */
    private fun chsToLba(cyl: Int, head: Int, sec: Int): Int =
        cyl * HEADS * SECTORS_PER_TRACK + head * SECTORS_PER_TRACK + (sec - 1)

    private fun setCF(ctx: VmContext)   { ctx.eflags = ctx.eflags or 1 }
    private fun clearCF(ctx: VmContext) { ctx.eflags = ctx.eflags and 1.inv() }
}
