package me.lesovoy.lenta.ui.viewer

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.hypot

class PinchToZoomListener(
    private val targetView: View,
    private val onZoneTap: ((TapZone) -> Unit)? = null,
    private val onSingleTap: (() -> Unit)? = null,
    private val onSwipeRight: (() -> Unit)? = null
) : View.OnTouchListener {

    private var scale = 1.0f
    private var isScaling = false
    private var isMultiTouch = false
    private var swipeRightTriggered = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private val scaleGestureDetector = ScaleGestureDetector(
        targetView.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                targetView.animate().cancel()
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY

                val cx = if (targetView.width > 0) targetView.width / 2f else detector.focusX
                val cy = if (targetView.height > 0) targetView.height / 2f else detector.focusY
                targetView.pivotX = cx
                targetView.pivotY = cy
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentScale = targetView.scaleX
                val factor = detector.scaleFactor
                val newScale = (currentScale * factor).coerceIn(1.0f, 6.0f)
                val k = if (currentScale > 0f) newScale / currentScale else 1.0f

                val focusX = detector.focusX
                val focusY = detector.focusY

                val cx = if (targetView.width > 0) targetView.width / 2f else focusX
                val cy = if (targetView.height > 0) targetView.height / 2f else focusY

                val oldTx = targetView.translationX
                val oldTy = targetView.translationY

                val newTx = (focusX - lastFocusX) + (1f - k) * (lastFocusX - cx) + k * oldTx
                val newTy = (focusY - lastFocusY) + (1f - k) * (lastFocusY - cy) + k * oldTy

                targetView.scaleX = newScale
                targetView.scaleY = newScale
                targetView.translationX = newTx
                targetView.translationY = newTy

                lastFocusX = focusX
                lastFocusY = focusY
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        }
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
                isMultiTouch = false
                isScaling = false
                swipeRightTriggered = false
                lastFocusX = event.x
                lastFocusY = event.y
                targetView.animate().cancel()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                isMultiTouch = true
                v.parent?.requestDisallowInterceptTouchEvent(true)
                targetView.animate().cancel()
                if (event.pointerCount >= 2) {
                    lastFocusX = (event.getX(0) + event.getX(1)) / 2f
                    lastFocusY = (event.getY(0) + event.getY(1)) / 2f
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isMultiTouch || isScaling || targetView.scaleX > 1.01f) {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    if (event.pointerCount >= 2 && !isScaling) {
                        val focusX = (event.getX(0) + event.getX(1)) / 2f
                        val focusY = (event.getY(0) + event.getY(1)) / 2f
                        val dx = focusX - lastFocusX
                        val dy = focusY - lastFocusY
                        targetView.translationX += dx
                        targetView.translationY += dy
                        lastFocusX = focusX
                        lastFocusY = focusY
                    }
                } else if (!swipeRightTriggered && onSwipeRight != null) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val touchSlop = ViewConfiguration.get(v.context).scaledTouchSlop
                    if (dx > touchSlop * 4 && dx > 1.4f * kotlin.math.abs(dy)) {
                        swipeRightTriggered = true
                        onSwipeRight.invoke()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
            }

            MotionEvent.ACTION_UP -> {
                v.parent?.requestDisallowInterceptTouchEvent(false)
                if (!isMultiTouch && !isScaling && targetView.scaleX <= 1.05f) {
                    val elapsed = System.currentTimeMillis() - downTime
                    val dist = hypot(event.x - downX, event.y - downY)
                    val touchSlop = ViewConfiguration.get(v.context).scaledTouchSlop
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (elapsed <= 500L && dist <= touchSlop) {
                        val width = v.width.toFloat()
                        val fraction = if (width > 0f) (event.x / width).coerceIn(0f, 1f) else 0.5f
                        val zone = TapZone.fromFraction(fraction)
                        onZoneTap?.invoke(zone)
                        onSingleTap?.invoke()
                        v.performClick()
                    } else if (!swipeRightTriggered && onSwipeRight != null && dx > touchSlop * 2 && dx > 1.2f * kotlin.math.abs(dy) && elapsed < 800L) {
                        onSwipeRight.invoke()
                    }
                }
                resetZoom(animated = true)
            }

            MotionEvent.ACTION_CANCEL -> {
                v.parent?.requestDisallowInterceptTouchEvent(false)
                resetZoom(animated = true)
            }
        }
        return true
    }

    fun resetZoom(animated: Boolean = true) {
        isScaling = false
        isMultiTouch = false
        if (animated) {
            if (targetView.scaleX != 1.0f || targetView.scaleY != 1.0f ||
                targetView.translationX != 0f || targetView.translationY != 0f
            ) {
                targetView.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(250L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        targetView.scaleX = 1.0f
                        targetView.scaleY = 1.0f
                        targetView.translationX = 0f
                        targetView.translationY = 0f
                        if (targetView.width > 0 && targetView.height > 0) {
                            targetView.pivotX = targetView.width / 2f
                            targetView.pivotY = targetView.height / 2f
                        }
                    }
                    .start()
            }
        } else {
            targetView.animate().cancel()
            targetView.scaleX = 1.0f
            targetView.scaleY = 1.0f
            targetView.translationX = 0f
            targetView.translationY = 0f
            if (targetView.width > 0 && targetView.height > 0) {
                targetView.pivotX = targetView.width / 2f
                targetView.pivotY = targetView.height / 2f
            }
        }
    }
}
