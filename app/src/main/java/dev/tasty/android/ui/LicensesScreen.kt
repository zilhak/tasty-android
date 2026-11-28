package dev.tasty.android.ui

import android.content.res.AssetManager
import androidx.annotation.WorkerThread
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import dev.tasty.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.tasty.android.ui.TastyTokens as T

/**
 * The open source licenses page: the notice file that the build puts in the APK assets, shown as it
 * is in a fixed-width font. Each line of the file is one item of a lazy list, and a line wider than
 * the page wraps. Going back closes it.
 */
@Composable
fun LicensesScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val assets = LocalContext.current.assets
    val lines by produceState(emptyList<String>(), assets) {
        value = withContext(Dispatchers.IO) { Notice.read(assets) }
    }
    PageFrame(stringResource(R.string.open_source_licenses), onClose, modifier) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = T.androidPopupPaddingX)) {
            items(lines.size) { index ->
                Text(
                    text = lines[index],
                    color = T.textPrimary,
                    fontSize = T.fontSizeCaption,
                    fontFamily = FontFamily.Monospace,
                    softWrap = true,
                )
            }
        }
    }
}

/** The license notice asset (THIRD_PARTY_NOTICES.txt at the repo root, U10). */
object Notice {
    const val ASSET = "THIRD_PARTY_NOTICES.txt"

    /** Reads the asset as UTF-8 and splits it into lines. */
    @WorkerThread
    fun read(assets: AssetManager): List<String> =
        assets.open(ASSET).use { lines(it.readBytes().toString(Charsets.UTF_8)) }

    /**
     * The lines of [text], split at LF. A final LF ends the last line and does not start an empty
     * one. The file is written with LF only, so CR is not treated as a line break.
     */
    fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n')
        return if (text.endsWith('\n')) lines.dropLast(1) else lines
    }
}
