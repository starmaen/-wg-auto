package com.star.wgauto

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

class Store(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val sp = ctx.getSharedPreferences("wgauto", Context.MODE_PRIVATE)

    val configs = MutableStateFlow(loadConfigs())
    val dns = MutableStateFlow(loadDns())
    val settings = MutableStateFlow(loadSettings())
    val ovpn = MutableStateFlow(loadOvpn())
    val ovpnUpdatedAt = MutableStateFlow(sp.getLong("ovpnUpdatedAt", 0L))

    // ---------- configs ----------
    fun updateConfigs(f: (List<WgConfig>) -> List<WgConfig>) {
        configs.value = f(configs.value)
        val a = JSONArray()
        configs.value.forEach {
            val o = JSONObject().put("id", it.id).put("name", it.name)
                .put("text", it.text).put("enabled", it.enabled).put("lastCountry", it.lastCountry)
                .put("expiresAt", it.expiresAt)
            it.lastCountryUseful?.let { u -> o.put("lastCountryUseful", u) }
            a.put(o)
        }
        sp.edit().putString("configs", a.toString()).apply()
    }

    private fun loadConfigs(): List<WgConfig> {
        val s = sp.getString("configs", null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                WgConfig(
                    o.getString("id"), o.getString("name"), o.getString("text"), o.optBoolean("enabled", true),
                    o.optString("lastCountry", ""),
                    if (o.has("lastCountryUseful")) o.getBoolean("lastCountryUseful") else null,
                    o.optLong("expiresAt", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------- dns ----------
    fun updateDns(f: (List<DnsServer>) -> List<DnsServer>) {
        dns.value = f(dns.value)
        saveDns()
    }

    fun restoreDefaultDns() {
        updateDns { cur ->
            val ids = cur.map { it.id }.toSet()
            cur + Defaults.dns.filter { it.id !in ids }
        }
    }

    private fun saveDns() {
        val a = JSONArray()
        dns.value.forEach {
            a.put(
                JSONObject().put("id", it.id).put("name", it.name).put("primary", it.primary)
                    .put("secondary", it.secondary).put("enabled", it.enabled).put("dot", it.dot)
                    .put("filtered", it.filtered)
            )
        }
        sp.edit().putString("dns", a.toString()).apply()
    }

    private fun loadDns(): List<DnsServer> {
        val s = sp.getString("dns", null) ?: return Defaults.dns
        return try {
            val a = JSONArray(s)
            val defDot = Defaults.dns.associate { d -> d.id to d.dot }
            val defFiltered = Defaults.dns.associate { d -> d.id to d.filtered }
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                val id = o.getString("id")
                DnsServer(
                    id, o.getString("name"), o.getString("primary"),
                    o.optString("secondary", ""), o.optBoolean("enabled", true),
                    o.optString("dot", "").ifEmpty { defDot[id] ?: "" },
                    o.optBoolean("filtered", defFiltered[id] ?: false)
                )
            }
        } catch (e: Exception) {
            Defaults.dns
        }
    }

    // ---------- settings ----------
    fun updateSettings(f: (AppSettings) -> AppSettings) {
        settings.value = f(settings.value)
        val s = settings.value
        val o = JSONObject()
            .put("autoConfig", s.autoConfig).put("autoDns", s.autoDns).put("autoMtu", s.autoMtu)
            .put("selConfigId", s.selConfigId).put("selDnsId", s.selDnsId).put("selMtu", s.selMtu)
            .put("reselectOnNetwork", s.reselectOnNetwork).put("periodMinV2", s.periodMin)
            .put("useRoot", s.useRoot).put("backgroundScan", s.backgroundScan).put("applyRoot", s.applyRoot)
            .put("allowFilteredDns", s.allowFilteredDns).put("killSwitch", s.killSwitch)
            .put("excludeSameCountry", s.excludeSameCountry).put("ovpnProto", s.ovpnProto)
            .put("ovpnAutoReconnect", s.ovpnAutoReconnect)
        sp.edit().putString("settings", o.toString()).apply()
    }

    private fun loadSettings(): AppSettings {
        val s = sp.getString("settings", null) ?: return AppSettings()
        return try {
            val o = JSONObject(s)
            val d = AppSettings()
            AppSettings(
                o.optBoolean("autoConfig", d.autoConfig), o.optBoolean("autoDns", d.autoDns),
                o.optBoolean("autoMtu", d.autoMtu), o.optString("selConfigId", ""),
                o.optString("selDnsId", ""), o.optInt("selMtu", d.selMtu),
                o.optBoolean("reselectOnNetwork", d.reselectOnNetwork),
                o.optInt("periodMinV2", d.periodMin), o.optBoolean("useRoot", d.useRoot),
                o.optBoolean("backgroundScan", d.backgroundScan),
                o.optBoolean("applyRoot", d.applyRoot),
                o.optBoolean("allowFilteredDns", d.allowFilteredDns),
                o.optBoolean("killSwitch", d.killSwitch),
                o.optBoolean("excludeSameCountry", d.excludeSameCountry),
                o.optString("ovpnProto", "auto").takeIf { it in listOf("auto", "udp", "tcp") } ?: "auto",
                o.optBoolean("ovpnAutoReconnect", true)
            )
        } catch (e: Exception) {
            AppSettings()
        }
    }

    // ---------- قيم بسيطة مساعدة ----------
    fun prefGet(key: String): String? = sp.getString(key, null)
    fun prefPut(key: String, value: String?) {
        sp.edit().putString(key, value).apply()
    }

    // ---------- سجل الفحص (ملف، يُحذف تلقائياً ما تجاوز 24 ساعة) ----------
    private fun scanLogFile() = java.io.File(appCtx.filesDir, "scanlog.txt")

    @Synchronized
    fun appendScanLog(epoch: Long, line: String) {
        try {
            val f = scanLogFile()
            f.appendText("$epoch|$line\n")
            // سقف الحجم: عند التجاوز نُبقي الأحدث فقط
            if (f.length() > 120_000) {
                val lines = f.readLines().takeLast(400)
                f.writeText(lines.joinToString("\n") + "\n")
            }
        } catch (e: Exception) {
        }
    }

    /** يحمّل السجل بعد حذف ما تجاوز عمره 24 ساعة. */
    fun loadScanLogs(): List<String> {
        return try {
            val f = scanLogFile()
            if (!f.exists()) return emptyList()
            val cutoff = System.currentTimeMillis() - 24L * 3600 * 1000
            val kept = ArrayList<String>()
            val display = ArrayList<String>()
            for (raw in f.readLines()) {
                val i = raw.indexOf('|')
                if (i <= 0) continue
                val ep = raw.substring(0, i).toLongOrNull() ?: continue
                if (ep < cutoff) continue
                kept += raw
                display += raw.substring(i + 1)
            }
            // إعادة كتابة الملف منظفاً (حذف القديم فعلياً)
            try {
                f.writeText(if (kept.isEmpty()) "" else kept.takeLast(500).joinToString("\n") + "\n")
            } catch (e: Exception) {
            }
            display.takeLast(300)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------- سيرفرات OpenVPN (VPNGate) ----------
    // الوصف في SharedPreferences، وملف CSV الخام في filesDir (الكونفيجات كبيرة).

    fun updateOvpn(f: (List<OvpnServer>) -> List<OvpnServer>) {
        ovpn.value = f(ovpn.value)
        val a = JSONArray()
        ovpn.value.forEach {
            a.put(
                JSONObject().put("key", it.key).put("host", it.host).put("ip", it.ip)
                    .put("country", it.country).put("sessions", it.sessions)
                    .put("speedMbps", it.speedMbps).put("pingAdvertised", it.pingAdvertised)
                    .put("lat", it.lat).put("jitter", it.jitter).put("loss", it.loss)
                    .put("via", it.via).put("testedAt", it.testedAt).put("lastSeen", it.lastSeen)
            )
        }
        sp.edit().putString("ovpn", a.toString()).apply()
    }

    private fun loadOvpn(): List<OvpnServer> {
        val s = sp.getString("ovpn", null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                OvpnServer(
                    o.optString("key", o.optString("host", "")),
                    o.optString("host", ""), o.optString("ip", ""), o.optString("country", ""),
                    o.optInt("sessions", 0), o.optDouble("speedMbps", 0.0),
                    o.optInt("pingAdvertised", -1), o.optInt("lat", -1),
                    o.optInt("jitter", 0), o.optInt("loss", 100),
                    o.optString("via", ""), o.optLong("testedAt", 0L),
                    // الكاش القديم بلا lastSeen: نعتبره حديثاً ولا نحذفه من أول تحديث
                    o.optLong("lastSeen", System.currentTimeMillis())
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setOvpnUpdatedAt(t: Long) {
        ovpnUpdatedAt.value = t
        sp.edit().putLong("ovpnUpdatedAt", t).apply()
    }

    fun saveVpnGateCsv(csv: String) {
        try {
            java.io.File(appCtx.filesDir, VpnGate.CACHE_FILE).writeText(csv)
        } catch (e: Exception) {
        }
    }

    fun loadVpnGateCsv(): String? = try {
        java.io.File(appCtx.filesDir, VpnGate.CACHE_FILE).takeIf { it.exists() }?.readText()
    } catch (e: Exception) {
        null
    }

    /** كونفيج .ovpn الكامل لسيرفر من الكاش الخام (يُستخدم عند الاتصال فقط). */
    fun ovpnConfigB64(key: String): String? {
        val csv = loadVpnGateCsv() ?: return null
        for (s in VpnGate.parse(csv)) {
            if (s.key == key) return s.configB64
        }
        return null
    }
}
