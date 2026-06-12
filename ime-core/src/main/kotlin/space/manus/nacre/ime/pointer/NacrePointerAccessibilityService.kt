package space.manus.nacre.ime.pointer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.roundToInt

class NacrePointerAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private var cursorView: CursorView? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var cursorDiameterPx = 0
    private var cursorRadiusPx = 0f
    private var pointerX = Float.NaN
    private var pointerY = Float.NaN
    private var isServiceConnected = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WindowManager::class.java)
        cursorDiameterPx = (36f * resources.displayMetrics.density).roundToInt().coerceAtLeast(28)
        cursorRadiusPx = cursorDiameterPx / 2f
        isServiceConnected = true
        FlexPointerBridge.attach(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        tearDownPointer()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        tearDownPointer()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun setPointerVisible(visible: Boolean): Boolean {
        return runOnMainIfConnected {
            if (visible) {
                ensureCursor()
            } else {
                removeCursor()
                true
            }
        }
    }

    fun movePointerBy(dx: Float, dy: Float): Boolean {
        return runOnMainIfConnected {
            if (!ensureCursor()) return@runOnMainIfConnected false
            val bounds = displayBounds()
            initializePointerIfNeeded(bounds)
            pointerX = clampPointer(pointerX + dx, cursorRadiusPx, bounds.width() - cursorRadiusPx)
            pointerY = clampPointer(pointerY + dy, cursorRadiusPx, bounds.height() - cursorRadiusPx)
            updateCursorPosition()
        }
    }

    fun tapPointer(): Boolean {
        val point = currentPointerPoint() ?: return false
        return runCatching {
            dispatchStroke(point.x, point.y, point.x, point.y, 0L, 55L)
        }.getOrDefault(false)
    }

    fun longPressPointer(): Boolean {
        val point = currentPointerPoint() ?: return false
        return runCatching {
            dispatchStroke(point.x, point.y, point.x, point.y, 0L, 560L)
        }.getOrDefault(false)
    }

    fun scrollAtPointer(deltaY: Float): Boolean {
        val point = currentPointerPoint() ?: return false
        return runCatching {
            val bounds = displayBounds()
            val distance = deltaY.coerceIn(-260f, 260f)
            if (kotlin.math.abs(distance) < 12f) return false
            val startY = point.y.coerceIn(cursorRadiusPx + 48f, bounds.height() - cursorRadiusPx - 48f)
            val endY = (startY + distance).coerceIn(cursorRadiusPx + 24f, bounds.height() - cursorRadiusPx - 24f)
            dispatchStroke(point.x, startY, point.x, endY, 0L, 220L)
        }.getOrDefault(false)
    }

    private fun ensureCursor(): Boolean {
        if (!isServiceConnected || !::windowManager.isInitialized) return false
        if (cursorView != null) return true

        val bounds = displayBounds()
        initializePointerIfNeeded(bounds)

        val view = CursorView(this)
        val params = WindowManager.LayoutParams(
            cursorDiameterPx,
            cursorDiameterPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (pointerX - cursorRadiusPx).roundToInt()
            y = (pointerY - cursorRadiusPx).roundToInt()
        }

        return runCatching {
            windowManager.addView(view, params)
            cursorView = view
            cursorParams = params
        }.isSuccess
    }

    private fun removeCursor() {
        val view = cursorView ?: return
        cursorView = null
        cursorParams = null
        if (::windowManager.isInitialized) {
            runCatching { windowManager.removeView(view) }
        }
    }

    private fun updateCursorPosition(): Boolean {
        val view = cursorView ?: return false
        val params = cursorParams ?: return false
        params.x = (pointerX - cursorRadiusPx).roundToInt()
        params.y = (pointerY - cursorRadiusPx).roundToInt()
        val updated = runCatching {
            windowManager.updateViewLayout(view, params)
        }.isSuccess
        if (!updated) removeCursor()
        return updated
    }

    private fun initializePointerIfNeeded(bounds: Rect) {
        if (!pointerX.isNaN() && !pointerY.isNaN()) return
        pointerX = bounds.width() / 2f
        pointerY = (bounds.height() * 0.38f).coerceAtLeast(cursorRadiusPx)
    }

    private fun currentPointerPoint(): PointerPoint? {
        if (!isServiceConnected || !::windowManager.isInitialized) return null
        return runCatching {
            val bounds = displayBounds()
            initializePointerIfNeeded(bounds)
            if (ensureCursor()) PointerPoint(pointerX, pointerY) else null
        }.getOrNull()
    }

    private fun dispatchStroke(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        startTimeMs: Long,
        durationMs: Long,
    ): Boolean {
        if (!isServiceConnected) return false
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, startTimeMs, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun displayBounds(): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun tearDownPointer() {
        isServiceConnected = false
        mainHandler.removeCallbacksAndMessages(null)
        removeCursor()
        FlexPointerBridge.detach(this)
    }

    private fun runOnMainIfConnected(block: () -> Boolean): Boolean {
        if (!isServiceConnected) return false
        val guardedBlock = {
            if (isServiceConnected) {
                runCatching(block).getOrElse {
                    removeCursor()
                    false
                }
            } else {
                false
            }
        }
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            guardedBlock()
        } else {
            mainHandler.post { guardedBlock() }
            true
        }
    }

    private fun clampPointer(value: Float, min: Float, max: Float): Float {
        return if (max < min) min else value.coerceIn(min, max)
    }

    private data class PointerPoint(val x: Float, val y: Float)

    private class CursorView(context: android.content.Context) : View(context) {
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * resources.displayMetrics.density
            color = Color.rgb(255, 34, 34)
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 7f * resources.displayMetrics.density
            color = Color.argb(70, 255, 34, 34)
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(255, 80, 80)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = width.coerceAtMost(height) / 2f - glowPaint.strokeWidth
            canvas.drawCircle(cx, cy, radius, glowPaint)
            canvas.drawCircle(cx, cy, radius, strokePaint)
            canvas.drawCircle(cx, cy, 2.5f * resources.displayMetrics.density, dotPaint)
        }
    }
}
