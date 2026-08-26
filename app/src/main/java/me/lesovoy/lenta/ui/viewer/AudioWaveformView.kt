package me.lesovoy.lenta.ui.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

class AudioWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BB86FC")
        style = Paint.Style.FILL
    }

    private val unplayedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        style = Paint.Style.FILL
    }

    private var progress: Float = 0f
    private var isPlaying: Boolean = false
    private var animPhase: Float = 0f
    private var amplitudes: FloatArray = generateDefaultAmplitudes(60, 42L)
    private val barRect = RectF()

    var onSeekListener: ((progressFraction: Float) -> Unit)? = null

    fun setProgress(newProgress: Float) {
        val clamped = newProgress.coerceIn(0f, 1f)
        if (progress != clamped) {
            progress = clamped
            invalidate()
        }
    }

    fun getProgress(): Float = progress

    fun setPlaying(playing: Boolean) {
        if (isPlaying != playing) {
            isPlaying = playing
            invalidate()
        }
    }

    fun setWaveformSeed(seedKey: String, sampleCount: Int = 64) {
        val seed = seedKey.hashCode().toLong()
        amplitudes = generateDefaultAmplitudes(sampleCount, seed)
        invalidate()
    }

    fun setAmplitudes(amps: FloatArray) {
        if (amps.isNotEmpty()) {
            amplitudes = amps
            invalidate()
        }
    }

    private fun generateDefaultAmplitudes(count: Int, seed: Long): FloatArray {
        val random = Random(seed)
        val result = FloatArray(count)
        var prev = 0.3f
        for (i in 0 until count) {
            val base = 0.15f + 0.7f * random.nextFloat()
            // Smooth a bit with neighboring bars
            val smooth = (prev * 0.4f + base * 0.6f).coerceIn(0.12f, 1.0f)
            result[i] = smooth
            prev = smooth
        }
        return result
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val count = amplitudes.size
        if (count == 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val barSpacing = 4f
        val totalSpacing = barSpacing * (count - 1)
        val barWidth = ((w - totalSpacing) / count).coerceAtLeast(2f)
        val cornerRadius = barWidth / 2f

        val currentProgressX = progress * w

        if (isPlaying) {
            animPhase = (animPhase + 0.15f) % (2f * Math.PI.toFloat())
        }

        for (i in 0 until count) {
            val x = i * (barWidth + barSpacing)
            val baseAmp = amplitudes[i]

            val dynamicAmp = if (isPlaying && (i.toFloat() / count <= progress + 0.1f && i.toFloat() / count >= progress - 0.1f)) {
                val bounce = 0.15f * sin(animPhase + i * 0.5).toFloat()
                (baseAmp + bounce).coerceIn(0.12f, 1.0f)
            } else {
                baseAmp
            }

            val barHeight = (h * dynamicAmp).coerceAtLeast(barWidth)
            val top = (h - barHeight) / 2f
            val bottom = top + barHeight
            val right = x + barWidth

            barRect.set(x, top, right, bottom)

            val paint = if (right <= currentProgressX) {
                playedPaint
            } else if (x < currentProgressX) {
                // Split bar or gradient
                playedPaint
            } else {
                unplayedPaint
            }

            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, paint)
        }

        if (isPlaying) {
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val fraction = (event.x / width.toFloat()).coerceIn(0f, 1f)
                progress = fraction
                invalidate()
                onSeekListener?.invoke(fraction)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val fraction = (event.x / width.toFloat()).coerceIn(0f, 1f)
                progress = fraction
                invalidate()
                onSeekListener?.invoke(fraction)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
