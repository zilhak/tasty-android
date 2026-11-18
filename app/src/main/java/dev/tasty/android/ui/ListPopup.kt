package dev.tasty.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import dev.tasty.android.ui.TastyTokens as T

/** One item of a [ListPopup]. Every item is one row unit tall. */
sealed interface PopupItem {
    /** Starts a group: a rule is drawn on the item's top edge, inside it. */
    val rule: Boolean

    /** A group heading such as Workspaces. Not a target. */
    data class Heading(
        val label: String,
        override val rule: Boolean = false,
    ) : PopupItem

    /**
     * An action. A disabled row stays in place and is not tappable, so rows do not move between
     * openings. [current] marks the one row already in effect, such as the workspace on screen.
     */
    data class Action(
        val label: String,
        val enabled: Boolean = true,
        val current: Boolean = false,
        override val rule: Boolean = false,
        val onClick: () -> Unit,
    ) : PopupItem
}

/**
 * The Android list popup: a list in the middle of the window over a scrim. Every Android menu uses
 * it, whether opened by a long press or by a button. It fills the area it is placed in, which ends
 * above an open on-screen keyboard, so the keyboard stays where it is.
 *
 * The list is a whole number of rows tall and scrolling rests on row edges, so a row is never shown
 * in part. While rows are out of view a scroll lane stands at the right. Tapping the scrim or going
 * back closes the popup without a choice; choosing a row runs it and closes the popup.
 */
@Composable
fun ListPopup(
    title: String,
    items: List<PopupItem>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PopupFrame(title, onDismiss, modifier) { room ->
        val listRoom = room - T.androidPopupTitleHeight - T.borderWidth * 2
        val fitting = (listRoom / T.androidPopupRowHeight).toInt()
        val rows = fitting.coerceIn(1, items.size.coerceAtLeast(1))
        PopupList(items, rows, onDismiss)
    }
}

/**
 * The frame every Android popup shares: a scrim over the area it is placed in, and in its middle a
 * panel with a title row. [content] goes under the title and gets the height the panel may take.
 * Tapping the scrim or going back calls [onDismiss].
 */
@Composable
internal fun PopupFrame(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (room: Dp) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(T.scrimBg)
            .clickable(interactionSource = null, indication = null, onClick = onDismiss)
            .padding(T.androidPopupMargin),
        contentAlignment = Alignment.Center,
    ) {
        val room = maxHeight
        Column(
            Modifier
                .width(min(maxWidth, T.androidPopupMaxWidth))
                .shadow(16.dp, RoundedCornerShape(T.radius))
                .background(T.bgPanel, RoundedCornerShape(T.radius))
                .border(T.borderWidth, T.borderFrame, RoundedCornerShape(T.radius))
                .padding(T.borderWidth)
                // Taps inside the popup must not reach the scrim.
                .clickable(interactionSource = null, indication = null, onClick = {}),
        ) {
            PopupTitle(title)
            content(room)
        }
    }
}

@Composable
private fun PopupTitle(title: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(T.androidPopupTitleHeight)
            .bottomRule(T.menuSeparator)
            .padding(horizontal = T.androidPopupPaddingX),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = title,
            color = T.textPrimary,
            fontSize = T.androidPopupFontSize,
            fontWeight = T.fontWeightSemibold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PopupList(
    items: List<PopupItem>,
    rows: Int,
    onDismiss: () -> Unit,
) {
    val state = rememberLazyListState()
    val fling = rememberSnapFlingBehavior(state, SnapPosition.Start)
    val scrolls = items.size > rows
    Row(Modifier.fillMaxWidth().height(T.androidPopupRowHeight * rows)) {
        LazyColumn(
            state = state,
            flingBehavior = fling,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            items(items.size) { index ->
                when (val item = items[index]) {
                    is PopupItem.Heading -> PopupHeading(item)
                    is PopupItem.Action -> PopupRow(item, onDismiss)
                }
            }
        }
        if (scrolls) {
            val above by remember { derivedStateOf { state.canScrollBackward } }
            val below by remember { derivedStateOf { state.canScrollForward } }
            val at by remember {
                derivedStateOf {
                    val max = (items.size - rows).coerceAtLeast(1)
                    (state.firstVisibleItemIndex.toFloat() / max).coerceIn(0f, 1f)
                }
            }
            ScrollLane(above, below, share = rows.toFloat() / items.size, at = at)
        }
    }
}

@Composable
private fun PopupHeading(item: PopupItem.Heading) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(T.androidPopupRowHeight)
            .topRule(item.rule)
            .padding(start = T.androidPopupPaddingX, end = T.androidPopupPaddingX, bottom = T.spaceSm),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            text = item.label,
            color = T.textSecondary,
            fontSize = T.fontSizeCaption,
            fontWeight = T.fontWeightSemibold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PopupRow(
    item: PopupItem.Action,
    onDismiss: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(T.androidPopupRowHeight)
            .background(if (pressed && item.enabled) T.overlayActive else T.bgPanel)
            // After the background, so the rule draws over it.
            .topRule(item.rule)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = item.enabled,
                role = Role.Button,
            ) {
                onDismiss()
                item.onClick()
            }.padding(horizontal = T.androidPopupPaddingX),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(T.spaceSm),
    ) {
        Text(
            text = item.label,
            color = if (item.enabled) T.textPrimary else T.textDisabled,
            fontSize = T.androidPopupFontSize,
            fontWeight = if (item.current) T.fontWeightSemibold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (item.current) {
            Icon(
                TastyIcons.check,
                contentDescription = null,
                tint = T.accentPrimary,
                modifier = Modifier.size(T.iconSizeMd),
            )
        }
    }
}

/**
 * The lane at the right of a list that scrolls. A chevron at each end is lit when rows lie that way
 * and dimmed at that end; the thumb shows the share in view and the place. Not a target.
 */
@Composable
private fun ScrollLane(
    above: Boolean,
    below: Boolean,
    share: Float,
    at: Float,
) {
    Column(Modifier.width(T.androidPopupLaneWidth).fillMaxHeight().background(T.surfaceRaised)) {
        LaneEnd(TastyIcons.chevronUp, lit = above)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val thumb: Dp = maxHeight * share
            Box(
                Modifier
                    .offset(y = (maxHeight - thumb) * at)
                    .width(T.androidPopupThumbWidth)
                    .height(thumb)
                    .background(T.textMuted, RoundedCornerShape(T.radiusSm)),
            )
        }
        LaneEnd(TastyIcons.chevronDown, lit = below)
    }
}

@Composable
private fun LaneEnd(
    icon: ImageVector,
    lit: Boolean,
) {
    Box(Modifier.fillMaxWidth().height(T.androidPopupLaneWidth), contentAlignment = Alignment.Center) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (lit) T.textSecondary else T.textDisabled,
            modifier = Modifier.size(T.androidPopupLaneGlyph),
        )
    }
}

private fun Modifier.topRule(show: Boolean): Modifier =
    if (!show) {
        this
    } else {
        drawBehind {
            val y = T.borderWidth.toPx() / 2
            drawLine(T.menuSeparator, Offset(0f, y), Offset(size.width, y), T.borderWidth.toPx())
        }
    }

private fun Modifier.bottomRule(color: androidx.compose.ui.graphics.Color): Modifier =
    drawBehind {
        val y = size.height - T.borderWidth.toPx() / 2
        drawLine(color, Offset(0f, y), Offset(size.width, y), T.borderWidth.toPx())
    }
