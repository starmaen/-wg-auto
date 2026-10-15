package com.star.wgauto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * عميل ذكاء اصطناعي بلا مكتبات خارجية — مفتاحك يبقى في جهازك فقط.
 * - gemini: مفتاح مجاني من Google AI Studio (aistudio.google.com) — الأفضل والأسرع.
 * - pollinations: بلا مفتاح وبلا حساب — يعمل فوراً للجميع.
 */
object AIClient {
    const val PROVIDER_GEMINI = "gemini"
    const val PROVIDER_POLLINATIONS = "pollinations"

    data class Result(val ok: Boolean, val text: String)

    suspend fun ask(
        provider: String,
        apiKey: String,
        systemPrompt: String,
        userPrompt: String
    ): Result = withContext(Dispatchers.IO) {
        try {
            when (provider) {
                PROVIDER_GEMINI -> askGemini(apiKey.trim(), systemPrompt, userPrompt)
                else -> askPollinations(systemPrompt, userPrompt)
            }
        } catch (e: Exception) {
            Result(false, "تعذّر الاتصال بخدمة الذكاء: ${(e.message ?: "").take(120)}")
        }
    }

    private fun askGemini(apiKey: String, system: String, user: String): Result {
        if (apiKey.isEmpty()) return Result(false, "أدخل مفتاح Gemini أولاً (مجاني من aistudio.google.com)")
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", system))))
            .put("contents", org.json.JSONArray().put(JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 600).put("temperature", 0.3))
            .toString()
        val c = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 25000; readTimeout = 40000
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val raw = ((if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: "")
        c.disconnect()
        if (code !in 200..299) {
            val msg = try {
                JSONObject(raw).optJSONObject("error")?.optString("message", "خطأ $code") ?: "خطأ $code"
            } catch (e: Exception) { "خطأ $code" }
            return Result(false, "Gemini: ${msg.take(200)}")
        }
        return try {
            val text = JSONObject(raw)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?.optJSONObject(0)?.optString("text", "")?.trim() ?: ""
            if (text.isEmpty()) Result(false, "رد فارغ من Gemini") else Result(true, text)
        } catch (e: Exception) {
            Result(false, "تعذّر فهم رد Gemini")
        }
    }

    private fun askPollinations(system: String, user: String): Result {
        val prompt = URLEncoder.encode("$system\n\n$user", "UTF-8")
        val url = URL("https://text.pollinations.ai/$prompt")
        val c = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 25000; readTimeout = 60000
            setRequestProperty("User-Agent", "WG-Auto")
        }
        val code = c.responseCode
        val raw = ((if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: "").trim()
        c.disconnect()
        return if (code in 200..299 && raw.isNotEmpty()) Result(true, raw)
        else Result(false, "تعذّرت الخدمة المجانية (HTTP $code) — جرّب Gemini بالمفتاح")
    }

    /**
     * يبني سؤال المستشار من القياسات الفعلية للتطبيق (لا تخمين):
     * أفضل الكونفيجات المفحوصة + أفضل DNS + MTU المقترح + أفضل سيرفر OpenVPN.
     */
    fun buildAdvisorPrompt(app: WgApp): String {
        val sb = StringBuilder()
        sb.appendLine("أجب بالعربية الفصحى المبسطة، مختصراً (6 أسطر كحد أقصى)، بنقاط واضحة.")
        sb.appendLine("اختر لي: أفضل كونفيج WireGuard + أفضل DNS + أفضل MTU + أفضل سيرفر OpenVPN، مع سبب سطر واحد لكل اختيار.")
        sb.appendLine("المعادلة المعتمدة: الزمن 50% + الفقد 30% + التذبذب 20% (أقل = أفضل).")
        sb.appendLine()
        // WireGuard المفحوصة
        val rows = try { app.engine.report.value.rows.filter { it.state == "ok" }.sortedBy { it.score }.take(5) } catch (e: Exception) { emptyList() }
        if (rows.isNotEmpty()) {
            sb.appendLine("كونفيجات WireGuard المفحوصة (الاسم | زمن | فقد | تذبذب | خروج):")
            rows.forEach { r ->
                sb.appendLine("- ${r.name} | ${r.latencyMs}ms | فقد ${r.loss}% | تذبذب ${r.jitter} | ${r.exitLoc.ifEmpty { "؟" }}")
            }
        } else sb.appendLine("لا توجد كونفيجات WireGuard مفحوصة بعد.")
        // DNS
        val dnsRes = try { app.engine.dnsResults.value } catch (e: Exception) { emptyMap() }
        val dnsList = try { app.store.dns.value.filter { it.enabled }.take(8) } catch (e: Exception) { emptyList() }
        if (dnsList.isNotEmpty()) {
            sb.appendLine("خوادم DNS (الاسم | الزمن المقاس):")
            dnsList.forEach { d ->
                val m = dnsRes[d.id]?.best()
                sb.appendLine("- ${d.name} (${d.primary}) | ${m?.let { "${it.second.median}ms فقد ${it.second.loss}%" } ?: "لم يُقس"}")
            }
        }
        // MTU
        val mtu = try { app.engine.netInfo.value.direct?.suggestedMtu ?: 0 } catch (e: Exception) { 0 }
        sb.appendLine(if (mtu > 0) "MTU المقترح من فحص الشبكة: $mtu" else "لا يوجد MTU مقترح بعد.")
        // OpenVPN
        val ovpn = try { app.store.ovpn.value.filter { it.tested && it.loss < 100 }.sortedBy { it.score }.take(5) } catch (e: Exception) { emptyList() }
        if (ovpn.isNotEmpty()) {
            sb.appendLine("سيرفرات OpenVPN المفحوصة (المضيف | الدولة | زمن | فقد | سرعة معلنة):")
            ovpn.forEach { s ->
                sb.appendLine("- ${s.host} | ${s.country} | ${s.lat}ms | فقد ${s.loss}% | ${VpnGate.speedText(s.speedMbps)}")
            }
        } else sb.appendLine("لا توجد سيرفرات OpenVPN مفحوصة بعد.")
        return sb.toString()
    }

    fun systemPrompt(): String =
        "أنت مستشار شبكات VPN داخل تطبيق أندرويد. تجيب بالعربية فقط، باختصار شديد، " +
            "وتختار دائماً بالأرقام المعطاة لا بالتخمين. إن غابت القياسات قل: افحص أولاً ثم اسألني."
}
