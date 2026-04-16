package io.github.jwyoon1220.pvm.api

/**
 * Event fired on an I/O port access (IN or OUT instruction).
 *
 * @param port     The 16-bit I/O port number (0x0000–0xFFFF).
 * @param value    The byte value read or written.
 * @param isWrite  `true` for OUT (write to device), `false` for IN (read from device).
 */
data class PortEvent(val port: Int, val value: Int, val isWrite: Boolean)
