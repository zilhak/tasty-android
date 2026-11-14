package dev.tasty.android.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tasty.android.R
import dev.tasty.android.core.PaneNode
import dev.tasty.android.core.SplitOrientation
import dev.tasty.android.core.StoreSnapshot
import dev.tasty.android.core.SurfaceNode
import dev.tasty.android.core.TabInfo
import dev.tasty.android.terminal.TerminalStatus
import dev.tasty.android.terminal.TerminalView

/**
 * The main screen: the active workspace with the desktop's two layout levels. Panes are placed
 * by the upper layout and each has its own tab bar; each tab places its surfaces with the lower
 * layout. The Tasty logo sits at the end of the tab bar of the pane in the top right corner.
 */
@Composable
fun WorkspaceScreen(
    modifier: Modifier = Modifier,
    model: WorkspaceViewModel = viewModel(),
) {
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val statuses by model.statuses.collectAsStateWithLifecycle()
    val context = WorkspaceContext(snapshot, statuses, model)
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
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
        }
    }
}

/** What every part of the screen reads, passed down the tree as one value. */
private class WorkspaceContext(
    val snapshot: StoreSnapshot,
    val statuses: Map<Long, TerminalStatus>,
    val model: WorkspaceViewModel,
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

@Composable
private fun PaneView(
    pane: PaneNode.Pane,
    context: WorkspaceContext,
    modifier: Modifier,
) {
    val focused = context.snapshot.layout.focusedPane == pane.id
    val tab = pane.tabs.firstOrNull { it.id == pane.activeTab } ?: return
    Column(modifier.fillMaxSize()) {
        PaneTabBar(pane, focused, showLogo = pane.id.toLong() == context.logoPane, context)
        val nodes = tab.nodes.associateBy { it.nodeId() }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SurfaceTree(tab.root.toLong(), SurfaceTreeScope(nodes, tab, focused, context))
        }
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

/** A pane's own tab bar: its tabs, a new-tab button, and the Tasty menu when [showLogo]. */
@Composable
private fun PaneTabBar(
    pane: PaneNode.Pane,
    paneFocused: Boolean,
    showLogo: Boolean,
    context: WorkspaceContext,
) {
    val model = context.model
    Row(
        modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pane.tabs.forEachIndexed { index, tab ->
                TabItem(
                    title = tabTitle(tab, index, context.statuses),
                    selected = tab.id == pane.activeTab,
                    paneFocused = paneFocused,
                    onSelect = { model.selectTab(tab.id.toLong()) },
                    onClose = { model.closeTab(tab.id.toLong()) },
                )
            }
            Text(
                text = "+",
                style = MaterialTheme.typography.titleLarge,
                modifier =
                    Modifier
                        .clickable(onClickLabel = stringResource(R.string.tab_new)) { model.newTab(pane.id.toLong()) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        if (showLogo) TastyMenu(context)
    }
}

@Composable
private fun TabItem(
    title: String,
    selected: Boolean,
    paneFocused: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val background =
        when {
            selected && paneFocused -> colors.surfaceContainerHighest
            selected -> colors.surfaceContainerHigh
            else -> colors.surfaceContainer
        }
    // Keeps the selected tab visible in a narrow pane, for example right after adding it.
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) { if (selected) bringIntoView.bringIntoView() }
    Row(
        modifier =
            Modifier
                .bringIntoViewRequester(bringIntoView)
                .fillMaxHeight()
                .background(background)
                .clickable(onClick = onSelect)
                .padding(start = 12.dp, end = if (selected) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected && paneFocused) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(TAB_TEXT_WIDTH),
        )
        if (selected) {
            Text(
                text = "×",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurfaceVariant,
                modifier =
                    Modifier
                        .clickable(onClickLabel = stringResource(R.string.tab_close), onClick = onClose)
                        .padding(8.dp),
            )
        }
    }
}

/** The Tasty logo button. Its menu switches workspaces and changes both layout levels. */
@Composable
private fun TastyMenu(context: WorkspaceContext) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Image(
                painter = painterResource(R.drawable.tasty_logo),
                contentDescription = stringResource(R.string.menu_open),
                modifier = Modifier.size(LOGO_SIZE),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TastyMenuItems(context) { action ->
                expanded = false
                action()
            }
        }
    }
}

@Composable
private fun TastyMenuItems(
    context: WorkspaceContext,
    run: (() -> Unit) -> Unit,
) {
    val snapshot = context.snapshot
    val model = context.model
    val focusedPane = snapshot.focusedPane()
    val focusedSurface =
        focusedPane
            ?.tabs
            ?.firstOrNull { it.id == focusedPane.activeTab }
            ?.focusedSurface
            ?.toLong()

    @Composable
    fun item(
        label: Int,
        action: () -> Unit,
    ) = DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { run(action) })

    Text(
        text = stringResource(R.string.menu_workspaces),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
    snapshot.workspaces.forEach { workspace ->
        val active = workspace.id == snapshot.activeWorkspace
        DropdownMenuItem(
            text = { Text(workspace.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Text(if (active) "●" else " ", color = MaterialTheme.colorScheme.primary) },
            onClick = { run { model.selectWorkspace(workspace.id.toLong()) } },
        )
    }
    item(R.string.workspace_new) { model.newWorkspace() }
    HorizontalDivider()
    if (focusedPane != null) {
        val paneId = focusedPane.id.toLong()
        item(R.string.pane_split_right) { model.splitPane(paneId, SplitOrientation.SIDE_BY_SIDE) }
        item(R.string.pane_split_down) { model.splitPane(paneId, SplitOrientation.STACKED) }
        item(R.string.pane_close) { model.closePane(paneId) }
        HorizontalDivider()
    }
    if (focusedSurface != null) {
        item(R.string.surface_split_right) { model.splitSurface(focusedSurface, SplitOrientation.SIDE_BY_SIDE) }
        item(R.string.surface_split_down) { model.splitSurface(focusedSurface, SplitOrientation.STACKED) }
        item(R.string.surface_close) { model.closeSurface(focusedSurface) }
        HorizontalDivider()
    }
    item(R.string.workspace_close) { model.closeWorkspace(snapshot.activeWorkspace.toLong()) }
}

private fun tabTitle(
    tab: TabInfo,
    index: Int,
    statuses: Map<Long, TerminalStatus>,
): String = statuses[tab.focusedSurface.toLong()]?.title?.takeIf { it.isNotBlank() } ?: "${tab.title} ${index + 1}"

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

private fun StoreSnapshot.focusedPane(): PaneNode.Pane? =
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

private val BAR_HEIGHT = 44.dp
private val TAB_TEXT_WIDTH = 88.dp
private val LOGO_SIZE = 26.dp
private val DIVIDER = 4.dp
