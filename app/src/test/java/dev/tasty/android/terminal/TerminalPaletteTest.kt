package dev.tasty.android.terminal

import dev.tasty.android.core.CellColor
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalPaletteTest {
    private val palette = TerminalPalette.DARK

    @Test
    fun defaultColorsUseTheTheme() {
        assertEquals(palette.foreground, palette.foreground(CellColor.Default, bold = false))
        assertEquals(palette.background, palette.background(CellColor.Default))
    }

    @Test
    fun boldUsesTheBrightVariantOfTheFirstEightColors() {
        val red = CellColor.Palette(1u)
        assertEquals(palette.indexed(1), palette.foreground(red, bold = false))
        assertEquals(palette.indexed(9), palette.foreground(red, bold = true))
    }

    @Test
    fun cubeAndGrayRampFollowXterm() {
        assertEquals(TerminalPalette.opaque(0, 0, 0), palette.indexed(16))
        assertEquals(TerminalPalette.opaque(255, 255, 255), palette.indexed(231))
        assertEquals(TerminalPalette.opaque(95, 135, 175), palette.indexed(67))
        assertEquals(TerminalPalette.opaque(8, 8, 8), palette.indexed(232))
        assertEquals(TerminalPalette.opaque(238, 238, 238), palette.indexed(255))
    }

    @Test
    fun trueColorPassesThrough() {
        assertEquals(TerminalPalette.opaque(10, 20, 30), palette.background(CellColor.Rgb(10u, 20u, 30u)))
    }
}
