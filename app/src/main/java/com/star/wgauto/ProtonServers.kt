package com.star.wgauto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * متصفح خوادم ProtonVPN المجانية (Tier 0) — أوفلاين جزئياً مع تحديث عند توفر إنترنت.
 *
 * ⚠ صدق تقني: لا يمكن توليد كونفيج WireGuard يعمل لـ Proton بدون حساب مجاني،
 * لأن المفتاح الخاص يُولَّد عبر API موثّق (SRP login) ولا يُعطى مجهولاً مثل WARP.
 * ما نفعله هنا:
 * 1) نجلب قائمة الخوادم المجانية العامة (بدون تسجيل) لنعرض الدولة + الحمل + عنوان الدخول.
 * 2) المستخدم يحمّل ملف .conf من account.protonvpn.com (مجاني) ويستورده بزر الاستيراد الموجود.
 * 3) التطبيق بعد الاستيراد يكشف الدولة تلقائياً ويعرض العلم.
 */
object ProtonServers {
    data class FreeServer(
        val country: String, val name: String, val entryIp: String,
        val domain: String, val load: Int, val tier: Int
    )

    private const val URL1 = "https://api.proton.me/vpn/v1/logicals"
    private const val URL2 = "https://api.protonvpn.ch/vpn/logicals"

    @Volatile
    var cache: List<FreeServer> = emptyList()
        private set
    @Volatile
    var lastError: String = ""
        private set
    @Volatile
    var lastUpdate: Long = 0L
        private set

    suspend fun refresh(network: android.net.Network? = null): List<FreeServer> =
        withContext(Dispatchers.IO) {
            lastError = ""
            val raw = tryFetch(network, URL1) ?: tryFetch(network, URL2)
            if (raw == null) {
                lastError = "تعذّر جلب قائمة Proton (تحقق من الإنترنت)"
                return@withContext cache
            }
            try {
                val servers = parse(raw)
                if (servers.isNotEmpty()) {
                    cache = servers
                    lastUpdate = System.currentTimeMillis()
                } else {
                    lastError = "رد فارغ من Proton"
                }
                cache
            } catch (e: Exception) {
                lastError = "رد غير متوقع: ${e.message?.take(80)}"
                cache
            }
        }

    private fun tryFetch(network: android.net.Network?, u: String): String? = try {
        val url = URL(u)
        val c = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "WG-Auto")
        val code = c.responseCode
        val t = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code in 200..299 && t.isNotEmpty()) t else null
    } catch (e: Exception) {
        null
    }

    internal fun parse(raw: String): List<FreeServer> {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("LogicalServers") ?: root.optJSONArray("servers")
            ?: return emptyList()
        val out = ArrayList<FreeServer>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val tier = o.optInt("Tier", 99)
            val status = o.optInt("Status", 1)
            if (status != 1) continue
            // Tier 0 = مجاني. نحتفظ بالمجاني فقط لتفادي إغراء المدفوع.
            if (tier != 0) continue
            val country = o.optString("ExitCountry", o.optString("Country", ""))
            val name = o.optString("Name", o.optString("Domain", ""))
            val domain = o.optString("Domain", "")
            val load = o.optInt("Load", 100)
            var entryIp = ""
            val servers = o.optJSONArray("Servers")
            if (servers != null && servers.length() > 0) {
                entryIp = servers.getJSONObject(0).optString("EntryIP", "")
            }
            if (entryIp.isEmpty()) entryIp = o.optString("EntryIP", "")
            out += FreeServer(country, name, entryIp, domain, load, tier)
        }
        // الأخف حملاً أولاً، ثم الدولة
        return out.sortedWith(compareBy({ it.load }, { it.country }))
    }
}
