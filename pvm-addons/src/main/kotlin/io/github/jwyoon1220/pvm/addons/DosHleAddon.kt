package io.github.jwyoon1220.pvm.addons

import io.github.jwyoon1220.pvm.api.AddonContext
import io.github.jwyoon1220.pvm.api.VmAddon
import io.github.jwyoon1220.pvm.api.VmContext
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDate
import java.time.LocalTime

/**
 * High-Level Emulation (HLE) addon for DOS INT 21h.
 *
 * Handles all INT 21h functions that MS-DOS programs commonly call.
 * File I/O is backed by the host JVM filesystem; handles 0/1/2 are
 * wired to [VmContext.input] / [VmContext.output].
 *
 * Memory allocation (AH=48h/49h/4Ah) uses a simple bump allocator over
 * the conventional-memory region above 64 KB.
 */
class DosHleAddon : VmAddon {
    override val id = "dos-hle"

    // ── I/O handles ──────────────────────────────────────────────────────────
    private val fileHandles = HashMap<Int, RandomAccessFile>()
    private var nextHandle  = 3   // 0=stdin, 1=stdout, 2=stderr

    // ── Memory allocator (paragraph = 16 bytes) ──────────────────────────────
    // Heap starts at paragraph 0x1000 (64 KB) and grows toward 0xA000 (640 KB).
    private var nextHeapParagraph = 0x1000

    // ── DOS state ─────────────────────────────────────────────────────────────
    private var dtaAddress   = 0x0080   // default DTA at PSP+0x80
    private var currentDrive = 2        // 0=A, 1=B, 2=C (default C:)
    private val currentDirs  = Array(26) { "\\" }
    private var exitCode     = 0

    override fun onEnable(context: AddonContext) {
        context.interrupts.register(0x21) { _, ctx -> handle(ctx) }
    }

    @Suppress("ComplexMethod")
    private fun handle(ctx: VmContext) {
        when (ctx.ah) {
            0x00 -> ctx.halted = true                                        // Program terminate
            0x01 -> { ctx.al = ctx.input.read() and 0xFF; ctx.output.write(ctx.al.toChar()) }
            0x02 -> ctx.output.write(ctx.dl.toChar())                        // Write char to stdout
            0x06 -> directConsoleIo(ctx)
            0x07 -> ctx.al = ctx.input.read() and 0xFF                       // Read char, no echo
            0x08 -> ctx.al = ctx.input.read() and 0xFF                       // Read char, no echo
            0x09 -> writeString(ctx)
            0x0A -> bufferedInput(ctx)
            0x0B -> ctx.al = if (ctx.input.hasInput()) 0xFF else 0x00        // Check input status
            0x0C -> {                                                         // Flush + input fn
                ctx.al = if (ctx.input.hasInput()) 0xFF else 0x00
            }
            0x0E -> { currentDrive = ctx.dl and 0x1F; ctx.al = 26 }         // Select drive
            0x19 -> ctx.al = currentDrive                                    // Get current drive
            0x1A -> dtaAddress = ctx.edx and 0xFFFF                          // Set DTA (DS:DX flat)
            0x25 -> { /* Set interrupt vector — no-op in HLE mode */ }
            0x2A -> getDate(ctx)
            0x2C -> getTime(ctx)
            0x2F -> ctx.ebx = dtaAddress                                     // Get DTA → ES:BX flat
            0x30 -> { ctx.al = 3; ctx.ah = 30; ctx.bh = 0 }                 // Get DOS version 3.30
            0x35 -> ctx.ebx = 0                                              // Get int vector → 0:0
            0x38 -> clearCF(ctx)                                             // Get country info stub
            0x3C -> createFile(ctx)
            0x3D -> openFile(ctx)
            0x3E -> closeFile(ctx)
            0x3F -> readFile(ctx)
            0x40 -> writeFile(ctx)
            0x41 -> deleteFile(ctx)
            0x42 -> seekFile(ctx)
            0x43 -> getSetAttr(ctx)
            0x44 -> ioctl(ctx)
            0x47 -> getCurrentDir(ctx)
            0x48 -> allocMemory(ctx)
            0x49 -> freeMemory(ctx)
            0x4A -> modifyMemory(ctx)
            0x4B -> execProgram(ctx)
            0x4C -> { exitCode = ctx.al; ctx.halted = true }                 // Exit with code
            0x4D -> ctx.al = exitCode                                        // Get exit code
            0x56 -> renameFile(ctx)
            0x57 -> clearCF(ctx)                                             // Get/Set file date (stub)
            else -> throw UnsupportedOperationException(
                String.format("INT 21h AH=%02Xh not implemented", ctx.ah)
            )
        }
    }

    // ── AH=06h ── Direct console I/O ─────────────────────────────────────────

