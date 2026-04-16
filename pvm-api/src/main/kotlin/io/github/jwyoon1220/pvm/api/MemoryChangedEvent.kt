package io.github.jwyoon1220.pvm.api

/**
 * Event fired whenever a memory write occurs within a watched address range.
 *
 * @param address    Physical address of the first byte written.
 * @param value      Raw value passed to the write (width determined by [byteCount]).
 * @param byteCount  Number of bytes written (1, 2, or 4).
 */
data class MemoryChangedEvent(val address: Int, val value: Int, val byteCount: Int)
