package dev.tasty.android.terminal

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import dev.tasty.android.core.EnvVar
import dev.tasty.android.core.OutputListener
import dev.tasty.android.core.ShellConfig
import dev.tasty.android.core.TerminalException
import dev.tasty.android.core.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Shell state shown outside the terminal grid. */
data class TerminalStatus(
    val title: String? = null,
    val exited: Boolean = false,
    val error: String? = null,
)

/**
 * Owns the local shell so it survives configuration changes such as rotation.
 *
 * The shell starts on the first [ensureStarted] call, once the view knows its grid size.
 */
class TerminalViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val updatePending = AtomicBoolean(false)
    private val listeners = mutableSetOf<() -> Unit>()
    private val _status = MutableStateFlow(TerminalStatus())

    val status: StateFlow<TerminalStatus> = _status.asStateFlow()

    var session: TerminalSession? = null
        private set

    /** Starts the shell with the given grid size unless it is already running. */
    fun ensureStarted(
        cols: Int,
        rows: Int,
    ) {
        if (session != null) {
            session?.resize(cols.toUInt(), rows.toUInt())
            return
        }
        session =
            try {
                TerminalSession.start(shellConfig(cols, rows), Listener())
            } catch (e: TerminalException) {
                _status.value = TerminalStatus(error = (e as? TerminalException.SpawnFailed)?.reason ?: e.message)
                null
            }
        if (session != null) _status.value = TerminalStatus()
        notifyListeners()
    }

    /** Starts a new shell after the previous one exited. */
    fun restart(
        cols: Int,
        rows: Int,
    ) {
        closeSession()
        ensureStarted(cols, rows)
    }

    /** Registers a callback run on the main thread after new output arrives. */
    fun addOutputListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeOutputListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** Called by the view after drawing a snapshot that shows the shell has exited. */
    fun markExited() {
        if (!_status.value.exited) _status.value = _status.value.copy(exited = true)
    }

    override fun onCleared() {
        closeSession()
        listeners.clear()
    }

    private fun closeSession() {
        session?.close()
        session = null
    }

    private fun shellConfig(
        cols: Int,
        rows: Int,
    ): ShellConfig {
        val app = getApplication<Application>()
        val home = app.filesDir.absolutePath
        val env =
            listOf(
                EnvVar("HOME", home),
                EnvVar("TMPDIR", app.cacheDir.absolutePath),
                EnvVar("COLORTERM", "truecolor"),
            )
        return ShellConfig(
            shell = SHELL,
            args = emptyList(),
            workingDir = home,
            env = env,
            cols = cols.toUInt(),
            rows = rows.toUInt(),
        )
    }

    private fun notifyListeners() {
        val title = session?.title()
        if (title != _status.value.title) _status.value = _status.value.copy(title = title)
        listeners.toList().forEach { it() }
    }

    /** Coalesces reader-thread wakeups into one main-thread update. */
    private inner class Listener : OutputListener {
        override fun onOutput() {
            if (updatePending.compareAndSet(false, true)) {
                mainHandler.post {
                    updatePending.set(false)
                    notifyListeners()
                }
            }
        }
    }

    private companion object {
        const val SHELL = "/system/bin/sh"
    }
}
