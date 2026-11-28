package dev.tasty.android.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import dev.tasty.android.R
import dev.tasty.android.core.PaneNode
import dev.tasty.android.core.SplitOrientation
import dev.tasty.android.core.TabInfo
import dev.tasty.android.ui.PopupItem
import dev.tasty.android.ui.TastyIcons
import dev.tasty.android.ui.TastyTokens as T

/**
 * Where the parts of one pane's tab bar go. Tabs share the room the buttons leave, each between
 * the min and max tab width. + follows the last tab and stays at the end when the tabs overflow;
 * the logo, on the pane that reaches the top right of the screen, sits at the far right.
 */
class PaneTabBarLayout(
    paneWidth: Dp,
    tabCount: Int,
    val logo: Boolean,
) {
    private val buttons = T.androidTabButtonWidth * (if (logo) 2 else 1)
    private val room = max(paneWidth - buttons, Dp(0f))
    val tabWidth: Dp = (room / tabCount.coerceAtLeast(1)).coerceIn(T.androidTabMinWidth, T.androidTabMaxWidth)

    /** Width of the scrolling tab row: all tabs, or the room left when they overflow. */
    val tabRowWidth: Dp = min(tabWidth * tabCount, room)
    val plusX: Dp = tabRowWidth
    val logoX: Dp = paneWidth - T.androidTabButtonWidth
}

