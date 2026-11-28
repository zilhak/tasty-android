package dev.tasty.android.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.tasty.android.R
import dev.tasty.android.ui.TastyTokens as T

/**
 * The About page: the app name, its version and the way to the open source licenses. It covers the
 * area it is placed in; going back closes it.
 */
@Composable
fun AboutScreen(
    onOpenLicenses: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val version = remember(context) { versionName(context) }
    PageFrame(stringResource(R.string.about), onClose, modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = T.androidPopupPaddingX, vertical = T.spaceSm)) {
            Text(
                text = stringResource(R.string.app_name),
                color = T.textPrimary,
                fontSize = T.androidPopupFontSize,
                fontWeight = T.fontWeightSemibold,
            )
            Text(
                text = stringResource(R.string.about_version, version),
                color = T.textSecondary,
                fontSize = T.fontSizeBody,
            )
        }
        PageRow(stringResource(R.string.open_source_licenses), onOpenLicenses)
    }
}

/**
 * A full page over the area it is placed in: a title row and [content] under it. Taps do not reach
 * what lies under the page, and going back calls [onClose].
 */
@Composable
internal fun PageFrame(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onClose)
    Column(
        modifier
            .fillMaxSize()
            .background(T.bgApp)
            .clickable(interactionSource = null, indication = null, onClick = {}),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(T.androidPopupTitleHeight)
                .background(T.bgPanel)
                .drawBehind {
                    val y = size.height - T.borderWidth.toPx() / 2
                    drawLine(T.menuSeparator, Offset(0f, y), Offset(size.width, y), T.borderWidth.toPx())
                }.padding(horizontal = T.androidPopupPaddingX),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = title,
                color = T.textPrimary,
                fontSize = T.androidPopupFontSize,
                fontWeight = T.fontWeightSemibold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        content()
    }
}

/** A tappable row of a page, one popup row tall, with a rule on its top edge. */
@Composable
private fun PageRow(
    label: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .height(T.androidPopupRowHeight)
            .background(if (pressed) T.overlayActive else T.bgApp)
            .drawBehind {
                val y = T.borderWidth.toPx() / 2
                drawLine(T.menuSeparator, Offset(0f, y), Offset(size.width, y), T.borderWidth.toPx())
            }.clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ).padding(horizontal = T.androidPopupPaddingX),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            color = T.textPrimary,
            fontSize = T.androidPopupFontSize,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The app's versionName from the package manager. */
private fun versionName(context: Context): String {
    val manager = context.packageManager
    val info =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(context.packageName, 0)
        }
    return info.versionName.orEmpty()
}
