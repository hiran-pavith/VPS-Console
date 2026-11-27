package com.vpsconsole.app.core

/**
 * Terminal text buffer: strips ANSI color sequences, handles carriage returns
 * and backspaces so interactive shell output stays readable.
 *
 * PTYs translate '\n' into "\r\n". A bare '\r' means "return cursor to column
 * zero" (overwrite the current line), but "\r\n" is just a newline and must
 * NOT erase the line. A pending flag handles CRLF pairs split across reads.
 *
 * Escape/CSI/OSC sequences can also be split across SSH reads, so the parser
 * is a small state machine instead of assuming a whole sequence is in one
 * chunk. Full-screen programs (top/htop/vim) redraw with cursor-home and
 * clear-screen sequences; those replace the previous screen instead of being
 * appended as a new block of text.
 */
class TerminalBuffer(private val maxChars: Int = 40_000) {
    private val sb = StringBuilder()
    private var pendingCR = false
    private var pendingEscInOsc = false

    private enum class Esc { NONE, ESC, CSI, OSC, CHARSET }
    private var esc = Esc.NONE
    private val params = StringBuilder()

    var version: Int = 0
        private set

    val text: String get() = sb.toString()

    fun append(chunk: String) {
        var i = 0
        while (i < chunk.length) {
            when (esc) {
                Esc.CSI -> {
                    val c = chunk[i++]
                    if (c in '@'..'~') {
                        applyCsi(c, params.toString())
                        params.setLength(0)
                        esc = Esc.NONE
                    } else {
                        params.append(c)
                    }
                    continue
                }

                Esc.OSC -> {
                    if (pendingEscInOsc) {
                        pendingEscInOsc = false
                        if (chunk[i] == '\\') {
                            i++
                            esc = Esc.NONE
                        }
                        // Otherwise the ESC belonged to another sequence; keep
                        // consuming OSC payload until BEL or ESC \ arrives.
                        continue
                    }
                    val c = chunk[i++]
                    if (c == BEL) {
                        esc = Esc.NONE
                    } else if (c == ESC) {
                        if (i < chunk.length && chunk[i] == '\\') {
                            i++
                            esc = Esc.NONE
                        } else {
                            // ESC was the final byte of this chunk — remember it
                            // so an ESC \ split across reads is not lost.
                            pendingEscInOsc = true
                        }
                    }
                    continue
                }

                Esc.CHARSET -> {
                    // ESC ( B / ESC ) 0 etc. — consume the final charset byte.
                    i++
                    esc = Esc.NONE
                    continue
                }

                Esc.ESC -> {
                    val c = chunk[i++]
                    when {
                        c == '[' -> { esc = Esc.CSI; params.setLength(0) }
                        c == ']' -> esc = Esc.OSC
                        c in "()#%*+-./" -> esc = Esc.CHARSET
                        else -> esc = Esc.NONE // ESC 7 / ESC 8 / single-byte escape
                    }
                    continue
                }

                Esc.NONE -> Unit
            }

            // Resolve a CR that was the last char of a previous chunk.
            if (pendingCR) {
                pendingCR = false
                val c = chunk[i]
                if (c == '\n') {
                    sb.append('\n')
                    i++
                    continue
                }
                // Bare CR: truncate current line (cursor home).
                truncateToLineStart()
            }

            val c = chunk[i]
            when (c) {
                ESC -> {
                    if (i + 1 < chunk.length) {
                        val next = chunk[i + 1]
                        when {
                            next == '[' -> { esc = Esc.CSI; params.setLength(0); i += 2 }
                            next == ']' -> { esc = Esc.OSC; i += 2 }
                            next in "()#%*+-./" -> { esc = Esc.CHARSET; i += 2 }
                            else -> i += 2
                        }
                    } else {
                        esc = Esc.ESC
                        i++
                    }
                }

                '\r' -> {
                    if (i + 1 < chunk.length) {
                        if (chunk[i + 1] == '\n') {
                            sb.append('\n')
                            i += 2
                        } else {
                            truncateToLineStart()
                            i++
                        }
                    } else {
                        pendingCR = true
                        i++
                    }
                }

                '\b' -> {
                    if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
                    i++
                }

                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        trim()
        version++
    }

    private fun applyCsi(final: Char, p: String) {
        when (final) {
            // Erase display. ESC[J defaults to "cursor to end", but full-screen
            // apps use ESC[2J (and clear uses ESC[3J) for the whole screen.
            'J' -> if (p.isEmpty() || p == "2" || p == "3") clear()

            // Cursor home (1;1 or no params) starts a fresh full-screen frame.
            'H', 'f' -> {
                val parts = p.split(';')
                val row = parts.getOrNull(0)?.toIntOrNull() ?: 1
                val col = parts.getOrNull(1)?.toIntOrNull() ?: 1
                if (row <= 1 && col <= 1) clear()
            }

            // Erase line. We do not track columns, so truncate to the start of
            // the current logical line.
            'K' -> truncateToLineStart()
        }
    }

    private fun truncateToLineStart() {
        val lineStart = sb.lastIndexOf("\n")
        sb.setLength(if (lineStart >= 0) lineStart + 1 else 0)
    }

    fun clear() {
        sb.setLength(0)
        pendingCR = false
        pendingEscInOsc = false
        esc = Esc.NONE
        params.setLength(0)
        version++
    }

    private fun trim() {
        if (sb.length > maxChars) sb.delete(0, sb.length - maxChars)
    }

    companion object {
        private const val ESC = '\u001B'
        private const val BEL = '\u0007'
    }
}
