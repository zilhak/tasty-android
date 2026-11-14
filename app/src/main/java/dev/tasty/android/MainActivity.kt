package dev.tasty.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tasty.android.terminal.TerminalStatus
import dev.tasty.android.terminal.TerminalView
import dev.tasty.android.terminal.TerminalViewModel
import dev.tasty.android.ui.theme.TastyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TastyTheme(darkTheme = true) {
                TerminalScreen()
            }
        }
    }
}

/** The first screen: a top bar over a local shell. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    modifier: Modifier = Modifier,
    model: TerminalViewModel = viewModel(),
) {
    val status by model.status.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { TopBarTitle(status) })
        },
    ) { innerPadding ->
        AndroidView(
            factory = { context -> TerminalView(context, model) },
            modifier =
                Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding()
                    .fillMaxSize(),
        )
    }
}

@Composable
private fun TopBarTitle(status: TerminalStatus) {
    val subtitle =
        when {
            status.error != null -> stringResource(R.string.terminal_start_failed, status.error)
            status.exited -> stringResource(R.string.terminal_exited)
            else -> status.title
        }
    Column {
        Text(text = AppInfo.NAME, maxLines = 1)
        if (!subtitle.isNullOrEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