    private fun directConsoleIo(ctx: VmContext) {
        if (ctx.dl == 0xFF) {
            ctx.al = if (ctx.input.hasInput()) ctx.input.read() and 0xFF else 0x00
        } else {
            ctx.output.write(ctx.dl.toChar())
            ctx.al = ctx.dl
        }
    }

    // ── AH=09h ── Write '$'-terminated string ────────────────────────────────

    private fun writeString(ctx: VmContext) {
        var addr = ctx.edx  // DS:DX; flat (DS=0 in this model)
        while (true) {
            val ch = ctx.read8(addr++)
            if (ch == '$'.code) break
            ctx.output.write(ch.toChar())
        }
    }

    // ── AH=0Ah ── Buffered keyboard input ────────────────────────────────────
    // DS:DX → buffer: byte[0]=maxLen, byte[1]=actualLen (out), byte[2..]=chars

    private fun bufferedInput(ctx: VmContext) {
        val bufAddr = ctx.edx and 0xFFFF
        val maxLen  = ctx.read8(bufAddr).coerceAtLeast(1)
        val sb      = StringBuilder()
        while (sb.length < maxLen - 1) {
            val ch = ctx.input.read()
            if (ch == -1 || ch == '\r'.code || ch == '\n'.code) break
            sb.append(ch.toChar())
            ctx.output.write(ch.toChar())
        }
        ctx.output.write('\r')
        ctx.output.write('\n')
        ctx.write8(bufAddr + 1, sb.length)
        for (i in sb.indices) ctx.write8(bufAddr + 2 + i, sb[i].code)
        ctx.write8(bufAddr + 2 + sb.length, '\r'.code)
    }

    // ── AH=2Ah ── Get date ────────────────────────────────────────────────────

    private fun getDate(ctx: VmContext) {
        val d = LocalDate.now()
        ctx.cx = d.year
        ctx.dh = d.monthValue
        ctx.dl = d.dayOfMonth
        ctx.al = d.dayOfWeek.value % 7   // 0=Sunday … 6=Saturday
    }

    // ── AH=2Ch ── Get time ────────────────────────────────────────────────────

    private fun getTime(ctx: VmContext) {
        val t = LocalTime.now()
        ctx.ch = t.hour
        ctx.cl = t.minute
        ctx.dh = t.second
        ctx.dl = t.nano / 10_000_000     // hundredths of seconds
    }

    // ── AH=3Ch ── Create / truncate file ─────────────────────────────────────

    private fun createFile(ctx: VmContext) {
        val name = readCString(ctx, ctx.edx and 0xFFFF)
        runCatching { RandomAccessFile(File(name), "rw").apply { setLength(0) } }
            .onSuccess { fileHandles[nextHandle] = it; ctx.ax = nextHandle++; clearCF(ctx) }
            .onFailure { ctx.ax = 0x0003; setCF(ctx) }
    }

    // ── AH=3Dh ── Open file ───────────────────────────────────────────────────

    private fun openFile(ctx: VmContext) {
        val name   = readCString(ctx, ctx.edx and 0xFFFF)
        val ioMode = if (ctx.al and 0x03 == 0) "r" else "rw"
        runCatching { RandomAccessFile(File(name), ioMode) }
            .onSuccess { fileHandles[nextHandle] = it; ctx.ax = nextHandle++; clearCF(ctx) }
            .onFailure { ctx.ax = 0x0002; setCF(ctx) }
    }

    // ── AH=3Eh ── Close file ──────────────────────────────────────────────────

    private fun closeFile(ctx: VmContext) {
        if (ctx.bx >= 3) fileHandles.remove(ctx.bx)?.close()
        clearCF(ctx)
    }

    // ── AH=3Fh ── Read from handle ───────────────────────────────────────────

    private fun readFile(ctx: VmContext) {
        val handle  = ctx.bx
        val count   = ctx.cx
        val bufAddr = ctx.edx and 0xFFFF
        when (handle) {
            0 -> {
                var read = 0
                while (read < count) {
                    val ch = ctx.input.read().takeIf { it != -1 } ?: break
                    ctx.write8(bufAddr + read++, ch)
                }
                ctx.ax = read; clearCF(ctx)
            }
            else -> {
                val raf = fileHandles[handle] ?: run { ctx.ax = 0x0006; setCF(ctx); return }
                val buf = ByteArray(count)
                val n   = raf.read(buf).coerceAtLeast(0)
                repeat(n) { ctx.write8(bufAddr + it, buf[it].toInt() and 0xFF) }
                ctx.ax = n; clearCF(ctx)
            }
        }
    }

    // ── AH=40h ── Write to handle ────────────────────────────────────────────

