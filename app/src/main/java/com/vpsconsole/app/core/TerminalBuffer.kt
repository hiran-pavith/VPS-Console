package com.vpsconsole.app.core

/**
 * Small terminal text buffer: strips ANSI escape sequences, handles carriage
 * returns and backspaces so interactive shell output stays readable.
 *
 * PTYs translate '\n' into "\r\n". A bare '\r' means "return cursor to column
 * zero" (overwrite the current line), but "\r\n" is just a newline and must
 * NOT erase the line. A pending flag handles CRLF pairs split across reads.
 */
class TerminalBuffer(private val maxChars: Int = 40_000) {
    private val sb = StringBuilder()
    private var pendingCR = false

    var version: Int = 0
        private set

    val text: String get() = sb.toString()

    fun append(chunk: String) {
        var i = 0
        while (i < chunk.length) {
            val c = chunk[i]
            // Resolve a CR that was the last char of a previous chunk.
            if (pendingCR) {
                pendingCR = false
                if (c == '\n') {
                    sb.append('\n')
                    i++
                    continue
                }
                // Bare CR: truncate current line (cursor home).
                truncateToLineStart()
            }
            when (c) {
                ESC -> {
                    i = when {
                        i + 1 < chunk.length && chunk[i + 1] == '[' -> skipCsi(chunk, i + 2)
                        i + 1 < chunk.length && chunk[i + 1] == ']' -> skipOsc(chunk, i + 2)
                        else -> i + 2
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

    private fun truncateToLineStart() {
        val lineStart = sb.lastIndexOf("\n")
        sb.setLength(if (lineStart >= 0) lineStart + 1 else 0)
    }

    fun clear() {
        sb.setLength(0)
        pendingCR = false
        version++
    }

    private fun trim() {
        if (sb.length > maxChars) sb.delete(0, sb.length - maxChars)
    }

    private fun skipCsi(chunk: String, from: Int): Int {
        var i = from
        while (i < chunk.length) {
            val c = chunk[i++]
            if (c in '@'..'~') return i
        }
        return i
    }

    private fun skipOsc(chunk: String, from: Int): Int {
        var i = from
        while (i < chunk.length) {
            if (chunk[i] == BEL) return i + 1
            if (chunk[i] == ESC && i + 1 < chunk.length && chunk[i + 1] == '\\') return i + 2
            i++
        }
        return i
    }

    companion object {
        private const val ESC = '\u001B'
        private const val BEL = '\u0007'
    }
}
