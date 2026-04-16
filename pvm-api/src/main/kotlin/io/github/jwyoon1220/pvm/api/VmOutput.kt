package io.github.jwyoon1220.pvm.api

/** Abstraction over the display / stdout sink injected into the VM. */
fun interface VmOutput {
    fun write(ch: Char)
}
