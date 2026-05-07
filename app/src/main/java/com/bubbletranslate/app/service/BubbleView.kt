package com.bubbletranslate.app.service

import android.content.Context
import android.graphics.*
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import com.bubbletranslate.app.App
import com.bubbletranslate.app.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

class BubbleView(context: Context) : FrameLayout(context) {

    enum class State { IDLE, SELECT, TRANSLATE }

    var bubbleState: State = State.IDLE
        private set

    var onBubbleTap: (() -> Unit)? = null
    var onSelectionComplete: ((Float, Float, Float, Float) -> Unit)? = null
    var onSelectionCancel: (() -> Unit)? = null
    var onDismissToIdle: (() -> Unit)? = null
    var onPositionRequest: ((x: Int, y: Int) -> Unit)? = null
    var onPanelDrag: ((x: Int, y: Int) -> Unit)? = null

    var storedX = 100
    var storedY = 400
    var panelX = 0
    var panelY = 0

    // ── idle drag ────────────────────────────────────────────────

    private var dragInitialX = 0
    private var dragInitialY = 0
    private var dragTouchX = 0f
    private var dragTouchY = 0f

    // ── selection ────────────────────────────────────────────────

    private var selStartX = 0f
    private var selStartY = 0f
    private var selEndX = 0f
    private var selEndY = 0f
    private var rawStartX = 0f
    private var rawStartY = 0f
    private var rawEndX = 0f
    private var rawEndY = 0f
    private var hasSelection = false

    // ── translate drag ───────────────────────────────────────────

    private var tdDragging = false
    private var tdDownRawX = 0f
    private var tdDownRawY = 0f
    private var tdDownTime = 0L
    private var tdDragInitPX = 0
    private var tdDragInitPY = 0
    private var tdDragRawX = 0f
    private var tdDragRawY = 0f

