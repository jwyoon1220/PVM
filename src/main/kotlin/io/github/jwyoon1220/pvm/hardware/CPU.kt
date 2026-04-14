package io.github.jwyoon1220.pvm.hardware

class CPU {
    // --- 범용 레지스터 (32비트) ---
    var eax: Int = 0
    var ebx: Int = 0
    var ecx: Int = 0
    var edx: Int = 0

    // --- 인덱스 및 포인터 레지스터 (32비트) ---
    var esi: Int = 0
    var edi: Int = 0
    var esp: Int = 0
    var ebp: Int = 0

    // --- 제어 레지스터 ---
    var eip: Int = 0
    var eflags: Int = 0

    // --- 세그먼트 레지스터 (16비트) ---
    var cs: Int = 0
    var ds: Int = 0
    var ss: Int = 0
    var es: Int = 0
    var fs: Int = 0
    var gs: Int = 0

    // --- EAX 계층 접근 (AX, AH, AL) ---
    var ax: Int
        get() = eax and 0xFFFF
        set(value) { eax = (eax and -0x10000) or (value and 0xFFFF) }
    var al: Int
        get() = eax and 0xFF
        set(value) { eax = (eax and -0x100) or (value and 0xFF) }
    var ah: Int
        get() = (eax shr 8) and 0xFF
        set(value) { eax = (eax and -0xFF01) or ((value and 0xFF) shl 8) }

    // --- EBX 계층 접근 (BX, BH, BL) ---
    var bx: Int
        get() = ebx and 0xFFFF
        set(value) { ebx = (ebx and -0x10000) or (value and 0xFFFF) }
    var bl: Int
        get() = ebx and 0xFF
        set(value) { ebx = (ebx and -0x100) or (value and 0xFF) }
    var bh: Int
        get() = (ebx shr 8) and 0xFF
        set(value) { ebx = (ebx and -0xFF01) or ((value and 0xFF) shl 8) }

    // --- ECX 계층 접근 (CX, CH, CL) ---
    var cx: Int
        get() = ecx and 0xFFFF
        set(value) { ecx = (ecx and -0x10000) or (value and 0xFFFF) }
    var cl: Int
        get() = ecx and 0xFF
        set(value) { ecx = (ecx and -0x100) or (value and 0xFF) }
    var ch: Int
        get() = (ecx shr 8) and 0xFF
        set(value) { ecx = (ecx and -0xFF01) or ((value and 0xFF) shl 8) }

    // --- EDX 계층 접근 (DX, DH, DL) ---
    var dx: Int
        get() = edx and 0xFFFF
        set(value) { edx = (edx and -0x10000) or (value and 0xFFFF) }
    var dl: Int
        get() = edx and 0xFF
        set(value) { edx = (edx and -0x100) or (value and 0xFF) }
    var dh: Int
        get() = (edx shr 8) and 0xFF
        set(value) { edx = (edx and -0xFF01) or ((value and 0xFF) shl 8) }

    // --- 기타 16비트 포인터 접근 ---
    var si: Int
        get() = esi and 0xFFFF
        set(value) { esi = (esi and -0x10000) or (value and 0xFFFF) }
    var di: Int
        get() = edi and 0xFFFF
        set(value) { edi = (edi and -0x10000) or (value and 0xFFFF) }
    var sp: Int
        get() = esp and 0xFFFF
        set(value) { esp = (esp and -0x10000) or (value and 0xFFFF) }
    var bp: Int
        get() = ebp and 0xFFFF
        set(value) { ebp = (ebp and -0x10000) or (value and 0xFFFF) }

    /**
     * 현재 CPU 레지스터 상태 덤프
     */
    fun dump() {
        println("\n┌─────────────────── CPU REGISTER DUMP ───────────────────┐")
        println(String.format("│ EAX: %08X (AX:%04X, AH:%02X, AL:%02X)  │", eax, ax, ah, al))
        println(String.format("│ EBX: %08X (BX:%04X, BH:%02X, BL:%02X)  │", ebx, bx, bh, bl))
        println(String.format("│ ECX: %08X (CX:%04X, CH:%02X, CL:%02X)  │", ecx, cx, ch, cl))
        println(String.format("│ EDX: %08X (DX:%04X, DH:%02X, DL:%02X)  │", edx, dx, dh, dl))
        println("├─────────────────────────────────────────────────────────┤")
        println(String.format("│ ESI: %08X  EDI: %08X  ESP: %08X  EBP: %08X │", esi, edi, esp, ebp))
        println(String.format("│ EIP: %08X  EFLAGS: %08X                        │", eip, eflags))
        println("├─────────────────────────────────────────────────────────┤")
        println(String.format("│ CS: %04X  DS: %04X  SS: %04X  ES: %04X  FS: %04X  GS: %04X │", cs, ds, ss, es, fs, gs))
        println("└─────────────────────────────────────────────────────────┘")
    }
}