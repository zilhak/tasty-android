package dev.tasty.android.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tasty.android.core.PaneNode
import dev.tasty.android.core.SplitOrientation
import dev.tasty.android.core.StoreSnapshot
import dev.tasty.android.core.SurfaceNode
import dev.tasty.android.core.TabInfo
import dev.tasty.android.terminal.TerminalStatus
import dev.tasty.android.terminal.TerminalView
import dev.tasty.android.ui.InputPopup
import dev.tasty.android.ui.ListPopup
import dev.tasty.android.ui.PopupItem
import dev.tasty.android.ui.TastyTokens

/**
 * The main screen: the active workspace with the desktop's two layout levels. Panes are placed
 * by the upper layout and each has its own tab bar; each tab places its surfaces with the lower
 * layout. The Tasty logo sits at the end of the tab bar of the pane in the top right corner.
 *
 * Menus open as a [ListPopup] over the whole screen area. That area ends above an open on-screen
 * keyboard, so the keyboard stays open and the popup sits above it.
 */
@Composable
fun WorkspaceScreen(
    modifier: Modifier = Modifier,
    model: WorkspaceViewModel = viewModel(),
) {
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val statuses by model.statuses.collectAsStateWithLifecycle()
    var popup by remember { mutableStateOf<Popup?>(null) }
    val labels = MenuLabels.load()
    val context = WorkspaceContext(snapshot, statuses, model, labels, popupOpen = popup != null) { popup = it }
    Surface(modifier = modifier.fillMaxSize(), color = TastyTokens.bgApp) {
        Box(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .navigationBarsPadding()
                .imePadding()
                .fillMaxSize(),
        ) {
            key(snapshot.layout.workspaceId) {
                PaneTree(snapshot.layout.root.toLong(), context)
            }
            when (val open = popup) {
                is Popup.Menu -> {
                    ListPopup(open.title, open.items, onDismiss = { popup = null })
                }

                is Popup.Input -> {
                    InputPopup(
                        title = open.title,
                        initial = open.initial,
                        placeholder = open.placeholder,
                        confirmLabel = open.confirmLabel,
                        cancelLabel = labels.cancel,
                        onConfirm = open.onConfirm,
                        onDismiss = { popup = null },
                    )
                }

                null -> {
                    Unit
                }
            }
        }
    }
}

/** A popup over the screen: a menu ([ListPopup]) or a short text entry ([InputPopup]). */
internal sealed interface Popup {
    class Menu(
        val title: String,
        val items: List<PopupItem>,
    ) : Popup

    class Input(
        val title: String,
        val initial: String,
        val placeholder: String,
        val confirmLabel: String,
        val onConfirm: (String) -> Unit,
    ) : Popup
}

/** What every part of the screen reads, passed down the tree as one value. */
internal class WorkspaceContext(
    val snapshot: StoreSnapshot,
    val statuses: Map<Long, TerminalStatus>,
    val model: WorkspaceViewModel,
    val labels: MenuLabels,
    /** While a popup is open the terminal leaves the focus to it. */
    val popupOpen: Boolean,
    val openPopup: (Popup) -> Unit,
) {
    val panes = snapshot.layout.nodes.associateBy { it.nodeId() }
    val logoPane = topRightPane(snapshot)
    val multiplePanes = snapshot.layout.nodes.size > 1
}

@Composable
private fun PaneTree(
    nodeId: Long,
    context: WorkspaceContext,
    modifier: Modifier = Modifier,
) {
    when (val node = context.panes[nodeId]) {
        is PaneNode.Split -> {
            SplitView(
                splitId = node.id.toLong(),
                orientation = node.orientation,
                ratio = node.ratio,
                model = context.model,
                modifier = modifier,
                first = { PaneTree(node.first.toLong(), context, it) },
                second = { PaneTree(node.second.toLong(), context, it) },
            )
        }

        is PaneNode.Pane -> {
            PaneView(node, context, modifier)
        }

        null -> {
            Unit
        }
    }
}

/**
 * A pane: its tab bar over the active tab's surfaces. The + and logo buttons are drawn over both,
 * because their targets reach below the bar into the pane's terminal.
 */
@Composable
private fun PaneView(
    pane: PaneNode.Pane,
    context: WorkspaceContext,
    modifier: Modifier,
) {
    val focused = context.snapshot.layout.focusedPane == pane.id
    val tab = pane.tabs.firstOrNull { it.id == pane.activeTab } ?: return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val bar =
            PaneTabBarLayout(
                paneWidth = maxWidth,
                tabCount = pane.tabs.size,
                logo =
                    pane.id.toLong() == context.logoPane,
            )
        Column(Modifier.fillMaxSize()) {
            PaneTabBar(pane, focused, bar, context)
            val nodes = tab.nodes.associateBy { it.nodeId() }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SurfaceTree(tab.root.toLong(), SurfaceTreeScope(nodes, tab, focused, context))
            }
        }
        PaneBarButtons(pane, bar, context)
    }
}

