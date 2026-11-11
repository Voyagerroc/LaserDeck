package com.voyagerroc.laserdeck.ptt.update

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sürüm denetimi.
 *
 * Uygulamanın işi tamamen çevrimdışı; internet YALNIZCA "daha yeni bir sürüm
 * var mı?" sorusunu sormak için kullanılır. Giden istek tek bir HTTPS GET'tir,
 * içinde hiçbir kullanıcı verisi, tanımlayıcı ya da istatistik yoktur ve
 * başarısız olursa sessizce vazgeçilir — hat yokken uygulama hiç fark etmez.
 *
 * Kurulumu uygulama YAPMAZ. Kendi APK'sını indirip kuran bir uygulama
 * REQUEST_INSTALL_PACKAGES iznine muhtaç olurdu; Google Play bu izni yalnızca
 * çekirdek işi uygulama kurmak olan mağazalara veriyor. Bunun yerine indirme
 * bağlantısı sisteme devrediliyor, kurulumu Android'in kendi yükleyicisi
 * yapıyor.
 */
class UpdateChecker(private val context: Context) {

    companion object {
        private const val MANIFEST_URL =
            "https://laserdeck-ptt.voyagerroc.workers.dev/surum.json"

        private const val PREFS = "guncelleme"
        private const val KEY_LAST_CHECK = "son_kontrol"
        private const val KEY_DISMISSED = "reddedilen_kod"

        /** Aynı gün defalarca sormaya gerek yok. */
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        private const val TIMEOUT_MS = 6000
        /** Kötü niyetli/bozuk bir yanıt belleği doldurmasın. */
        private const val MAX_BYTES = 8 * 1024
    }

    data class Update(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val currentCode: Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrDefault(Int.MAX_VALUE) // okunamazsa güncelleme önerme

    /**
     * Arka plan iş parçacığından çağrılmalı. Güncelleme yoksa, ağ yoksa,
     * kullanıcı bu sürümü reddettiyse ya da çok yakında kontrol edildiyse
     * null döner.
     */
    fun check(force: Boolean = false): Update? {
        if (!force && !dueForCheck()) return null
        val body = fetch() ?: return null
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

        val update = runCatching {
            val o = JSONObject(body)
            Update(
                versionCode = o.getInt("versionCode"),
                versionName = o.getString("versionName"),
                apkUrl = o.getString("apk"),
                notes = o.optString("notes")
            )
        }.getOrNull() ?: return null

        if (update.versionCode <= currentCode) return null
        if (update.versionCode == prefs.getInt(KEY_DISMISSED, -1)) return null
        // Sadece kendi sunucumuzdan gelen bağlantı kabul edilir: bozuk ya da
        // değiştirilmiş bir yanıt kullanıcıyı yabancı bir dosyaya yollamasın.
        if (!update.apkUrl.startsWith("https://laserdeck-ptt.voyagerroc.workers.dev/")) return null
        return update
    }

    /** Kullanıcı "şimdi değil" derse bu sürüm bir daha sorulmaz. */
    fun dismiss(versionCode: Int) {
        prefs.edit().putInt(KEY_DISMISSED, versionCode).apply()
    }

    private fun dueForCheck(): Boolean =
        System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L) > CHECK_INTERVAL_MS

    private fun fetch(): String? = runCatching {
        val conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            useCaches = false
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            conn.inputStream.use { input ->
                val buf = ByteArray(MAX_BYTES)
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n <= 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8)
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrNull()

    /** Kurulu sürüm okunamadıysa güncelleme önerilmez. */
    fun packageManagerAvailable(): Boolean =
        runCatching { context.packageManager != null }.getOrDefault(false)
}
