package me.lesovoy.lenta.ui.viewer

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

enum class TapZone {
    LEFT,
    MIDDLE,
    RIGHT;

    companion object {
        fun fromFraction(fraction: Float): TapZone {
            return when {
                fraction < 0.35f -> LEFT
                fraction > 0.65f -> RIGHT
                else -> MIDDLE
            }
        }
    }
}

class ZoneTapListener(
    private val onZoneTap: (zone: TapZone) -> Unit
) : View.OnTouchListener {

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
        val tapTimeout = 500L

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val upX = event.x
                val upY = event.y
                val elapsed = System.currentTimeMillis() - downTime
                val distance = hypot(upX - downX, upY - downY)

                if (elapsed <= tapTimeout && distance <= touchSlop) {
                    val width = view.width.toFloat()
                    val fraction = if (width > 0f) (upX / width).coerceIn(0f, 1f) else 0.5f
                    val zone = TapZone.fromFraction(fraction)
                    onZoneTap(zone)
                    view.performClick()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                return false
            }
        }
        return false
    }
}
