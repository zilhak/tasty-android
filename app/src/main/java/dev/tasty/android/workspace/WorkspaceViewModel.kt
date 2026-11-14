package dev.tasty.android.workspace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.tasty.android.core.SplitOrientation
import dev.tasty.android.core.StoreSnapshot
import dev.tasty.android.core.WorkspaceStore
import dev.tasty.android.terminal.SurfaceSession
import dev.tasty.android.terminal.TerminalStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the workspaces and one shell per surface so both survive configuration changes.
 *
 * The Rust [WorkspaceStore] owns the structure. After each change this model publishes a new
 * snapshot and closes the sessions of surfaces that no longer exist.
 */
class WorkspaceViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val store = WorkspaceStore()
    private val sessions = mutableMapOf<Long, SurfaceSession>()
    private val _snapshot = MutableStateFlow(store.snapshot())
    private val _statuses = MutableStateFlow<Map<Long, TerminalStatus>>(emptyMap())

    val snapshot: StateFlow<StoreSnapshot> = _snapshot.asStateFlow()

    /** Shell status by surface id, for tab titles. */
    val statuses: StateFlow<Map<Long, TerminalStatus>> = _statuses.asStateFlow()

    /** The session for a surface, created on first use. */
    fun session(surfaceId: Long): SurfaceSession =
        sessions.getOrPut(surfaceId) {
            SurfaceSession(getApplication(), surfaceId) { changed ->
                _statuses.value = _statuses.value + (changed.surfaceId to changed.status)
            }
        }

    fun selectWorkspace(workspaceId: Long) = update { store.selectWorkspace(workspaceId.toULong()) }

    fun newWorkspace() = update { store.newWorkspace() }

    fun closeWorkspace(workspaceId: Long) = update { store.closeWorkspace(workspaceId.toULong()) }

    fun newTab(paneId: Long) = update { store.newTab(paneId.toULong()) }

    fun selectTab(tabId: Long) = update { store.selectTab(tabId.toULong()) }

    fun closeTab(tabId: Long) = update { store.closeTab(tabId.toULong()) }

    fun splitPane(
        paneId: Long,
        orientation: SplitOrientation,
    ) = update { store.splitPane(paneId.toULong(), orientation) }

    fun closePane(paneId: Long) = update { store.closePane(paneId.toULong()) }

    fun splitSurface(
        surfaceId: Long,
        orientation: SplitOrientation,
    ) = update { store.splitSurface(surfaceId.toULong(), orientation) }

    fun closeSurface(surfaceId: Long) = update { store.closeSurface(surfaceId.toULong()) }

    /** Focuses a surface, which also selects its tab and focuses its pane. */
    fun focusSurface(surfaceId: Long) = update { store.focusSurface(surfaceId.toULong()) }

    fun setSplitRatio(
        splitId: Long,
        ratio: Float,
    ) = update { store.setSplitRatio(splitId.toULong(), ratio) }

    override fun onCleared() {
        sessions.values.forEach(SurfaceSession::close)
        sessions.clear()
        store.close()
    }

    private fun update(change: () -> Any?) {
        change()
        val next = store.snapshot()
        val live = next.surfaces.map(ULong::toLong).toSet()
        val gone = sessions.keys - live
        gone.forEach { sessions.remove(it)?.close() }
        if (gone.isNotEmpty()) _statuses.value = _statuses.value - gone
        _snapshot.value = next
    }
}