/** The bar: tabs only. The + and logo buttons are drawn over the pane by [PaneBarButtons]. */
@Composable
internal fun PaneTabBar(
    pane: PaneNode.Pane,
    focused: Boolean,
    layout: PaneTabBarLayout,
    context: WorkspaceContext,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(T.androidTabHeight + T.borderWidth)
            .background(if (focused) T.surfaceRaised else T.bgSidebar)
            .drawBehind {
                val y = size.height - T.borderWidth.toPx() / 2
                drawLine(T.separator, Offset(0f, y), Offset(size.width, y), T.borderWidth.toPx())
            },
    ) {
        Row(Modifier.width(layout.tabRowWidth).height(T.androidTabHeight).horizontalScroll(rememberScrollState())) {
            pane.tabs.forEachIndexed { index, tab ->
                PaneTab(
                    title = tabTitle(tab, context.statuses),
                    width = layout.tabWidth,
                    selected = tab.id == pane.activeTab,
                    paneFocused = focused,
                    onSelect = { context.model.selectTab(tab.id.toLong()) },
                    onMenu = { context.openPopup(tabMenu(context, pane, tab, index)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaneTab(
    title: String,
    width: Dp,
    selected: Boolean,
    paneFocused: Boolean,
    onSelect: () -> Unit,
    onMenu: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fg =
        when {
            selected && paneFocused -> T.tabFgActive
            selected -> T.textSecondary
            pressed -> T.tabFgHover
            else -> T.tabFg
        }
    val bg =
        when {
            selected -> T.tabBgActive
            pressed -> T.tabBgHover
            else -> T.tabBg
        }
    // Keeps the selected tab in view, for example right after adding it.
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) { if (selected) bringIntoView.bringIntoView() }
    Box(
        Modifier
            .bringIntoViewRequester(bringIntoView)
            .width(width)
            .height(T.androidTabHeight)
            .background(bg)
            .drawBehind {
                val stroke = T.borderWidth.toPx()
                drawLine(
                    T.tabSeparator,
                    Offset(size.width - stroke / 2, 0f),
                    Offset(size.width - stroke / 2, size.height),
                    stroke,
                )
                if (selected && paneFocused) {
                    val h = T.tabIndicatorWidth.toPx()
                    drawRect(T.tabIndicator, size = size.copy(height = h))
                }
            }.combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onLongClick = onMenu,
                onClick = onSelect,
            ).padding(horizontal = T.tabPaddingX),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = title, color = fg, fontSize = T.fontSizeBody, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The + and logo buttons, laid over the whole pane. Each is drawn at the bar height, and its target
 * reaches [T.androidTabButtonReach] further down into the pane's own terminal.
 */
@Composable
internal fun PaneBarButtons(
    pane: PaneNode.Pane,
    layout: PaneTabBarLayout,
    context: WorkspaceContext,
) {
    Box(Modifier.fillMaxSize()) {
        BarButton(
            x = layout.plusX,
            label = stringResource(R.string.tab_new),
            onClick = { context.model.newTab(pane.id.toLong()) },
        ) {
            Icon(
                TastyIcons.plus,
                contentDescription = null,
                tint = T.iconButtonFg,
                modifier = Modifier.size(T.iconSizeMd),
            )
        }
        if (layout.logo) {
            val title = stringResource(R.string.app_name)
            BarButton(
                x = layout.logoX,
                label = stringResource(R.string.menu_open),
                onClick = { context.openPopup(tastyMenu(context, title)) },
            ) {
                Image(
                    painterResource(R.drawable.tasty_logo),
                    contentDescription = null,
                    modifier = Modifier.size(T.androidTabLogoSize),
                )
            }
        }
    }
}

@Composable
private fun BarButton(
    x: Dp,
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .offset(x = x)
            .width(T.androidTabButtonWidth)
            .height(T.androidTabHeight + T.androidTabButtonReach)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(T.androidTabHeight)
                .background(if (pressed) T.overlayActive else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/** Menu labels read once in composition, so menus can be built in click handlers. */
internal class MenuLabels(
    val workspaces: String,
    val newWorkspace: String,
    val closeWorkspace: String,
    val splitPaneRight: String,
    val splitPaneDown: String,
    val closePane: String,
    val splitSurfaceRight: String,
    val splitSurfaceDown: String,
    val closeSurface: String,
    val renameTab: String,
    val rename: String,
    val cancel: String,
    val tabName: String,
    val moveLeft: String,
    val moveRight: String,
    val closeTab: String,
    val about: String,
) {
    companion object {
        @Composable
        fun load(): MenuLabels {
            val res = LocalResources.current
            return remember(res) {
                MenuLabels(
                    workspaces = res.getString(R.string.menu_workspaces),
                    newWorkspace = res.getString(R.string.workspace_new),
                    closeWorkspace = res.getString(R.string.workspace_close),
                    splitPaneRight = res.getString(R.string.pane_split_right),
                    splitPaneDown = res.getString(R.string.pane_split_down),
                    closePane = res.getString(R.string.pane_close),
                    splitSurfaceRight = res.getString(R.string.surface_split_right),
                    splitSurfaceDown = res.getString(R.string.surface_split_down),
                    closeSurface = res.getString(R.string.surface_close),
                    renameTab = res.getString(R.string.tab_rename),
                    rename = res.getString(R.string.rename),
                    cancel = res.getString(R.string.cancel),
                    tabName = res.getString(R.string.tab_name),
                    moveLeft = res.getString(R.string.tab_move_left),
                    moveRight = res.getString(R.string.tab_move_right),
                    closeTab = res.getString(R.string.tab_close),
                    about = res.getString(R.string.about),
                )
            }
        }
    }
}

/**
 * The menu a long press on a tab opens. Rename opens an input popup; moves past either end stay
 * listed, disabled.
 */
private fun tabMenu(
    context: WorkspaceContext,
    pane: PaneNode.Pane,
    tab: TabInfo,
    index: Int,
): Popup {
    val labels = context.labels
    val model = context.model
    val id = tab.id.toLong()
    val title = tabTitle(tab, context.statuses)
    val rename =
        Popup.Input(
            title = labels.renameTab,
            initial = title,
            placeholder = labels.tabName,
            confirmLabel = labels.rename,
        ) { model.renameTab(id, it) }
    return Popup.Menu(
        title = title,
        items =
            listOf(
                PopupItem.Action(labels.renameTab) { context.openPopup(rename) },
                PopupItem.Action(labels.moveLeft, enabled = index > 0) { model.moveTab(id, -1) },
                PopupItem.Action(labels.moveRight, enabled = index < pane.tabs.lastIndex) { model.moveTab(id, 1) },
                PopupItem.Action(labels.closeTab, rule = true) { model.closeTab(id) },
            ),
    )
}

/** The menu the logo opens: workspaces, then the focused pane's and surface's actions, then About. */
private fun tastyMenu(
    context: WorkspaceContext,
    title: String,
): Popup {
    val labels = context.labels
    val snapshot = context.snapshot
    val model = context.model
    val focusedPane = snapshot.focusedPane()
    val focusedSurface =
        focusedPane
            ?.tabs
            ?.firstOrNull { it.id == focusedPane.activeTab }
            ?.focusedSurface
            ?.toLong()
    val items = mutableListOf<PopupItem>(PopupItem.Heading(labels.workspaces))
    snapshot.workspaces.forEach { workspace ->
        val id = workspace.id.toLong()
        items +=
            PopupItem.Action(
                workspace.name,
                current = workspace.id == snapshot.activeWorkspace,
            ) { model.selectWorkspace(id) }
    }
    items += PopupItem.Action(labels.newWorkspace) { model.newWorkspace() }
    if (focusedPane != null) {
        val paneId = focusedPane.id.toLong()
        items +=
            PopupItem.Action(
                labels.splitPaneRight,
                rule = true,
            ) { model.splitPane(paneId, SplitOrientation.SIDE_BY_SIDE) }
        items += PopupItem.Action(labels.splitPaneDown) { model.splitPane(paneId, SplitOrientation.STACKED) }
        items += PopupItem.Action(labels.closePane) { model.closePane(paneId) }
    }
    if (focusedSurface != null) {
        items +=
            PopupItem.Action(labels.splitSurfaceRight, rule = true) {
                model.splitSurface(focusedSurface, SplitOrientation.SIDE_BY_SIDE)
            }
        items +=
            PopupItem.Action(labels.splitSurfaceDown) { model.splitSurface(focusedSurface, SplitOrientation.STACKED) }
        items += PopupItem.Action(labels.closeSurface) { model.closeSurface(focusedSurface) }
    }
    val workspaceId = snapshot.activeWorkspace.toLong()
    items += PopupItem.Action(labels.closeWorkspace, rule = true) { model.closeWorkspace(workspaceId) }
    items += PopupItem.Action(labels.about, rule = true) { context.openPage(Page.About) }
    return Popup.Menu(title, items)
}
