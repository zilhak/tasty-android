package dev.tasty.android.terminal

import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyEncoderTest {
    private fun encode(
        keyCode: Int,
        char: Char? = null,
        modifiers: KeyModifiers = KeyModifiers(),
        applicationCursor: Boolean = false,
    ): String? = KeyEncoder.encode(keyCode, char?.code ?: 0, modifiers, applicationCursor)?.toString(Charsets.UTF_8)

    @Test
    fun enterAndBackspaceSendCarriageReturnAndDelete() {
        assertArrayEquals("\r".toByteArray(), KeyEncoder.encode(KeyEvent.KEYCODE_ENTER, 0, KeyModifiers(), false))
        assertArrayEquals(byteArrayOf(0x7f), KeyEncoder.encode(KeyEvent.KEYCODE_DEL, 0, KeyModifiers(), false))
    }

    @Test
    fun arrowsFollowCursorKeyMode() {
        assertEquals("\u001b[A", encode(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("\u001bOA", encode(KeyEvent.KEYCODE_DPAD_UP, applicationCursor = true))
    }

    @Test
    fun ctrlLetterSendsControlCode() {
        val bytes = KeyEncoder.encode(KeyEvent.KEYCODE_C, 'c'.code, KeyModifiers(ctrl = true), false)
        assertArrayEquals(byteArrayOf(3), bytes)
    }

    @Test
    fun altPrefixesEscape() {
        assertEquals("\u001bx", encode(KeyEvent.KEYCODE_X, 'x', KeyModifiers(alt = true)))
    }

    @Test
    fun functionKeysUseXtermSequences() {
        assertEquals("\u001bOP", encode(KeyEvent.KEYCODE_F1))
        assertEquals("\u001b[15~", encode(KeyEvent.KEYCODE_F5))
        assertEquals("\u001b[24~", encode(KeyEvent.KEYCODE_F12))
    }

    @Test
    fun keysWithoutInputReturnNull() {
        assertNull(KeyEncoder.encode(KeyEvent.KEYCODE_SHIFT_LEFT, 0, KeyModifiers(), false))
    }

    @Test
    fun softKeyboardNewlineBecomesCarriageReturn() {
        assertArrayEquals("ls\r".toByteArray(), KeyEncoder.text("ls\n"))
        assertArrayEquals("한".toByteArray(Charsets.UTF_8), KeyEncoder.text("한"))
    }

    private fun assertEquals(
        expected: String,
        actual: String?,
    ) = org.junit.Assert.assertEquals(expected, actual)
}
