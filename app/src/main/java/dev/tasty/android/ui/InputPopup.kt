package dev.tasty.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import dev.tasty.android.ui.TastyTokens as T

/**
 * The Android input popup, for a short text entry such as a new tab name. It shares the list
 * popup's frame. The field takes focus with [initial] fully selected, so typing replaces it.
 * Rename, Enter or the keyboard's action key confirm; Cancel, Esc, back or the scrim cancel.
 * A blank entry leaves Rename disabled. The confirmed text is trimmed.
 */
@Composable
fun InputPopup(
    title: String,
    initial: String,
    placeholder: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val blank = value.text.isBlank()
    val confirm = {
        if (!blank) {
            onDismiss()
            onConfirm(value.text.trim())
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    PopupFrame(title, onDismiss, modifier) {
        Box(
            Modifier
                .padding(start = T.androidPopupPaddingX, end = T.androidPopupPaddingX, top = T.androidPopupPaddingX)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            confirm()
                            true
                        }

                        Key.Escape -> {
                            onDismiss()
                            true
                        }

                        else -> {
                            false
                        }
                    }
                },
        ) {
            PopupField(value, placeholder, focus, onChange = { value = it }, onDone = confirm)
        }
        Row(
            Modifier.fillMaxWidth().padding(T.androidPopupPaddingX),
            horizontalArrangement = Arrangement.spacedBy(T.spaceSm, Alignment.End),
        ) {
            PopupButton(cancelLabel, primary = false, enabled = true, onClick = onDismiss)
            PopupButton(confirmLabel, primary = true, enabled = !blank, onClick = confirm)
        }
    }
}

@Composable
private fun PopupField(
    value: TextFieldValue,
    placeholder: String,
    focus: FocusRequester,
    onChange: (TextFieldValue) -> Unit,
    onDone: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(T.inputRadius)
    CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(T.inputBorderFocus, T.selectionBg)) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            interactionSource = interaction,
            textStyle = TextStyle(color = T.inputFg, fontSize = T.androidPopupFontSize),
            cursorBrush = SolidColor(T.textPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            decorationBox = { field ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(T.androidPopupRowHeight)
                        .background(T.inputBg, shape)
                        // Focused: the border and a ring of the same color just outside it.
                        .border(
                            if (focused) T.borderWidth * 2 else T.borderWidth,
                            if (focused) T.inputBorderFocus else T.inputBorder,
                            shape,
                        ).padding(horizontal = T.inputPaddingX),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.text.isEmpty()) {
                        Text(placeholder, color = T.inputPlaceholder, fontSize = T.androidPopupFontSize, maxLines = 1)
                    }
                    field()
                }
            },
        )
    }
}

/** A button in the popup's action row. The primary one is filled; a disabled one stays in place. */
@Composable
private fun PopupButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(T.radius)
    val fill =
        when {
            primary && !enabled -> T.stateDisabledFill
            primary -> T.buttonPrimaryBg
            else -> Color.Transparent
        }
    val ink =
        when {
            !enabled -> T.stateDisabledFg
            primary -> T.buttonPrimaryFg
            else -> T.textPrimary
        }
    Box(
        Modifier
            .height(T.androidPopupRowHeight)
            .drawBehind {
                if (focused) {
                    // The focus ring sits one border width outside the button.
                    val inset = -(T.borderWidth + T.focusRingWidth / 2).toPx()
                    drawRoundRect(
                        color = T.borderFocus,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - inset * 2, size.height - inset * 2),
                        cornerRadius = CornerRadius(T.radius.toPx()),
                        style = Stroke(T.focusRingWidth.toPx()),
                    )
                }
            }.background(fill, shape)
            .border(T.borderWidth, if (primary && !enabled) T.stateDisabledBorder else Color.Transparent, shape)
            .background(if (pressed && enabled) T.overlayActive else Color.Transparent, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ).padding(horizontal = T.androidPopupPaddingX),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = ink, fontSize = T.androidPopupFontSize, fontWeight = T.fontWeightSemibold, maxLines = 1)
    }
}
