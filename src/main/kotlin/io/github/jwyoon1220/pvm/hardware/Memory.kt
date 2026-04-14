package io.github.jwyoon1220.pvm.hardware

import sun.nio.ch.DirectBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Memory(
    val size: Int = 1024 * 1024,
) {
    private val buffer = ByteBuffer.allocateDirect(size).apply {
        order(ByteOrder.LITTLE_ENDIAN)
    }

    // --- 8-bit Read/Write ---
    fun read8(address: Int): Int = buffer.get(address).toInt() and 0xFF

    fun write8(address: Int, value: Int) {
        buffer.put(address, (value and 0xFF).toByte())
    }

    // --- 16-bit Read/Write (WORD) ---
    fun read16(address: Int): Int = buffer.getShort(address).toInt() and 0xFFFF

    fun write16(address: Int, value: Int) {
        buffer.putShort(address, (value and 0xFFFF).toShort())
    }

    // --- 32-bit Read/Write (x86 DWORD) ---
    fun read32(address: Int): Int = buffer.getInt(address)

    fun write32(address: Int, value: Int) {
        buffer.putInt(address, value)
    }


    fun dump(address: Int, length: Int, rowSize: Int = 16) {
        println("Memory Dump at ${address.toString(16).uppercase().padStart(8, '0')}:")

        // 상단 오프셋 가이드
        print("        ")
        for (i in 0 until rowSize) {
            print(String.format("%02X ", i))
        }
        println(" | ASCII")
        println("-".repeat(10 + rowSize * 3 + rowSize))

        for (i in 0 until length step rowSize) {
            val currentAddr = address + i
            // 1. 주소 출력 (6자리)
            print(String.format("%06X  ", currentAddr))

            // 2. Hex 데이터 출력
            for (j in 0 until rowSize) {
                if (i + j < length) {
                    print(String.format("%02X ", read8(currentAddr + j)))
                } else {
                    print("   ")
                }
            }

            print(" | ")

            // 3. ASCII 문자 출력
            for (j in 0 until rowSize) {
                if (i + j < length) {
                    val b = read8(currentAddr + j)
                    // 읽을 수 있는 문자 범위(32~126)면 출력, 아니면 '.' 출력
                    if (b in 32..126) {
                        print(b.toChar())
                    } else {
                        print(".")
                    }
                }
            }
            println()
        }
    }


}