package dev.tasty.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.tasty.android.ui.theme.TastyTheme
import dev.tasty.android.workspace.WorkspaceScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TastyTheme(darkTheme = true) {
                WorkspaceScreen()
            }
        }
    }
}
