package com.voyagerroc.laserdeck.ptt.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Açılış dizisi — uygulama açılırken satır satır akan sistem raporu.
 *
 * Süs değil: her satır GERÇEK durumu bildiriyor (Bluetooth açık mı, kaç cihaz
 * eşleşmiş, hangi ses codec'i kurulabiliyor, kaç mesaj kayıtlı). Sahte bir
 * "ACCESS GRANTED" tiyatrosu yerine, aygıtın kendi kendini sınaması.
 *
 * Bu yüzden bazı satırlar beklemeli: codec sınaması gerçekten MediaCodec
 * kuruyor ve yüzlerce ms sürebiliyor — o iş çağıran tarafından arka planda
 * yapılır, buraya hazır sonuç gelir.
 */
class BootSequence(
    private val view: TextView,
    private val scope: CoroutineScope,
    private val dimColor: Int,
    private val accentColor: Int,
    private val textColor: Int
) {
    companion object {
        /** Satır arası — yeterince hızlı ki bekletmesin, yeterince yavaş ki okunsun. */
        private const val LINE_DELAY_MS = 55L
        private const val TAIL_DELAY_MS = 420L
    }

    data class Line(val tag: String, val label: String, val value: String)

    private var job: Job? = null

    /**
     * [lines] satırlarını sırayla yazar, sonra [onDone] çağırır.
     * [skip] ile atlanabilir — kullanıcı ekrana dokunduğunda.
     */
    fun run(header: List<String>, lines: List<Line>, onDone: () -> Unit) {
        job?.cancel()
        val buffer = SpannableStringBuilder()
        job = scope.launch {
            header.forEach { h ->
                appendPlain(buffer, h, if (h.startsWith("LASERDECK")) accentColor else dimColor)
                view.text = buffer
                delay(LINE_DELAY_MS)
            }
            appendPlain(buffer, "", dimColor)
            lines.forEach { line ->
                appendLine(buffer, line)
                view.text = buffer
                delay(LINE_DELAY_MS)
            }
            appendPlain(buffer, "", dimColor)
            appendPlain(buffer, "> HAZIR_", accentColor)
            view.text = buffer
            delay(TAIL_DELAY_MS)
            onDone()
        }
    }

    fun skip() {
        job?.cancel()
        job = null
    }

    /** `[ OK ] etiket ........... değer` — terminal okuması. */
    private fun appendLine(sb: SpannableStringBuilder, line: Line) {
        val tagStart = sb.length
        sb.append("[").append(line.tag).append("] ")
        sb.setSpan(
            ForegroundColorSpan(if (line.tag.trim() == "OK") accentColor else dimColor),
            tagStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        val leaderStart = sb.length
        val dots = (26 - line.label.length).coerceAtLeast(1)
        sb.append(line.label).append(" ").append(".".repeat(dots)).append(" ")
        sb.setSpan(
            ForegroundColorSpan(dimColor),
            leaderStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        val valueStart = sb.length
        sb.append(line.value).append("\n")
        sb.setSpan(
            ForegroundColorSpan(textColor),
            valueStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun appendPlain(sb: SpannableStringBuilder, text: String, color: Int) {
        val start = sb.length
        sb.append(text).append("\n")
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
