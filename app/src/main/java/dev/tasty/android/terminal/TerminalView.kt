package dev.tasty.android.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import androidx.core.graphics.withTranslation
import dev.tasty.android.R
import dev.tasty.android.core.CellColor
import dev.tasty.android.core.ScreenSnapshot
import dev.tasty.android.core.TextSpan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Draws the shell screen and sends keyboard input to it.
 *
 * Tapping the view calls [onTap], shows the soft keyboard, and restarts the shell after it
 * exited. Only the [active] view takes the keyboard focus when it appears.
 */
@SuppressLint("ViewConstructor")
class TerminalView(
    context: Context,
    private val model: SurfaceSession,
    private val palette: TerminalPalette = TerminalPalette.DARK,
) : View(context) {
    var onTap: () -> Unit = {}

    var active: Boolean = false
        set(value) {
            field = value
            if (value && isAttachedToWindow) requestFocus()
        }

    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, FONT_SP, resources.displayMetrics)
        }
    private val fillPaint = Paint()
    private val cellWidth = textPaint.measureText("M")
    private val cellHeight: Float
    private val baseline: Float
    private var cols = 0
    private var rows = 0
    private var composing = ""
    private val onOutput: () -> Unit = { postInvalidateOnAnimation() }
    private val scrollBarWidth =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SCROLL_BAR_DP, resources.displayMetrics)

    init {
        val metrics = textPaint.fontMetrics
        cellHeight = ceil(metrics.descent - metrics.ascent)
        baseline = -metrics.ascent
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(palette.background)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        model.addOutputListener(onOutput)
        if (active) requestFocus()
    }

    override fun onDetachedFromWindow() {
        model.removeOutputListener(onOutput)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        cols = max(1, floor((w - paddingLeft - paddingRight) / cellWidth).toInt())
        rows = max(1, floor((h - paddingTop - paddingBottom) / cellHeight).toInt())
        model.ensureStarted(cols, rows)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val snapshot = model.session?.snapshot()
        if (snapshot != null) {
            canvas.withTranslation(paddingLeft.toFloat(), paddingTop.toFloat()) {
                snapshot.lines.forEachIndexed { row, spans -> spans.forEach { drawSpan(this, row, it) } }
                drawCursor(this, snapshot)
            }
            drawScrollIndicator(canvas, snapshot)
            if (snapshot.exited) model.markExited()
        }
        val error = model.status.error
        when {
            error != null -> drawNotice(canvas, context.getString(R.string.terminal_start_failed, error))
            model.status.exited -> drawNotice(canvas, context.getString(R.string.terminal_exited))
        }
    }

    /** Draws a one-line notice on the last row, over the screen contents. */
    private fun drawNotice(
        canvas: Canvas,
        text: String,
    ) {
        val top = height - paddingBottom - cellHeight
        fillPaint.color = palette.indexed(NOTICE_BACKGROUND)
        canvas.drawRect(0f, top, width.toFloat(), top + cellHeight, fillPaint)
        resetTextStyle()
        textPaint.isUnderlineText = false
        textPaint.color = palette.foreground
        canvas.drawText(text, paddingLeft.toFloat(), top + baseline, textPaint)
    }

    private fun drawSpan(
        canvas: Canvas,
        row: Int,
        span: TextSpan,
    ) {
        val flags = span.flags.toInt()
        val bold = flags and FLAG_BOLD != 0
        var fg = palette.foreground(span.fg, bold)
        var bg = palette.background(span.bg)
        val reverse = flags and FLAG_REVERSE != 0
        if (reverse) fg = bg.also { bg = fg }
        val left = span.col.toFloat() * cellWidth
        val top = row * cellHeight
        if (reverse || span.bg != CellColor.Default) {
            fillPaint.color = bg
            canvas.drawRect(left, top, left + span.cells.toFloat() * cellWidth, top + cellHeight, fillPaint)
        }
        if (flags and FLAG_INVISIBLE != 0) return
        textPaint.color = fg
        if (flags and FLAG_DIM != 0) textPaint.alpha = DIM_ALPHA
        textPaint.isFakeBoldText = bold
        textPaint.textSkewX = if (flags and FLAG_ITALIC != 0) ITALIC_SKEW else 0f
        textPaint.isUnderlineText = flags and FLAG_UNDERLINE != 0
        textPaint.isStrikeThruText = flags and FLAG_STRIKETHROUGH != 0
        drawText(canvas, span, left, top + baseline)
    }

    /** Draws a span. Non-ASCII text goes cell by cell so fallback fonts cannot shift the grid. */
    private fun drawText(
        canvas: Canvas,
        span: TextSpan,
        left: Float,
        y: Float,
    ) {
        val text = span.text
        if (text.all { it.code < ASCII_LIMIT } || span.cells.toInt() != text.codePointCount(0, text.length)) {
            canvas.drawText(text, left, y, textPaint)
            return
        }
        var x = left
        var index = 0
        while (index < text.length) {
            val next = text.offsetByCodePoints(index, 1)
            canvas.drawText(text, index, next, x, y, textPaint)
            x += cellWidth
            index = next
        }
    }

    private fun drawCursor(
        canvas: Canvas,
        snapshot: ScreenSnapshot,
    ) {
        if (!snapshot.cursorVisible) return
        val left = snapshot.cursorCol.toFloat() * cellWidth
        val top = snapshot.cursorRow.toFloat() * cellHeight
        fillPaint.color = palette.cursor
        fillPaint.alpha = CURSOR_ALPHA
        canvas.drawRect(left, top, left + cellWidth, top + cellHeight, fillPaint)
        fillPaint.alpha = OPAQUE
        if (composing.isNotEmpty()) {
            resetTextStyle()
            textPaint.color = palette.foreground
            textPaint.isUnderlineText = true
            canvas.drawText(composing, left, top + baseline, textPaint)
            textPaint.isUnderlineText = false
        }
    }

    private fun resetTextStyle() {
        textPaint.isFakeBoldText = false
        textPaint.textSkewX = 0f
        textPaint.isStrikeThruText = false
    }

    /** Dragging scrolls the history (down reveals older rows); a tap focuses and opens the keyboard. */
    private val gestures =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    scroller.forceFinished(true)
                    scrollRemainder = 0f
                    return true
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean = performClick()

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    scrollPixels(-distanceY)
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    flingLastY = 0
                    scroller.fling(0, 0, 0, velocityY.toInt(), 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)
                    postInvalidateOnAnimation()
                    return true
                }
            },
        )
    private val scroller = OverScroller(context)
    private var scrollRemainder = 0f
    private var flingLastY = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val y = scroller.currY
            scrollPixels((y - flingLastY).toFloat())
            flingLastY = y
            postInvalidateOnAnimation()
        }
    }

    /** Moves the view by whole rows; positive [pixels] (finger moving down) goes back in history. */
    private fun scrollPixels(pixels: Float) {
        scrollRemainder += pixels
        val rows = (scrollRemainder / cellHeight).toInt()
        if (rows == 0) return
        scrollRemainder -= rows * cellHeight
        model.session?.scrollBy(rows)
        invalidate()
    }

    /** A thin bar on the right edge showing where the shown rows sit in the history. */
    private fun drawScrollIndicator(
        canvas: Canvas,
        snapshot: ScreenSnapshot,
    ) {
        if (snapshot.scrollOffset == 0u) return
        val rows = snapshot.rows.toFloat()
        val total = snapshot.historyLen.toFloat() + rows
        val track = (height - paddingTop - paddingBottom).toFloat()
        val barHeight = max(track * rows / total, cellHeight)
        val top =
            paddingTop + (track - barHeight) * (1f - snapshot.scrollOffset.toFloat() / snapshot.historyLen.toFloat())
        fillPaint.color = palette.foreground
        fillPaint.alpha = SCROLL_BAR_ALPHA
        val right = (width - paddingRight).toFloat()
        canvas.drawRect(right - scrollBarWidth, top, right, top + barHeight, fillPaint)
        fillPaint.alpha = OPAQUE
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap()
        if (model.status.exited || model.status.error != null) {
            model.restart(cols, rows)
            invalidate()
        }
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0)
        return true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // A visible-password field keeps the keyboard from autocorrecting or predicting words.
        outAttrs.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return TerminalInputConnection()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = sendKey(event) || super.onKeyDown(keyCode, event)

    private fun sendKey(event: KeyEvent): Boolean {
        val session = model.session ?: return false
        val modifiers = KeyModifiers(ctrl = event.isCtrlPressed, alt = event.isAltPressed, shift = event.isShiftPressed)
        val unicode =
            event.getUnicodeChar(
                event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv(),
            )
        val bytes =
            KeyEncoder.encode(event.keyCode, unicode, modifiers, session.applicationCursorKeys()) ?: return false
        session.write(bytes)
        return true
    }

    private fun sendText(text: CharSequence) {
        if (text.isNotEmpty()) model.session?.write(KeyEncoder.text(text))
    }

    /**
     * Receives soft keyboard input. Composed text (such as Hangul being assembled) is drawn at
     * the cursor and sent only once the keyboard commits it.
     */
    private inner class TerminalInputConnection : BaseInputConnection(this@TerminalView, false) {
        override fun commitText(
            text: CharSequence,
            newCursorPosition: Int,
        ): Boolean {
            composing = ""
            sendText(text)
            invalidate()
            return true
        }

        override fun setComposingText(
            text: CharSequence,
            newCursorPosition: Int,
        ): Boolean {
            composing = text.toString()
            invalidate()
            return true
        }

        override fun finishComposingText(): Boolean {
            sendText(composing)
            composing = ""
            invalidate()
            return true
        }

        override fun deleteSurroundingText(
            beforeLength: Int,
            afterLength: Int,
        ): Boolean {
            repeat(beforeLength) { model.session?.write(byteArrayOf(DEL)) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) sendKey(event)
            return true
        }
    }

    private companion object {
        const val FONT_SP = 13f
        const val ASCII_LIMIT = 0x80
        const val DIM_ALPHA = 0x99
        const val ITALIC_SKEW = -0.25f
        const val CURSOR_ALPHA = 0xA0
        const val OPAQUE = 0xFF
        const val NOTICE_BACKGROUND = 8
        const val SCROLL_BAR_ALPHA = 0x80
        const val SCROLL_BAR_DP = 3f
        const val DEL: Byte = 0x7f

        // Matches FLAG_* in rust/tasty-mobile-core/src/snapshot.rs.
        const val FLAG_BOLD = 1
        const val FLAG_DIM = 1 shl 1
        const val FLAG_ITALIC = 1 shl 2
        const val FLAG_UNDERLINE = 1 shl 3
        const val FLAG_STRIKETHROUGH = 1 shl 4
        const val FLAG_REVERSE = 1 shl 5
        const val FLAG_INVISIBLE = 1 shl 6
    }
}
