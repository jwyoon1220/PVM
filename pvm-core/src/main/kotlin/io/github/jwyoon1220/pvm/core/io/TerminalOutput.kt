package io.github.jwyoon1220.pvm.core.io

import io.github.jwyoon1220.pvm.api.VmOutput

/** Default [VmOutput] that writes to [System.out]. */
class TerminalOutput : VmOutput {
    override fun write(ch: Char) = print(ch)
}
