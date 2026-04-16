package io.github.jwyoon1220.pvm.addons

import io.github.jwyoon1220.pvm.core.VM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for [BiosDiskAddon] (INT 13h).
 */
class BiosDiskAddonTest {

    private fun runInt13(disk: ByteArray? = null, setup: VM.() -> Unit): VM {
        val vm = VM()
        vm.registerAddon(BiosDiskAddon(disk))
        vm.memory.write8(0, 0xCD); vm.memory.write8(1, 0x13); vm.memory.write8(2, 0xF4)
        vm.cpu.eip = 0
        vm.setup()
        vm.run()
        return vm
    }

    // ── AH=00h ── Reset ───────────────────────────────────────────────────────

    @Test fun `AH=00h reset always succeeds`() {
        val vm = runInt13 { cpu.ah = 0x00 }
        assertEquals(0x00, vm.cpu.ah,          "status byte 0 on success")
        assertEquals(0,    vm.cpu.eflags and 1, "CF=0 on success")
        vm.close()
    }

    // ── AH=02h ── Read sectors ────────────────────────────────────────────────

    @Test fun `AH=02h reads first sector of a known disk image`() {
        val disk = ByteArray(BiosDiskAddon.SECTOR_BYTES * 2)
        disk[0] = 0xEB.toByte()
        disk[1] = 0xFE.toByte()

        val vm = runInt13(disk) {
            cpu.ah  = 0x02
            cpu.al  = 1       // 1 sector
            cpu.ch  = 0       // cylinder 0
            cpu.cl  = 1       // sector 1 (1-based)
            cpu.dh  = 0       // head 0
            cpu.dl  = 0       // drive 0
            cpu.ebx = 0x1000  // buffer at 0x1000
        }

        assertEquals(0x00, vm.cpu.ah,          "status 0")
        assertEquals(0,    vm.cpu.eflags and 1, "CF=0")
        assertEquals(0xEB, vm.memory.read8(0x1000), "first byte of MBR")
        assertEquals(0xFE, vm.memory.read8(0x1001), "second byte of MBR")
        vm.close()
    }

    @Test fun `AH=02h with no disk image returns zeroes`() {
        val vm = runInt13 {
            cpu.ah  = 0x02
            cpu.al  = 1
            cpu.ch  = 0; cpu.cl = 1; cpu.dh = 0; cpu.dl = 0
            cpu.ebx = 0x2000
        }
        assertEquals(0x00, vm.cpu.ah,          "status 0 even without disk")
        assertEquals(0,    vm.cpu.eflags and 1, "CF=0")
        assertEquals(0,    vm.memory.read8(0x2000), "zeroes when no image")
        vm.close()
    }

    @Test fun `AH=02h out-of-range sector returns error`() {
        // 1-sector disk: reading sector 2 should fail
        val disk = ByteArray(BiosDiskAddon.SECTOR_BYTES)
        val vm = runInt13(disk) {
            cpu.ah  = 0x02
            cpu.al  = 1
            cpu.ch  = 0; cpu.cl = 2   // sector 2 — beyond end
            cpu.dh  = 0; cpu.dl = 0
            cpu.ebx = 0x3000
        }
        assertTrue(vm.cpu.eflags and 1 != 0, "CF=1 on error")
        vm.close()
    }

    // ── AH=03h ── Write sectors ───────────────────────────────────────────────

    @Test fun `AH=03h writes data to disk image and can be read back`() {
        val disk = ByteArray(BiosDiskAddon.SECTOR_BYTES)
        val vm   = VM()
        vm.registerAddon(BiosDiskAddon(disk))
        // Program: write sector (INT 13h), then read it back (INT 13h), then HLT
        val prog = byteArrayOf(
            0xB4.toByte(), 0x03, 0xCD.toByte(), 0x13,  // write: AH=03h INT 13h
            0xB4.toByte(), 0x02, 0xCD.toByte(), 0x13,  // read:  AH=02h INT 13h
            0xF4.toByte()
        )
        prog.forEachIndexed { i, b -> vm.memory.write8(i, b.toInt() and 0xFF) }

        // Write sentinel bytes to the source buffer in VM memory
        vm.memory.write8(0x4000, 0xAB)
        vm.memory.write8(0x4001, 0xCD)

        // Common CHS: cylinder 0, head 0, sector 1, drive 0
        vm.cpu.al  = 1
        vm.cpu.ch  = 0; vm.cpu.cl = 1; vm.cpu.dh = 0; vm.cpu.dl = 0
        vm.cpu.ebx = 0x4000
        vm.cpu.eip = 0

        vm.run()

        // After read-back, the buffer at 0x4000 should match what was written
        assertEquals(0x00,          vm.cpu.ah,          "status 0 on read-back")
        assertEquals(0,             vm.cpu.eflags and 1, "CF=0")
        assertEquals(0xAB,          vm.memory.read8(0x4000), "first byte read back")
        assertEquals(0xCD,          vm.memory.read8(0x4001), "second byte read back")
        vm.close()
    }

    @Test fun `AH=03h with no disk image returns write-protected error`() {
        val vm = runInt13 {
            cpu.ah = 0x03
            cpu.al = 1
            cpu.ch = 0; cpu.cl = 1; cpu.dh = 0; cpu.dl = 0
            cpu.ebx = 0x5000
        }
        assertTrue(vm.cpu.eflags and 1 != 0, "CF=1 (no disk — write-protected)")
        vm.close()
    }

    // ── AH=08h ── Get drive parameters ───────────────────────────────────────

    @Test fun `AH=08h returns geometry for 1_44 MB floppy`() {
        val vm = runInt13 { cpu.ah = 0x08 }
        assertEquals(0x00, vm.cpu.ah,          "status 0")
        assertEquals(0,    vm.cpu.eflags and 1, "CF=0")
        assertEquals(0x04, vm.cpu.bl,           "drive type: 3.5\" HD")
        assertEquals(1,    vm.cpu.dl,           "one drive reported")
        assertTrue(vm.cpu.dh > 0,              "at least one head")
        vm.close()
    }
}
