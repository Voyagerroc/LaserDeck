package com.voyagerroc.laserdeck.ptt.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Ses seviyesi göstergesi: ayrık çentikler.
 *
 * Sürekli bir çubuk yerine çentik dizisi, uygulamanın geri kalanıyla aynı dili
 * konuşuyor (mesaj kenarındaki çizgi, hat göstergesi) ve bir aygıt panelinde
 * beklenen şey bu. Çağrıda gerçek bir işi var: hattın ses taşıyıp taşımadığını
 * söylüyor — sessizlik "kimse konuşmuyor" da olabilir, "mikrofon açılmadı" da.
 */
class LevelMeter @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickWidth = dp(3f)
    private val gap = dp(3f)

    private var litColor = 0xFFFF2A3C.toInt()
    private var dimColor = 0xFF262D3B.toInt()

    /** 0..1 arası. Değişim küçükse yeniden çizim yapılmaz. */
    private var level = 0f

    fun setColors(lit: Int, dim: Int) {
        litColor = lit
        dimColor = dim
        invalidate()
    }

    fun setLevel(value: Float) {
        val v = value.coerceIn(0f, 1f)
        // Ses her 100 ms'de bir güncelleniyor; 1/40'lık değişimden küçük
        // oynamalar için tüm görünümü yeniden çizmeye değmez.
        if (kotlin.math.abs(v - level) < 0.025f) return
        level = v
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        if (h <= 0f || w <= 0f) return

        val step = tickWidth + gap
        val count = ((w + gap) / step).toInt().coerceAtLeast(1)
        val lit = (level * count).toInt()

        var x = 0f
        for (i in 0 until count) {
            paint.color = if (i < lit) litColor else dimColor
            canvas.drawRect(x, 0f, x + tickWidth, h, paint)
            x += step
        }
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    )
}
