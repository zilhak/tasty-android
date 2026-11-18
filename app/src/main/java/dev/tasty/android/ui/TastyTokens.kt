package dev.tasty.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design token values from the Tasty design canon (tasty-page `vendor/tokens/tasty.tokens.json`),
 * Catppuccin Mocha theme. Each name is the canon token without its `--tasty-` prefix. Lengths read
 * as dp and type sizes as sp, one to one.
 */
object TastyTokens {
    // Colors.
    val tabBg = Color(0xFF181825)
    val tabFg = Color(0xFFA6ADC8)

    /** `tab-bg-hover`: bg-panel 60% mixed with bg-sidebar. */
    val tabBgHover = Color(0xFF1C1C2A)
    val tabFgHover = Color(0xFFBAC2DE)
    val tabBgActive = Color(0xFF1E1E2E)
    val tabFgActive = Color(0xFFCDD6F4)
    val tabIndicator = Color(0xFF89B4FA)
    val tabSeparator = Color(0x14FFFFFF)
    val separator = Color(0x14FFFFFF)
    val iconButtonFg = Color(0xFFBAC2DE)
    val overlayActive = Color(0x1FFFFFFF)
    val surfaceRaised = Color(0xFF313244)
    val bgSidebar = Color(0xFF181825)
    val bgPanel = Color(0xFF1E1E2E)
    val bgApp = Color(0xFF11111B)
    val scrimBg = Color(0x80000000)
    val borderFrame = Color(0xFF585B70)
    val menuSeparator = Color(0xFF45475A)
    val textPrimary = Color(0xFFCDD6F4)
    val textSecondary = Color(0xFFBAC2DE)
    val textMuted = Color(0xFFA6ADC8)
    val textDisabled = Color(0xFF7F849C)
    val accentPrimary = Color(0xFF89B4FA)
    val inputBg = Color(0xFF313244)
    val inputFg = Color(0xFFCDD6F4)
    val inputBorder = Color(0xFF6C7086)
    val inputBorderFocus = Color(0xFF89B4FA)
    val inputPlaceholder = Color(0xFFA6ADC8)
    val selectionBg = Color(0xFF585B70)
    val buttonPrimaryBg = Color(0xFF89B4FA)
    val buttonPrimaryFg = Color(0xFF11111B)
    val stateDisabledFill = Color(0xFF313244)
    val stateDisabledFg = Color(0xFF7F849C)
    val stateDisabledBorder = Color(0xFF313244)
    val borderFocus = Color(0xFF89B4FA)

    // Shape and type.
    val borderWidth = 1.dp
    val radius = 4.dp
    val radiusSm = 2.dp
    val spaceSm = 8.dp
    val inputPaddingX = 12.dp
    val inputRadius = 4.dp
    val focusRingWidth = 2.dp
    val iconSizeMd = 16.dp
    val fontSizeCaption = 11.sp
    val fontSizeBody = 13.sp
    val fontWeightSemibold = FontWeight.SemiBold

    // Tab bar.
    val tabPaddingX = 8.dp
    val tabIndicatorWidth = 2.dp
    val androidTabHeight = 32.dp
    val androidTabMinWidth = 96.dp
    val androidTabMaxWidth = 240.dp
    val androidTabButtonWidth = 40.dp
    val androidTabButtonReach = 8.dp
    val androidTabLogoSize = 20.dp

    // List popup.
    val androidPopupMaxWidth = 320.dp
    val androidPopupMargin = 16.dp
    val androidPopupTitleHeight = 48.dp
    val androidPopupRowHeight = 48.dp
    val androidPopupPaddingX = 16.dp
    val androidPopupFontSize = 14.sp
    val androidPopupLaneWidth = 24.dp
    val androidPopupLaneGlyph = 16.dp
    val androidPopupThumbWidth = 4.dp
}