/** What the surface tree of one tab reads. */
private class SurfaceTreeScope(
    val nodes: Map<Long, SurfaceNode>,
    val tab: TabInfo,
    val paneFocused: Boolean,
    val context: WorkspaceContext,
)

@Composable
private fun SurfaceTree(
    nodeId: Long,
    scope: SurfaceTreeScope,
    modifier: Modifier = Modifier,
) {
    when (val node = scope.nodes[nodeId]) {
        is SurfaceNode.Split -> {
            SplitView(
                splitId = node.id.toLong(),
                orientation = node.orientation,
                ratio = node.ratio,
                model = scope.context.model,
                modifier = modifier,
                first = { SurfaceTree(node.first.toLong(), scope, it) },
                second = { SurfaceTree(node.second.toLong(), scope, it) },
            )
        }

        is SurfaceNode.Surface -> {
            SurfaceView(node.id.toLong(), scope, modifier)
        }

        null -> {
            Unit
        }
    }
}

@Composable
private fun SurfaceView(
    surfaceId: Long,
    scope: SurfaceTreeScope,
    modifier: Modifier,
) {
    val model = scope.context.model
    val focused = scope.paneFocused && scope.tab.focusedSurface.toLong() == surfaceId
    val showFocus = focused && (scope.context.multiplePanes || scope.nodes.size > 1)
    val border = if (showFocus) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    Box(modifier.fillMaxSize().border(1.dp, border)) {
        key(surfaceId) {
            AndroidView(
                factory = { ctx -> TerminalView(ctx, model.session(surfaceId)) },
                update = { view ->
                    view.onTap = { if (!focused) model.focusSurface(surfaceId) }
                    view.active = focused
                    // Take the focus back once a popup that held it closes.
                    if (focused && !scope.context.popupOpen && !view.hasFocus()) view.requestFocus()
                },
                modifier = Modifier.fillMaxSize().padding(1.dp),
            )
        }
    }
}

/** Two children with a draggable divider. Used by both layout levels. */
@Composable
private fun SplitView(
    splitId: Long,
    orientation: SplitOrientation,
    ratio: Float,
    model: WorkspaceViewModel,
    modifier: Modifier,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    var size by remember { mutableIntStateOf(1) }
    val currentRatio by rememberUpdatedState(ratio)
    val sideBySide = orientation == SplitOrientation.SIDE_BY_SIDE
    val dragState = rememberDraggableState { delta -> model.setSplitRatio(splitId, currentRatio + delta / size) }
    val divider =
        Modifier
            .background(MaterialTheme.colorScheme.outlineVariant)
            .draggable(dragState, if (sideBySide) Orientation.Horizontal else Orientation.Vertical)
    if (sideBySide) {
        Row(modifier.fillMaxSize().onSizeChanged { size = it.width.coerceAtLeast(1) }) {
            first(Modifier.weight(ratio))
            Box(divider.fillMaxHeight().width(DIVIDER))
            second(Modifier.weight(1f - ratio))
        }
    } else {
        Column(modifier.fillMaxSize().onSizeChanged { size = it.height.coerceAtLeast(1) }) {
            first(Modifier.weight(ratio))
            Box(divider.fillMaxWidth().height(DIVIDER))
            second(Modifier.weight(1f - ratio))
        }
    }
}

/** The name the user gave the tab, else the shell's title, else the tab's own "Terminal N". */
internal fun tabTitle(
    tab: TabInfo,
    statuses: Map<Long, TerminalStatus>,
): String =
    tab.customTitle
        ?: statuses[tab.focusedSurface.toLong()]?.title?.takeIf { it.isNotBlank() }
        ?: tab.title

/**
 * The pane whose tab bar reaches the top right corner: follow the right child of side-by-side
 * splits and the top child of stacked splits.
 */
private fun topRightPane(snapshot: StoreSnapshot): Long {
    val nodes = snapshot.layout.nodes.associateBy { it.nodeId() }
    var node = nodes[snapshot.layout.root.toLong()]
    while (node is PaneNode.Split) {
        val next = if (node.orientation == SplitOrientation.SIDE_BY_SIDE) node.second else node.first
        node = nodes[next.toLong()]
    }
    return node?.nodeId() ?: -1
}

internal fun StoreSnapshot.focusedPane(): PaneNode.Pane? =
    layout.nodes.filterIsInstance<PaneNode.Pane>().firstOrNull { it.id == layout.focusedPane }

private fun PaneNode.nodeId(): Long =
    when (this) {
        is PaneNode.Pane -> id.toLong()
        is PaneNode.Split -> id.toLong()
    }

private fun SurfaceNode.nodeId(): Long =
    when (this) {
        is SurfaceNode.Surface -> id.toLong()
        is SurfaceNode.Split -> id.toLong()
    }

private val DIVIDER = 4.dp
