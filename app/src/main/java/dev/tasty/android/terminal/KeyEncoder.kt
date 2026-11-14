package dev.tasty.android.terminal

import android.view.KeyEvent

/** Modifier keys held with a key press. */
data class KeyModifiers(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
)

/** Encodes key presses into the bytes an xterm-compatible shell expects. */
object KeyEncoder {
    private const val ESC = "\u001b"
    private const val DEL: Byte = 0x7f
    private const val CTRL_MASK = 0x1f

    /**
     * Returns the bytes for [keyCode], or null when the key produces no input.
     *
     * [unicodeChar] is the character the key types without Ctrl applied (0 when none).
     * [applicationCursor] selects `ESC O x` instead of `ESC [ x` for the arrow keys.
     */
    fun encode(
        keyCode: Int,
        unicodeChar: Int,
        modifiers: KeyModifiers,
        applicationCursor: Boolean,
    ): ByteArray? {
        special(keyCode, modifiers, applicationCursor)?.let { return it }
        if (unicodeChar == 0) return null
        val typed = typedBytes(unicodeChar, modifiers.ctrl) ?: return null
        return if (modifiers.alt) ESC.toByteArray() + typed else typed
    }

    /** Text typed on the soft keyboard. A newline submits the line like the Enter key. */
    fun text(text: CharSequence): ByteArray = text.toString().replace('\n', '\r').toByteArray(Charsets.UTF_8)

    private fun special(
        keyCode: Int,
        modifiers: KeyModifiers,
        applicationCursor: Boolean,
    ): ByteArray? {
        val sequence =
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
                KeyEvent.KEYCODE_DEL -> return byteArrayOf(DEL)
                KeyEvent.KEYCODE_TAB -> if (modifiers.shift) "$ESC[Z" else "\t"
                KeyEvent.KEYCODE_ESCAPE -> ESC
                KeyEvent.KEYCODE_DPAD_UP -> arrow('A', applicationCursor)
                KeyEvent.KEYCODE_DPAD_DOWN -> arrow('B', applicationCursor)
                KeyEvent.KEYCODE_DPAD_RIGHT -> arrow('C', applicationCursor)
                KeyEvent.KEYCODE_DPAD_LEFT -> arrow('D', applicationCursor)
                KeyEvent.KEYCODE_MOVE_HOME -> "$ESC[H"
                KeyEvent.KEYCODE_MOVE_END -> "$ESC[F"
                KeyEvent.KEYCODE_INSERT -> "$ESC[2~"
                KeyEvent.KEYCODE_FORWARD_DEL -> "$ESC[3~"
                KeyEvent.KEYCODE_PAGE_UP -> "$ESC[5~"
                KeyEvent.KEYCODE_PAGE_DOWN -> "$ESC[6~"
                in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> function(keyCode - KeyEvent.KEYCODE_F1 + 1)
                else -> return null
            }
        val bytes = sequence.toByteArray()
        return if (modifiers.alt && sequence.length == 1) ESC.toByteArray() + bytes else bytes
    }

    private fun arrow(
        letter: Char,
        applicationCursor: Boolean,
    ): String = if (applicationCursor) "${ESC}O$letter" else "$ESC[$letter"

    private fun function(number: Int): String =
        when (number) {
            1 -> "${ESC}OP"
            2 -> "${ESC}OQ"
            3 -> "${ESC}OR"
            4 -> "${ESC}OS"
            else -> "$ESC[${FUNCTION_CODES[number - 5]}~"
        }

    private val FUNCTION_CODES = intArrayOf(15, 17, 18, 19, 20, 21, 23, 24)

    private fun typedBytes(
        unicodeChar: Int,
        ctrl: Boolean,
    ): ByteArray? {
        if (!ctrl) return String(Character.toChars(unicodeChar)).toByteArray(Charsets.UTF_8)
        val control =
            when (unicodeChar) {
                in 'a'.code..'z'.code, in 'A'.code..'Z'.code -> unicodeChar and CTRL_MASK
                ' '.code, '@'.code, '2'.code -> 0
                '['.code, '3'.code -> 0x1b
                '\\'.code, '4'.code -> 0x1c
                ']'.code, '5'.code -> 0x1d
                '^'.code, '6'.code -> 0x1e
                '_'.code, '-'.code, '7'.code -> CTRL_MASK
                '?'.code, '8'.code -> DEL.toInt()
                else -> return null
            }
        return byteArrayOf(control.toByte())
    }
}
