package com.voyagerroc.laserdeck.ptt.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * CRT tarama çizgileri — her şeyin üstüne çizilen ince yatay hatlar.
 *
 * Çok bilinçli biçimde zayıf: %4 opaklıkta ve 3 piksel aralıkla. Fosfor
 * ekran hissini veriyor ama metnin okunurluğunu bozmuyor. Dokunuşları da
 * geçirir (clickable=false), yani altındaki hiçbir şeyi engellemez.
 */
class ScanlineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.BLACK
        alpha = 10 // 255'in ~%4'ü
        strokeWidth = 1f
    }

    private val period = maxOf(2f, resources.displayMetrics.density * 1.5f)

    init {
        // Dokunulamaz ve odaklanamaz: yalnızca bir doku katmanı.
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        var y = 0f
        while (y < height) {
            canvas.drawLine(0f, y, w, y, paint)
            y += period
        }
    }
}
