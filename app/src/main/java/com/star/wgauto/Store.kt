package com.star.wgauto

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("wgauto", Context.MODE_PRIVATE)

    val configs = MutableStateFlow(loadConfigs())
    val dns = MutableStateFlow(loadDns())
    val settings = MutableStateFlow(loadSettings())

    // ---------- configs ----------
    fun updateConfigs(f: (List<WgConfig>) -> List<WgConfig>) {
        configs.value = f(configs.value)
        val a = JSONArray()
        configs.value.forEach {
            a.put(
                JSONObject().put("id", it.id).put("name", it.name)
                    .put("text", it.text).put("enabled", it.enabled)
            )
        }
        sp.edit().putString("configs", a.toString()).apply()
    }

    private fun loadConfigs(): List<WgConfig> {
        val s = sp.getString("configs", null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                WgConfig(o.getString("id"), o.getString("name"), o.getString("text"), o.optBoolean("enabled", true))
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
                o.optBoolean("killSwitch", d.killSwitch)
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
}
