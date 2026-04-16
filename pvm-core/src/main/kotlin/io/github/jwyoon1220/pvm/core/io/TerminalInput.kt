package io.github.jwyoon1220.pvm.core.io

import io.github.jwyoon1220.pvm.api.VmInput

/** Default [VmInput] that reads from [System.in]. */
class TerminalInput : VmInput {
    override fun read(): Int = System.`in`.read()
    override fun hasInput(): Boolean = System.`in`.available() > 0
}
