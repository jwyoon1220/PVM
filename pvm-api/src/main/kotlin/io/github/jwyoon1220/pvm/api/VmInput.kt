package io.github.jwyoon1220.pvm.api

/** Abstraction over the keyboard / stdin data source injected into the VM. */
interface VmInput {
    /** Blocking read; returns the character code, or -1 on EOF. */
    fun read(): Int

    /** Returns true if at least one character is available without blocking. */
    fun hasInput(): Boolean
}
