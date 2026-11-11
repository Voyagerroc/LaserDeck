package com.voyagerroc.laserdeck.ptt.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.voyagerroc.laserdeck.ptt.ranging.RangeEstimate
import kotlin.math.ln
import kotlin.math.min

/**
 * Radar kapsamı.
 *
 * ## Neden nokta değil halka
 * Bluetooth ile iki telefon arasındaki MESAFE ölçülebilir, YÖN ölçülemez — tek
 * antenle yön bilgisi fiziksel olarak yoktur. Bir noktayı bir açıya koymak
 * uydurma olurdu. Halka tam olarak bildiğimiz şeyi çiziyor: "şu yarıçapta, bir
 * yerde".
 *
 * ## Neden logaritmik yarıçap
 * Sinyalden mesafe kestiriminin hatası çarpansaldır (her mesafede ±%60 gibi).
 * Yarıçap logaritmik olunca bu belirsizlik ekranda HER MESAFEDE aynı kalınlıkta
 * çıkıyor — yani halkanın kalınlığı doğrudan "ne kadar eminim"i okutuyor.
 * İnce halka = emin, kalın halka = tahmin. Ayrıca yakın mesafe merkeze
 * çökmüyor.
 *
 * ## Süpürme çizgisi neyi anlatıyor
 * Açısal tarama YAPMIYORUZ, o yüzden süpürme yön taraması taklidi değil:
 * dönüş hızı doğrudan saniyedeki ölçüm sayısıdır. Hızlı dönüyorsa veri akıyor,
 * yavaşlıyorsa örnekler seyreliyor, durduysa sinyal yok.
 */
class RadarScope @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val D_MIN = 0.5
        private const val D_MAX = 60.0
        private val RINGS = doubleArrayOf(1.0, 3.0, 10.0, 30.0)
    }

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(7f)
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private val dash = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)

    private var accent = Color.parseColor("#FF2A3C")
    private var dim = Color.parseColor("#262D3B")
    private var faint = Color.parseColor("#8A93A6")

    private var estimate = RangeEstimate()
    private var sweepAngle = 0f
    private var lastFrameMs = 0L

    fun setColors(accent: Int, dim: Int, faint: Int) {
        this.accent = accent
        this.dim = dim
        this.faint = faint
        invalidate()
    }

    fun setEstimate(value: RangeEstimate) {
        estimate = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f - dp(2f)

        // --- kadran ---
        grid.color = dim
        canvas.drawCircle(cx, cy, r, grid)
        RINGS.forEach { d ->
            val rr = radiusFor(d, r)
            if (rr > dp(3f) && rr < r) canvas.drawCircle(cx, cy, rr, grid)
        }

        val live = estimate.state == RangeEstimate.State.LIVE ||
                estimate.state == RangeEstimate.State.PAUSED

        // --- hedef halkası ---
        if (live && estimate.high > 0f) {
            val inner = radiusFor(estimate.low.toDouble(), r)
            val outer = radiusFor(estimate.high.toDouble(), r)
            val duraklatildi = estimate.state == RangeEstimate.State.PAUSED

            stroke.color = accent
            stroke.alpha = if (duraklatildi) 120 else 255
            stroke.pathEffect = if (duraklatildi) dash else null
            canvas.drawCircle(cx, cy, inner, stroke)
            canvas.drawCircle(cx, cy, outer, stroke)

            // Aradaki bandı seyrek eş merkezli çizgilerle tara: dolu bir halka
            // "kesin" görünürdü, taranmış bant belirsizliği taşıyor.
            val bandAlpha = if (duraklatildi) 18 else 36
            stroke.alpha = bandAlpha
            var rr = inner + dp(2.5f)
            while (rr < outer - dp(1f)) {
                canvas.drawCircle(cx, cy, rr, stroke)
                rr += dp(2.5f)
            }
            stroke.alpha = 255
            stroke.pathEffect = null
        }

        // --- merkez: sen ---
        fill.color = if (live) accent else dim
        fill.alpha = 255
        canvas.drawCircle(cx, cy, dp(2.5f), fill)

        // --- süpürme: dönüş hızı = ölçüm hızı ---
        if (estimate.state == RangeEstimate.State.LIVE && estimate.sampleHz > 0.05f) {
            val now = System.currentTimeMillis()
            if (lastFrameMs != 0L) {
                val dt = (now - lastFrameMs).coerceAtMost(200L) / 1000f
                // Bir tur = bir ölçüm döngüsü; hız doğrudan örnek sayısından.
                sweepAngle = (sweepAngle + estimate.sampleHz.coerceIn(0.2f, 3f) * 360f * dt) % 360f
            }
            lastFrameMs = now
            sweepPaint.color = accent
            sweepPaint.alpha = 150
            val rad = Math.toRadians(sweepAngle.toDouble() - 90.0)
            canvas.drawLine(
                cx, cy,
                cx + (r * Math.cos(rad)).toFloat(),
                cy + (r * Math.sin(rad)).toFloat(),
                sweepPaint
            )
            postInvalidateOnAnimation()
        } else {
            lastFrameMs = 0L
        }

        // --- menzil etiketleri ---
        label.color = faint
        RINGS.forEach { d ->
            val rr = radiusFor(d, r)
            if (rr > dp(8f) && rr < r - dp(4f)) {
                canvas.drawText(d.toInt().toString(), cx + dp(2f), cy - rr + sp(7f), label)
            }
        }
    }

    /** Logaritmik yarıçap: belirsizlik her mesafede aynı kalınlıkta görünsün. */
    private fun radiusFor(meters: Double, maxR: Float): Float {
        val d = meters.coerceIn(D_MIN, D_MAX)
        val t = ln(d / D_MIN) / ln(D_MAX / D_MIN)
        return (t * maxR).toFloat()
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