    private fun writeFile(ctx: VmContext) {
        val handle  = ctx.bx
        val count   = ctx.cx
        val bufAddr = ctx.edx and 0xFFFF
        when (handle) {
            1, 2 -> {
                repeat(count) { ctx.output.write(ctx.read8(bufAddr + it).toChar()) }
                ctx.ax = count; clearCF(ctx)
            }
            else -> {
                val raf = fileHandles[handle] ?: run { ctx.ax = 0x0006; setCF(ctx); return }
                val buf = ByteArray(count) { ctx.read8(bufAddr + it).toByte() }
                raf.write(buf)
                ctx.ax = count; clearCF(ctx)
            }
        }
    }

    // ── AH=41h ── Delete file ────────────────────────────────────────────────

    private fun deleteFile(ctx: VmContext) {
        val name = readCString(ctx, ctx.edx and 0xFFFF)
        if (File(name).delete()) clearCF(ctx) else { ctx.ax = 0x0002; setCF(ctx) }
    }

    // ── AH=42h ── Seek file ───────────────────────────────────────────────────

    private fun seekFile(ctx: VmContext) {
        val raf = fileHandles[ctx.bx] ?: run { ctx.ax = 0x0006; setCF(ctx); return }
        val offset = (ctx.cx shl 16) or (ctx.edx and 0xFFFF)
        val newPos = when (ctx.al) {
            0    -> offset.toLong()
            1    -> raf.filePointer + offset
            2    -> raf.length() + offset
            else -> { ctx.ax = 0x0001; setCF(ctx); return }
        }
        raf.seek(newPos)
        ctx.dx = ((newPos shr 16) and 0xFFFF).toInt()
        ctx.ax = (newPos and 0xFFFF).toInt()
        clearCF(ctx)
    }

    // ── AH=43h ── Get / Set file attributes ──────────────────────────────────

    private fun getSetAttr(ctx: VmContext) {
        val name = readCString(ctx, ctx.edx and 0xFFFF)
        if (ctx.al == 0) {
            ctx.cx = if (File(name).exists()) 0x20 else 0x00
        }
        clearCF(ctx)
    }

    // ── AH=44h ── IOCTL ──────────────────────────────────────────────────────

    private fun ioctl(ctx: VmContext) {
        when (ctx.al) {
            0x00 -> { ctx.dx = if (ctx.bx < 3) 0x80D3 else 0x0002; clearCF(ctx) }
            0x01 -> clearCF(ctx)
            else -> { ctx.ax = 0x0001; setCF(ctx) }
        }
    }

    // ── AH=47h ── Get current directory ──────────────────────────────────────

    private fun getCurrentDir(ctx: VmContext) {
        val drive  = if (ctx.dl == 0) currentDrive else (ctx.dl - 1)
        val dir    = currentDirs.getOrElse(drive) { "\\" }.trimStart('\\')
        val bufAddr = ctx.esi and 0xFFFF   // DS:SI flat
        dir.forEachIndexed { i, c -> ctx.write8(bufAddr + i, c.code) }
        ctx.write8(bufAddr + dir.length, 0)
        clearCF(ctx)
    }

    // ── AH=48h ── Allocate memory ─────────────────────────────────────────────

    private fun allocMemory(ctx: VmContext) {
        val paragraphs = ctx.bx
        if (nextHeapParagraph + paragraphs > 0xA000) {
            ctx.bx = 0xA000 - nextHeapParagraph
            ctx.ax = 0x0008   // insufficient memory
            setCF(ctx)
        } else {
            ctx.ax = nextHeapParagraph
            nextHeapParagraph += paragraphs
            clearCF(ctx)
        }
    }

    // ── AH=49h ── Free memory ─────────────────────────────────────────────────

    private fun freeMemory(ctx: VmContext) {
        // Bump allocator: no actual free tracking — just report success.
        clearCF(ctx)
    }

    // ── AH=4Ah ── Modify memory allocation ───────────────────────────────────

    private fun modifyMemory(ctx: VmContext) {
        clearCF(ctx)   // stub: always succeed
    }

    // ── AH=4Bh ── Execute program (EXEC) ─────────────────────────────────────

    private fun execProgram(ctx: VmContext) {
        // HLE cannot load another binary — return "file not found".
        ctx.ax = 0x0002
        setCF(ctx)
    }

    // ── AH=56h ── Rename file ─────────────────────────────────────────────────

    private fun renameFile(ctx: VmContext) {
        val oldName = readCString(ctx, ctx.edx and 0xFFFF)
        val newName = readCString(ctx, ctx.edi and 0xFFFF)  // ES:DI flat
        if (File(oldName).renameTo(File(newName))) clearCF(ctx)
        else { ctx.ax = 0x0002; setCF(ctx) }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun readCString(ctx: VmContext, addr: Int): String {
        val sb = StringBuilder()
        var a  = addr
        while (true) {
            val b = ctx.read8(a++) and 0xFF
            if (b == 0) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun setCF(ctx: VmContext)   { ctx.eflags = ctx.eflags or 1 }
    private fun clearCF(ctx: VmContext) { ctx.eflags = ctx.eflags and 1.inv() }
}
