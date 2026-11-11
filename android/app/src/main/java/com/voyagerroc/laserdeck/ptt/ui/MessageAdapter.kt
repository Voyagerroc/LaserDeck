package com.voyagerroc.laserdeck.ptt.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import com.voyagerroc.laserdeck.ptt.R
import com.voyagerroc.laserdeck.ptt.chat.ChatMessage
import java.util.Calendar
import java.util.Date

/**
 * Sohbet listesi. ListView + BaseAdapter bilinçli tercih: uygulamanın tek
 * bağımlılığı coroutines ve APK 1 MB — RecyclerView için androidx'i içeri
 * almak, 500 mesajlık tavanı olan bir liste için ödenecek bedele değmiyor.
 */
class MessageAdapter(private val context: Context) : BaseAdapter() {

    private var items: List<ChatMessage> = emptyList()

    private val inflater = LayoutInflater.from(context)
    private val timeFormat = DateFormat.getTimeFormat(context)
    private val dateFormat = DateFormat.getMediumDateFormat(context)

    private val renkLazer = context.getColor(R.color.lazer)
    private val renkMetin = context.getColor(R.color.metin)
    private val yanBoslukPx = (72 * context.resources.displayMetrics.density).toInt()
    private val kenarBoslukPx = (16 * context.resources.displayMetrics.density).toInt()
    private val dikeyBoslukPx = (7 * context.resources.displayMetrics.density).toInt()

    fun submit(list: List<ChatMessage>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): ChatMessage = items[position]
    override fun getItemId(position: Int): Long = items[position].id.toLong()

    /** Kucuk resimler bir kez cozulur; liste geri donusumu her kaydirmada
     *  yeniden cozmeye kalksa liste takilirdi. */
    private val thumbs = HashMap<String, Bitmap?>()

    private class Holder(val row: LinearLayout) {
        val ruleStart: TransmissionRule = row.findViewById(R.id.ruleStart)
        val ruleEnd: TransmissionRule = row.findViewById(R.id.ruleEnd)
        val body: LinearLayout = row.findViewById(R.id.body)
        val txtBody: TextView = row.findViewById(R.id.txtBody)
        val txtMeta: TextView = row.findViewById(R.id.txtMeta)
        val imgAttachment: ImageView = row.findViewById(R.id.imgAttachment)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.row_message, parent, false)
        val h = (view.tag as? Holder) ?: Holder(view as LinearLayout).also { view.tag = it }
        val m = items[position]

        // Fotograf mesajlarinda gorsel var, metin dosya adi.
        val thumb = m.attachment?.let { thumbnail(it) }
        h.imgAttachment.visibility = if (thumb != null) View.VISIBLE else View.GONE
        h.imgAttachment.setImageBitmap(thumb)
        h.txtBody.text = m.text
        h.txtBody.textSize = if (thumb != null) 12f else 16f
        h.txtMeta.text = metaFor(m)

        if (m.outgoing) {
            // Giden: blok sağa yaslanır, kırmızı çizgi sağ kenarda.
            h.row.setPadding(yanBoslukPx, dikeyBoslukPx, kenarBoslukPx, dikeyBoslukPx)
            h.body.gravity = Gravity.END
            h.ruleStart.visibility = View.INVISIBLE
            h.ruleEnd.visibility = View.VISIBLE
            h.ruleEnd.setRule(styleFor(m.state), renkLazer)
        } else {
            h.row.setPadding(kenarBoslukPx, dikeyBoslukPx, yanBoslukPx, dikeyBoslukPx)
            h.body.gravity = Gravity.START
            h.ruleStart.visibility = View.VISIBLE
            h.ruleEnd.visibility = View.INVISIBLE
            h.ruleStart.setRule(TransmissionRule.Style.FADED, renkMetin)
        }
        return view
    }

    /**
     * Kucultulmus onizleme. Tam cozunurluklu bir fotografi listede cozmek
     * bellegi patlatirdi; kaynak yalnizca olculup uygun oranda ornekleniyor.
     */
    private fun thumbnail(source: String): Bitmap? {
        thumbs[source]?.let { return it }
        if (thumbs.containsKey(source)) return null

        val bmp = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val target = 400
            var sample = 1
            while (bounds.outWidth / sample > target || bounds.outHeight / sample > target) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            openStream(source)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
        thumbs[source] = bmp
        return bmp
    }

    private fun openStream(source: String) = runCatching {
        if (source.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(source))
        } else {
            java.io.File(source).takeIf { it.exists() }?.inputStream()
        }
    }.getOrNull()

    private fun styleFor(state: ChatMessage.State): TransmissionRule.Style = when (state) {
        ChatMessage.State.QUEUED -> TransmissionRule.Style.DASHED
        ChatMessage.State.SENT -> TransmissionRule.Style.FADED
        else -> TransmissionRule.Style.SOLID
    }

    /** Bugünün mesajlarında yalnızca saat; eski mesajlarda tarih de eklenir. */
    private fun metaFor(m: ChatMessage): String {
        val date = Date(m.timestamp)
        val stamp = if (isToday(m.timestamp)) {
            timeFormat.format(date)
        } else {
            "${dateFormat.format(date)} ${timeFormat.format(date)}"
        }
        if (!m.outgoing) return stamp
        val durum = when (m.state) {
            ChatMessage.State.QUEUED -> context.getString(R.string.msg_queued)
            ChatMessage.State.SENT -> context.getString(R.string.msg_sent)
            else -> context.getString(R.string.msg_delivered)
        }
        return "$stamp · $durum"
    }

    private fun isToday(timestamp: Long): Boolean {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = timestamp }
        return now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    }
}
