package io.github.jwyoon1220.pvm.api

/** Listener that is notified for every memory write in a registered region. */
fun interface MemoryAccessor {
    fun onWrite(physicalAddress: Int, value: Int, byteCount: Int)
}
