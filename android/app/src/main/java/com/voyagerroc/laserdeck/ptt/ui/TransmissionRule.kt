package com.voyagerroc.laserdeck.ptt.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Mesajın kenarındaki dikey çizgi.
 *
 * Telsizde bir mesajın karşıya ulaşıp ulaşmadığı belirsizdir; bu belirsizliği
 * ayrı bir simge ya da dönen çark yerine çizginin kendi çizimi anlatır:
 *
 *   kesik çizgi  -> hat yok, mesaj sırada bekliyor
 *   soluk düz    -> gönderildi, onay bekleniyor
 *   parlak düz   -> karşı taraf aldı
 *
 * Böylece durum, mesaja bakarken kenar görüşle bile okunur.
 */
class TransmissionRule @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Style { SOLID, FADED, DASHED }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val dashEffect = DashPathEffect(floatArrayOf(dp(3f), dp(5f)), 0f)

    private var ruleStyle: Style = Style.SOLID
    private var ruleColor: Int = Color.WHITE

    fun setRule(style: Style, color: Int) {
        if (style == ruleStyle && color == ruleColor) return
        ruleStyle = style
        ruleColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        if (w <= 0f || height <= 0) return

        paint.strokeWidth = w
        paint.color = ruleColor
        // color ataması alfayı sıfırlar, bu yüzden alfa sonra verilir.
        paint.alpha = if (ruleStyle == Style.FADED) 120 else 255
        paint.pathEffect = if (ruleStyle == Style.DASHED) dashEffect else null
        paint.strokeCap = if (ruleStyle == Style.DASHED) Paint.Cap.BUTT else Paint.Cap.ROUND

        // Yuvarlak uç çizgiyi iki uçtan w/2 kadar uzatır; uçları içeri al ki
        // çizgi metnin dikey sınırını taşmasın.
        val inset = if (ruleStyle == Style.DASHED) 0f else w / 2f
        val x = w / 2f
        canvas.drawLine(x, inset, x, height - inset, paint)
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    )
}