    // ── paints ───────────────────────────────────────────────────

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF9800.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1AFF9800.toInt()
        style = Paint.Style.FILL
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 48f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF9800.toInt()
        style = Paint.Style.FILL
    }

    // ── child views ──────────────────────────────────────────────

    private lateinit var iconLayer: ImageView
    private lateinit var panelContainer: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelText: TextView
    private lateinit var copyBtn: TextView

    private val maxTextHeight by lazy {
        (context.resources.displayMetrics.heightPixels * 0.55).toInt()
    }

    init {
        setWillNotDraw(false)

        // ── Layer 0: icon (IDLE) ─────────────────────────────────
        iconLayer = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_bubble))
            setBackgroundResource(R.drawable.bg_bubble)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LayoutParams(56.dp, 56.dp, Gravity.CENTER)
            alpha = App.instance.bubbleAlpha / 100f
        }
        addView(iconLayer)

        // ── Layer 1: translation panel (TRANSLATE) ───────────────
        panelContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundResource(R.drawable.bg_popup)
            elevation = 16f
            setPadding(20.dp, 20.dp, 20.dp, 16.dp)
        }

        // ── Title bar ────────────────────────────────────────────
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val accentBar = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(4.dp, 24.dp)
            setBackgroundColor(ContextCompat.getColor(context, R.color.primary))
        }
        titleRow.addView(accentBar)

        panelTitle = TextView(context).apply {
            id = View.generateViewId()
            text = context.getString(R.string.translation_result)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = 12.dp }
        }
        titleRow.addView(panelTitle)

        copyBtn = TextView(context).apply {
            text = context.getString(R.string.copy_text)
            setTextColor(ContextCompat.getColor(context, R.color.primary))
            textSize = 14f
            setPadding(12.dp, 4.dp, 0, 4.dp)
        }
        titleRow.addView(copyBtn)
        panelContainer.addView(titleRow)

        // ── Translation text (no ScrollView — panel grows to fit) ─
        panelText = TextView(context).apply {
            id = View.generateViewId()
            text = context.getString(R.string.translating)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 15f
            setPadding(4.dp, 8.dp, 4.dp, 8.dp)
            maxHeight = maxTextHeight
        }
        panelContainer.addView(panelText)

        // ── Hint ─────────────────────────────────────────────────
        val hint = TextView(context).apply {
            text = "Tap anywhere outside to close"
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 12f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 8.dp, 0, 0)
        }
        panelContainer.addView(hint)

        addView(panelContainer)
    }

    // ── screen geometry helpers ───────────────────────────────────

    private fun getStatusBarHeight(): Int {
        val resourceId = context.resources.getIdentifier(
            "status_bar_height", "dimen", "android"
        )
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }

    /** Pixel size of the IDLE bubble (matches iconLayer layoutParams). */
    private fun idleBubbleSize(): Int = 56.dp

    // ── state transitions ────────────────────────────────────────

    fun setState(newState: State) {
        bubbleState = newState
        when (newState) {
            State.IDLE -> {
                iconLayer.alpha = App.instance.bubbleAlpha / 100f
                iconLayer.visibility = VISIBLE
                panelContainer.visibility = GONE
                hasSelection = false
            }
            State.SELECT -> {
                iconLayer.visibility = GONE
                panelContainer.visibility = GONE
                selStartX = 0f; selStartY = 0f
                selEndX = 0f; selEndY = 0f
                hasSelection = false
            }
            State.TRANSLATE -> {
                iconLayer.visibility = GONE
                panelContainer.visibility = VISIBLE
                hasSelection = false
            }
        }
        invalidate()
    }

    // ── text helpers ─────────────────────────────────────────────

    fun setPanelTitleText(text: String) { panelTitle.text = text }
    fun getPanelText(): String = panelText.text.toString()
    fun setPanelText(text: String) { panelText.text = text }

    fun appendPanelText(chunk: String) {
        val current = panelText.text.toString()
        val prefix = context.getString(R.string.translating)
        val clean = if (current == prefix) "" else current
        panelText.text = clean + chunk
    }

    fun updateBubbleAlpha() {
        iconLayer.alpha = App.instance.bubbleAlpha / 100f
    }

    // ── ACTION_OUTSIDE ───────────────────────────────────────────

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            if (bubbleState == State.TRANSLATE) onDismissToIdle?.invoke()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Touch
    // ═══════════════════════════════════════════════════════════════

    /**
     * In TRANSLATE we intercept everything.  No ScrollView means no
     * child ever needs to be the touch target — BubbleView itself
     * receives all events and handles drag uniformly on every pixel
     * of the panel.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        bubbleState == State.TRANSLATE

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (bubbleState) {
            State.IDLE      -> handleIdleTouch(event)
            State.SELECT    -> handleSelection(event)
            State.TRANSLATE -> handleTranslateTouch(event)
        }
    }

    // ── IDLE ─────────────────────────────────────────────────────

    private fun handleIdleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragInitialX = storedX; dragInitialY = storedY
                dragTouchX = event.rawX; dragTouchY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - dragTouchX).toInt()
                val dy = (event.rawY - dragTouchY).toInt()
                if (abs(dx) > 5 || abs(dy) > 5) {
                    val screenW = context.resources.displayMetrics.widthPixels
                    val screenH = context.resources.displayMetrics.heightPixels
                    val statusBarH = getStatusBarHeight()
                    val bubbleSize = idleBubbleSize()
                    storedX = (dragInitialX + dx)
                        .coerceIn(0, (screenW - bubbleSize).coerceAtLeast(0))
                    storedY = (dragInitialY + dy)
                        .coerceIn(statusBarH, (screenH - bubbleSize).coerceAtLeast(statusBarH))
                    onPositionRequest?.invoke(storedX, storedY)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (abs(event.rawX - dragTouchX) < 10 &&
                    abs(event.rawY - dragTouchY) < 10
                ) onBubbleTap?.invoke()
                return true
            }
        }
        return false
    }

    // ── TRANSLATE (pure drag, no scroll) ─────────────────────────
    //
    //  Without a ScrollView there is no ambiguity: every touch that
    //  moves more than the slop threshold is a panel drag.  Short
    //  taps on the copy button still trigger the clipboard action.

    private fun handleTranslateTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                tdDownRawX = event.rawX
                tdDownRawY = event.rawY
                tdDownTime = event.eventTime
                tdDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (tdDragging) {
                    val dx = (event.rawX - tdDragRawX).toInt()
                    val dy = (event.rawY - tdDragRawY).toInt()
                    if (abs(dx) > 3 || abs(dy) > 3) {
                        val screenW = context.resources.displayMetrics.widthPixels
                        val screenH = context.resources.displayMetrics.heightPixels
                        val statusBarH = getStatusBarHeight()
                        val panelW = panelContainer.width.coerceAtLeast(100)
                        val panelH = panelContainer.height.coerceAtLeast(100)
                        panelX = (tdDragInitPX + dx)
                            .coerceIn(0, (screenW - panelW).coerceAtLeast(0))
                        panelY = (tdDragInitPY + dy)
                            .coerceIn(statusBarH, (screenH - panelH).coerceAtLeast(statusBarH))
                        onPanelDrag?.invoke(panelX, panelY)
                    }
                    return true
                }
                val dx = abs(event.rawX - tdDownRawX)
                val dy = abs(event.rawY - tdDownRawY)
                if (dx > 10 || dy > 10) {
                    tdDragging = true
                    tdDragInitPX = panelX
                    tdDragInitPY = panelY
                    tdDragRawX = event.rawX
                    tdDragRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (tdDragging) {
                    val dx = (event.rawX - tdDragRawX).toInt()
                    val dy = (event.rawY - tdDragRawY).toInt()
                    val screenW = context.resources.displayMetrics.widthPixels
                    val screenH = context.resources.displayMetrics.heightPixels
                    val statusBarH = getStatusBarHeight()
                    val panelW = panelContainer.width.coerceAtLeast(100)
                    val panelH = panelContainer.height.coerceAtLeast(100)
                    panelX = (tdDragInitPX + dx)
                        .coerceIn(0, (screenW - panelW).coerceAtLeast(0))
                    panelY = (tdDragInitPY + dy)
                        .coerceIn(statusBarH, (screenH - panelH).coerceAtLeast(statusBarH))
                    onPanelDrag?.invoke(panelX, panelY)
                    tdDragging = false
                    return true
                }
                // Short tap → copy button hit test
                val dist = abs(event.rawX - tdDownRawX) +
                        abs(event.rawY - tdDownRawY)
                if (dist < 20 && event.eventTime - tdDownTime < 400) {
                    val btnLoc = IntArray(2)
                    copyBtn.getLocationOnScreen(btnLoc)
                    if (event.rawX >= btnLoc[0] &&
                        event.rawX < btnLoc[0] + copyBtn.width &&
                        event.rawY >= btnLoc[1] &&
                        event.rawY < btnLoc[1] + copyBtn.height
                    ) copyTextToClipboard()
                }
                tdDragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                tdDragging = false
                return true
            }
        }
        return true
    }

    private fun copyTextToClipboard() {
        val text = panelText.text.toString()
        val prefix = context.getString(R.string.translating)
        if (text.isNotBlank() && text != prefix) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            cm.setPrimaryClip(
                android.content.ClipData.newPlainText("translation", text)
            )
            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
        }
    }

    // ── SELECT ───────────────────────────────────────────────────

    private fun handleSelection(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                selStartX = event.x;  selStartY = event.y
                selEndX   = event.x;  selEndY   = event.y
                rawStartX = event.rawX; rawStartY = event.rawY
                rawEndX   = event.rawX; rawEndY   = event.rawY
                hasSelection = false
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                selEndX = event.x;  selEndY = event.y
                rawEndX = event.rawX; rawEndY = event.rawY
                hasSelection = abs(selEndX - selStartX) > 10 ||
                        abs(selEndY - selStartY) > 10
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (hasSelection)
                    onSelectionComplete?.invoke(
                        min(rawStartX, rawEndX), min(rawStartY, rawEndY),
                        max(rawStartX, rawEndX), max(rawStartY, rawEndY)
                    )
                else onSelectionCancel?.invoke()
                return true
            }
        }
        return false
    }

    // ── drawing (SELECT only) ────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bubbleState != State.SELECT) return

        if (hasSelection) {
            val l = min(selStartX, selEndX)
            val t = min(selStartY, selEndY)
            val r = max(selStartX, selEndX)
            val b = max(selStartY, selEndY)
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, t, dimPaint)
            canvas.drawRect(0f, b, w, h, dimPaint)
            canvas.drawRect(0f, t, l, b, dimPaint)
            canvas.drawRect(r, t, w, b, dimPaint)
            canvas.drawRect(l, t, r, b, fillPaint)
            canvas.drawRect(l, t, r, b, borderPaint)
            val hs = 16f
            canvas.drawCircle(l, t, hs, handlePaint)
            canvas.drawCircle(r, t, hs, handlePaint)
            canvas.drawCircle(l, b, hs, handlePaint)
            canvas.drawCircle(r, b, hs, handlePaint)
        } else {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            val cx = width / 2f; val cy = height / 2f
            canvas.drawText("Drag to select area to translate", cx, cy - 24f, guidePaint)
            val smallPaint = Paint(guidePaint).apply { textSize = 32f }
            canvas.drawText("Tap to cancel", cx, cy + 40f, smallPaint)
        }
    }
}

private val Int.dp: Int get() = (this * android.util.TypedValue.applyDimension(
    android.util.TypedValue.COMPLEX_UNIT_DIP, 1f,
    android.content.res.Resources.getSystem().displayMetrics
)).toInt()
