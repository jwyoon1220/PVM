package io.github.jwyoon1220.pvm.core.cpu

class CPU {
    var eax: Int = 0; var ebx: Int = 0; var ecx: Int = 0; var edx: Int = 0
    var esi: Int = 0; var edi: Int = 0; var esp: Int = 0; var ebp: Int = 0
    var eip: Int = 0
    var eflags: Int = 0x0002  // bit 1 always set per x86 spec
    var halted: Boolean = false

    var cs: Int = 0; var ds: Int = 0; var ss: Int = 0
    var es: Int = 0; var fs: Int = 0; var gs: Int = 0

    var ax: Int get() = eax and 0xFFFF; set(v) { eax = (eax and -0x10000) or (v and 0xFFFF) }
    var al: Int get() = eax and 0xFF;   set(v) { eax = (eax and -0x100) or (v and 0xFF) }
    var ah: Int get() = (eax shr 8) and 0xFF; set(v) { eax = (eax and -0xFF01) or ((v and 0xFF) shl 8) }

    var bx: Int get() = ebx and 0xFFFF; set(v) { ebx = (ebx and -0x10000) or (v and 0xFFFF) }
    var bl: Int get() = ebx and 0xFF;   set(v) { ebx = (ebx and -0x100) or (v and 0xFF) }
    var bh: Int get() = (ebx shr 8) and 0xFF; set(v) { ebx = (ebx and -0xFF01) or ((v and 0xFF) shl 8) }

    var cx: Int get() = ecx and 0xFFFF; set(v) { ecx = (ecx and -0x10000) or (v and 0xFFFF) }
    var cl: Int get() = ecx and 0xFF;   set(v) { ecx = (ecx and -0x100) or (v and 0xFF) }
    var ch: Int get() = (ecx shr 8) and 0xFF; set(v) { ecx = (ecx and -0xFF01) or ((v and 0xFF) shl 8) }

    var dx: Int get() = edx and 0xFFFF; set(v) { edx = (edx and -0x10000) or (v and 0xFFFF) }
    var dl: Int get() = edx and 0xFF;   set(v) { edx = (edx and -0x100) or (v and 0xFF) }
    var dh: Int get() = (edx shr 8) and 0xFF; set(v) { edx = (edx and -0xFF01) or ((v and 0xFF) shl 8) }

    var si: Int get() = esi and 0xFFFF; set(v) { esi = (esi and -0x10000) or (v and 0xFFFF) }
    var di: Int get() = edi and 0xFFFF; set(v) { edi = (edi and -0x10000) or (v and 0xFFFF) }
    var sp: Int get() = esp and 0xFFFF; set(v) { esp = (esp and -0x10000) or (v and 0xFFFF) }
    var bp: Int get() = ebp and 0xFFFF; set(v) { ebp = (ebp and -0x10000) or (v and 0xFFFF) }

    fun dump() {
        println("CPU REGISTER DUMP")
        println(String.format("| EAX: %08X (AX:%04X, AH:%02X, AL:%02X)              |", eax, ax, ah, al))
        println(String.format("| EBX: %08X (BX:%04X, BH:%02X, BL:%02X)              |", ebx, bx, bh, bl))
        println(String.format("| ECX: %08X (CX:%04X, CH:%02X, CL:%02X)              |", ecx, cx, ch, cl))
        println(String.format("| EDX: %08X (DX:%04X, DH:%02X, DL:%02X)              |", edx, dx, dh, dl))
        println()
        println(String.format("| ESI: %08X  EDI: %08X  ESP: %08X  EBP: %08X |", esi, edi, esp, ebp))
        println(String.format("| EIP: %08X  EFLAGS: %08X                        |", eip, eflags))
        println()
        println(String.format("| CS: %04X  DS: %04X  SS: %04X  ES: %04X  FS: %04X  GS: %04X |", cs, ds, ss, es, fs, gs))
    }
}
