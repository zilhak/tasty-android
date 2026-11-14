package dev.tasty.android.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.tasty.android.core.EnvVar
import dev.tasty.android.core.OutputListener
import dev.tasty.android.core.ShellConfig
import dev.tasty.android.core.TerminalException
import dev.tasty.android.core.TerminalSession
import java.util.concurrent.atomic.AtomicBoolean

/** Shell state shown outside the terminal grid. */
data class TerminalStatus(
    val title: String? = null,
    val exited: Boolean = false,
    val error: String? = null,
)

/**
 * The local shell behind one surface. The shell starts on the first [ensureStarted] call,
 * once a view knows the grid size, and lives until [close].
 */
class SurfaceSession(
    private val context: Context,
    val surfaceId: Long,
    private val onStatusChanged: (SurfaceSession) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val updatePending = AtomicBoolean(false)
    private val listeners = mutableSetOf<() -> Unit>()

    var status = TerminalStatus()
        private set

    var session: TerminalSession? = null
        private set

    /** Starts the shell with the given grid size, or resizes the running one. */
    fun ensureStarted(
        cols: Int,
        rows: Int,
    ) {
        session?.let {
            it.resize(cols.toUInt(), rows.toUInt())
            return
        }
        if (status.error != null) return
        session =
            try {
                TerminalSession.start(shellConfig(cols, rows), Listener())
            } catch (e: TerminalException) {
                updateStatus(TerminalStatus(error = (e as? TerminalException.SpawnFailed)?.reason ?: e.message))
                null
            }
        notifyListeners()
    }

    /** Starts a new shell after the previous one exited or failed to start. */
    fun restart(
        cols: Int,
        rows: Int,
    ) {
        session?.close()
        session = null
        updateStatus(TerminalStatus())
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
        if (!status.exited) updateStatus(status.copy(exited = true))
    }

    fun close() {
        session?.close()
        session = null
        listeners.clear()
    }

    private fun updateStatus(next: TerminalStatus) {
        if (next == status) return
        status = next
        onStatusChanged(this)
    }

    private fun shellConfig(
        cols: Int,
        rows: Int,
    ): ShellConfig {
        val home = context.filesDir.absolutePath
        val env =
            listOf(
                EnvVar("HOME", home),
                EnvVar("TMPDIR", context.cacheDir.absolutePath),
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
        if (title != status.title) updateStatus(status.copy(title = title))
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
