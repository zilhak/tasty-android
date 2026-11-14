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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.tasty.android.core.LayoutNode
import dev.tasty.android.core.SplitOrientation
import dev.tasty.android.core.StoreSnapshot
import dev.tasty.android.core.TabInfo
import dev.tasty.android.terminal.TerminalStatus
import dev.tasty.android.terminal.TerminalView

/** The main screen: a tab bar with the Tasty menu on the right, over the active workspace. */
@Composable
fun WorkspaceScreen(
    modifier: Modifier = Modifier,
    model: WorkspaceViewModel = viewModel(),
) {
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val statuses by model.statuses.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopBar(snapshot, statuses, model) },
    ) { innerPadding ->
        Box(
            Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .fillMaxSize(),
        ) {
            key(snapshot.layout.workspaceId) {
                LayoutNodeView(snapshot.layout.root.toLong(), snapshot, model)
            }
        }
    }
}

@Composable
private fun TopBar(
    snapshot: StoreSnapshot,
    statuses: Map<Long, TerminalStatus>,
    model: WorkspaceViewModel,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(BAR_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabBar(snapshot, statuses, model, Modifier.weight(1f))
            TastyMenu(snapshot, model)
        }
    }
}

/** Tabs of the focused pane, then a button that adds a tab to it. */
@Composable
private fun TabBar(
    snapshot: StoreSnapshot,
    statuses: Map<Long, TerminalStatus>,
    model: WorkspaceViewModel,
    modifier: Modifier = Modifier,
) {
    val pane = snapshot.focusedPane() ?: return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pane.tabs.forEachIndexed { index, tab ->
            TabItem(
                title = tabTitle(tab, index, statuses),
                selected = tab.id == pane.activeTab,
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun TabItem(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier =
            Modifier
                .fillMaxHeight()
                .background(if (selected) colors.surfaceContainerHighest else colors.surfaceContainer)
                .clickable(onClick = onSelect)
                .padding(start = 12.dp, end = if (selected) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
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

/** The Tasty logo button. Its menu switches workspaces and changes the pane layout. */
@Composable
private fun TastyMenu(
    snapshot: StoreSnapshot,
    model: WorkspaceViewModel,
) {
    var expanded by remember { mutableStateOf(false) }
    val focusedPane = snapshot.layout.focusedPane.toLong()
    Box {
        IconButton(onClick = { expanded = true }) {
            Image(
                painter = painterResource(R.drawable.tasty_logo),
                contentDescription = stringResource(R.string.menu_open),
                modifier = Modifier.size(LOGO_SIZE),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            fun run(action: () -> Unit): () -> Unit =
                {
                    expanded = false
                    action()
                }
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
                    onClick = run { model.selectWorkspace(workspace.id.toLong()) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_new)) },
                onClick = run { model.newWorkspace() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pane_split_right)) },
                onClick = run { model.splitPane(focusedPane, SplitOrientation.SIDE_BY_SIDE) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pane_split_down)) },
                onClick = run { model.splitPane(focusedPane, SplitOrientation.STACKED) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pane_close)) },
                onClick = run { model.closePane(focusedPane) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_close)) },
                onClick = run { model.closeWorkspace(snapshot.activeWorkspace.toLong()) },
            )
        }
    }
}

/** Draws one node of the pane tree: a split with a draggable divider, or a pane. */
@Composable
private fun LayoutNodeView(
    nodeId: Long,
    snapshot: StoreSnapshot,
    model: WorkspaceViewModel,
    modifier: Modifier = Modifier,
) {
    when (val node = snapshot.layout.nodes.firstOrNull { it.nodeId() == nodeId }) {
        is LayoutNode.Split -> SplitView(node, snapshot, model, modifier)
        is LayoutNode.Pane -> PaneView(node, snapshot, model, modifier)
        null -> Unit
    }
}

@Composable
private fun SplitView(
    split: LayoutNode.Split,
    snapshot: StoreSnapshot,
    model: WorkspaceViewModel,
    modifier: Modifier,
) {
    var size by remember { mutableIntStateOf(1) }
    val currentRatio by rememberUpdatedState(split.ratio)
    val sideBySide = split.orientation == SplitOrientation.SIDE_BY_SIDE
    val dragState =
        rememberDraggableState { delta ->
            model.setSplitRatio(split.id.toLong(), currentRatio + delta / size)
        }
    val divider =
        Modifier
            .background(MaterialTheme.colorScheme.outlineVariant)
            .draggable(dragState, if (sideBySide) Orientation.Horizontal else Orientation.Vertical)
    val first = split.first.toLong()
    val second = split.second.toLong()
    if (sideBySide) {
        Row(modifier.fillMaxSize().onSizeChanged { size = it.width.coerceAtLeast(1) }) {
            LayoutNodeView(first, snapshot, model, Modifier.weight(split.ratio))
            Box(divider.fillMaxHeight().width(DIVIDER))
            LayoutNodeView(second, snapshot, model, Modifier.weight(1f - split.ratio))
        }
    } else {
        Column(modifier.fillMaxSize().onSizeChanged { size = it.height.coerceAtLeast(1) }) {
            LayoutNodeView(first, snapshot, model, Modifier.weight(split.ratio))
            Box(divider.fillMaxWidth().height(DIVIDER))
            LayoutNodeView(second, snapshot, model, Modifier.weight(1f - split.ratio))
        }
    }
}

@Composable
private fun PaneView(
    pane: LayoutNode.Pane,
    snapshot: StoreSnapshot,
    model: WorkspaceViewModel,
    modifier: Modifier,
) {
    val paneId = pane.id.toLong()
    val focused = snapshot.layout.focusedPane == pane.id
    val showFocus = focused && snapshot.layout.nodes.size > 1
    val surfaceId =
        pane.tabs
            .firstOrNull { it.id == pane.activeTab }
            ?.surfaceId
            ?.toLong() ?: return
    Box(
        modifier
            .fillMaxSize()
            .border(1.dp, if (showFocus) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface),
    ) {
        key(surfaceId) {
            AndroidView(
                factory = { context -> TerminalView(context, model.session(surfaceId)) },
                update = { view ->
                    view.onTap = { model.focusPane(paneId) }
                    view.active = focused
                },
                modifier = Modifier.fillMaxSize().padding(1.dp),
            )
        }
    }
}

private fun tabTitle(
    tab: TabInfo,
    index: Int,
    statuses: Map<Long, TerminalStatus>,
): String = statuses[tab.surfaceId.toLong()]?.title?.takeIf { it.isNotBlank() } ?: "${tab.title} ${index + 1}"

private fun StoreSnapshot.focusedPane(): LayoutNode.Pane? =
    layout.nodes.filterIsInstance<LayoutNode.Pane>().firstOrNull { it.id == layout.focusedPane }

private fun LayoutNode.nodeId(): Long =
    when (this) {
        is LayoutNode.Pane -> id.toLong()
        is LayoutNode.Split -> id.toLong()
    }

private val BAR_HEIGHT = 48.dp
private val TAB_TEXT_WIDTH = 96.dp
private val LOGO_SIZE = 28.dp
private val DIVIDER = 4.dp
