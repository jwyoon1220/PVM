package io.github.jwyoon1220.pvm.core.memory

import io.github.jwyoon1220.pvm.api.MemoryAccessor
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder

/**
 * Physical memory implemented as an off-heap Panama MemorySegment.
 * All multi-byte reads/writes use little-endian byte order to match x86.
 *
 * Write listeners (MemoryAccessor) are notified for every write that
 * overlaps their registered physical address range.
 */
class MemoryBus(val physicalSize: Int = 1024 * 1024) : AutoCloseable {

    private val arena = Arena.ofShared()
    private val segment = arena.allocate(physicalSize.toLong())

    private val leShort  = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
    private val leInt    = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)

    private data class AccessorEntry(val start: Int, val endInclusive: Int, val accessor: MemoryAccessor)
    private val writeAccessors = mutableListOf<AccessorEntry>()

    fun registerWriteAccessor(start: Int, endInclusive: Int, accessor: MemoryAccessor) {
        writeAccessors.add(AccessorEntry(start, endInclusive, accessor))
    }

    fun read8(address: Int): Int {
        checkBounds(address, 1)
        return segment.get(ValueLayout.JAVA_BYTE, address.toLong()).toInt() and 0xFF
    }

    fun read16(address: Int): Int {
        checkBounds(address, 2)
        return segment.get(leShort, address.toLong()).toInt() and 0xFFFF
    }

    fun read32(address: Int): Int {
        checkBounds(address, 4)
        return segment.get(leInt, address.toLong())
    }

    fun write8(address: Int, value: Int) {
        checkBounds(address, 1)
        segment.set(ValueLayout.JAVA_BYTE, address.toLong(), (value and 0xFF).toByte())
        notifyWriteAccessors(address, value, 1)
    }

    fun write16(address: Int, value: Int) {
        checkBounds(address, 2)
        segment.set(leShort, address.toLong(), (value and 0xFFFF).toShort())
        notifyWriteAccessors(address, value, 2)
    }

    fun write32(address: Int, value: Int) {
        checkBounds(address, 4)
        segment.set(leInt, address.toLong(), value)
        notifyWriteAccessors(address, value, 4)
    }

    fun load(address: Int, bytes: ByteArray) {
        for (i in bytes.indices) write8(address + i, bytes[i].toInt())
    }

    fun dump(address: Int, length: Int, rowSize: Int = 16) {
        println("Memory Dump @ 0x${address.toString(16).uppercase().padStart(8, '0')}:")
        print("        ")
        for (i in 0 until rowSize) print(String.format("%02X ", i))
        println(" | ASCII")
        println("-".repeat(10 + rowSize * 3 + rowSize))
        for (i in 0 until length step rowSize) {
            val cur = address + i
            print(String.format("%06X  ", cur))
            for (j in 0 until rowSize) {
                if (i + j < length) print(String.format("%02X ", read8(cur + j))) else print("   ")
            }
            print(" | ")
            for (j in 0 until rowSize) {
                if (i + j < length) { val b = read8(cur + j); print(if (b in 32..126) b.toChar() else '.') }
            }
            println()
        }
    }

    override fun close() = arena.close()

    private fun checkBounds(address: Int, size: Int) {
        require(address >= 0 && address + size <= physicalSize) {
            "Memory access out of bounds: address=0x${address.toString(16)}, size=$size, physicalSize=$physicalSize"
        }
    }

    private fun notifyWriteAccessors(address: Int, value: Int, byteCount: Int) {
        val end = address + byteCount - 1
        for (entry in writeAccessors) {
            if (address <= entry.endInclusive && end >= entry.start) {
                entry.accessor.onWrite(address, value, byteCount)
            }
        }
    }
}
