package dev.tasty.android.terminal

import dev.tasty.android.core.CellColor

/**
 * Maps cell colors from the Rust core to ARGB values.
 *
 * Indices 0..15 come from [ansi]; 16..255 are the fixed xterm 6x6x6 cube and grayscale ramp.
 */
class TerminalPalette(
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    private val ansi: IntArray,
) {
    init {
        require(ansi.size == ANSI_COUNT) { "ANSI palette needs $ANSI_COUNT colors, got ${ansi.size}" }
    }

    /** Resolves a foreground color. Bold text uses the bright variant of the first eight colors. */
    fun foreground(
        color: CellColor,
        bold: Boolean,
    ): Int =
        when (color) {
            is CellColor.Default -> foreground
            is CellColor.Palette -> indexed(brighten(color.index.toInt() and 0xFF, bold))
            is CellColor.Rgb -> rgb(color.r, color.g, color.b)
        }

    fun background(color: CellColor): Int =
        when (color) {
            is CellColor.Default -> background
            is CellColor.Palette -> indexed(color.index.toInt() and 0xFF)
            is CellColor.Rgb -> rgb(color.r, color.g, color.b)
        }

    /** ARGB for a 256-color index. */
    fun indexed(index: Int): Int =
        when {
            index < ANSI_COUNT -> ansi[index]
            index < GRAY_START -> cube(index - ANSI_COUNT)
            else -> gray(index - GRAY_START)
        }

    private fun brighten(
        index: Int,
        bold: Boolean,
    ): Int = if (bold && index < BRIGHT_OFFSET) index + BRIGHT_OFFSET else index

    private fun cube(offset: Int): Int {
        fun level(step: Int) = if (step == 0) 0 else CUBE_BASE + step * CUBE_STEP
        return opaque(level(offset / 36), level(offset / 6 % 6), level(offset % 6))
    }

    private fun gray(offset: Int): Int {
        val level = GRAY_BASE + offset * GRAY_STEP
        return opaque(level, level, level)
    }

    companion object {
        private const val ANSI_COUNT = 16
        private const val BRIGHT_OFFSET = 8
        private const val GRAY_START = 232
        private const val CUBE_BASE = 55
        private const val CUBE_STEP = 40
        private const val GRAY_BASE = 8
        private const val GRAY_STEP = 10

        fun opaque(
            r: Int,
            g: Int,
            b: Int,
        ): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

        private fun rgb(
            r: UByte,
            g: UByte,
            b: UByte,
        ): Int = opaque(r.toInt(), g.toInt(), b.toInt())

        /** Dark default theme. */
        val DARK =
            TerminalPalette(
                foreground = 0xFFCDD6F4.toInt(),
                background = 0xFF1E1E2E.toInt(),
                cursor = 0xFFF5E0DC.toInt(),
                ansi =
                    intArrayOf(
                        0xFF45475A.toInt(),
                        0xFFF38BA8.toInt(),
                        0xFFA6E3A1.toInt(),
                        0xFFF9E2AF.toInt(),
                        0xFF89B4FA.toInt(),
                        0xFFF5C2E7.toInt(),
                        0xFF94E2D5.toInt(),
                        0xFFBAC2DE.toInt(),
                        0xFF585B70.toInt(),
                        0xFFF38BA8.toInt(),
                        0xFFA6E3A1.toInt(),
                        0xFFF9E2AF.toInt(),
                        0xFF89B4FA.toInt(),
                        0xFFF5C2E7.toInt(),
                        0xFF94E2D5.toInt(),
                        0xFFA6ADC8.toInt(),
                    ),
            )
    }
}
