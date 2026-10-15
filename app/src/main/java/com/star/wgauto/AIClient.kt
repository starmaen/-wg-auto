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
    const val PROVIDER_CUSTOM = "custom"

    data class Result(val ok: Boolean, val text: String)

    /** نماذج Gemini المتاحة للاختيار (الأحدث أولاً). */
    val GEMINI_MODELS = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash")
    const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"

    suspend fun ask(
        provider: String,
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        model: String = DEFAULT_GEMINI_MODEL,
        baseUrl: String = ""
    ): Result = withContext(Dispatchers.IO) {
        try {
            when (provider) {
                PROVIDER_GEMINI -> askGemini(apiKey.trim(), model, systemPrompt, userPrompt)
                PROVIDER_CUSTOM -> askOpenAICompatible(baseUrl.trim(), apiKey.trim(), model, systemPrompt, userPrompt)
                else -> askPollinations(systemPrompt, userPrompt)
            }
        } catch (e: Exception) {
            Result(false, "تعذّر الاتصال بخدمة الذكاء: ${(e.message ?: "").take(120)}")
        }
    }

    /** أي خدمة بنمط OpenAI (Muse Spark / Grok / Qwen / OpenRouter...): يقرأ الرابط والموديل كما تكتبهما. */
    private fun askOpenAICompatible(baseUrl: String, apiKey: String, model: String, system: String, user: String): Result {
        if (baseUrl.isEmpty()) return Result(false, "أدخل رابط الـ API الخاص بخدمتك أولاً")
        if (model.isEmpty()) return Result(false, "أدخل اسم الموديل كما يظهر في خدمتك (مثل 3.5-flash)")
        val root = baseUrl.trimEnd('/')
        val url = URL(if (root.endsWith("/chat/completions")) root else "$root/chat/completions")
        val body = JSONObject()
            .put("model", model.trim())
            .put("temperature", 0.3)
            .put("max_tokens", 600)
            .put(
                "messages", org.json.JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            ).toString()
        val c = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 25000; readTimeout = 60000
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotEmpty()) setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true
        }
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val raw = ((if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: "")
        c.disconnect()
        if (code !in 200..299)
            return Result(false, "الخدمة ردت بخطأ $code: ${raw.take(200)}")
        return try {
            val text = JSONObject(raw)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "")?.trim() ?: ""
            if (text.isEmpty()) Result(false, "رد فارغ من الخدمة") else Result(true, text)
        } catch (e: Exception) {
            Result(false, "تعذّر فهم رد الخدمة")
        }
    }

    private fun askGemini(apiKey: String, model: String, system: String, user: String): Result {
        if (apiKey.isEmpty()) return Result(false, "أدخل مفتاح Gemini أولاً (مجاني من aistudio.google.com)")
        val m = model.ifBlank { DEFAULT_GEMINI_MODEL }
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=$apiKey")
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

    /**
     * سؤال التطبيق التلقائي: يطلب JSON صارماً فقط بأسماء موجودة فعلاً
     * ليطبقها التطبيق حرفياً دون تدخل يدوي.
     */
    fun buildApplyPrompt(app: WgApp): String? {
        val cfgs = try { app.store.configs.value.filter { it.enabled } } catch (e: Exception) { emptyList() }
        val dns = try { app.store.dns.value.filter { it.enabled } } catch (e: Exception) { emptyList() }
        if (cfgs.isEmpty() || dns.isEmpty()) return null
        val sb = StringBuilder()
        sb.appendLine("أجب بسطر JSON واحد فقط بلا أي شرح، بهذا الشكل تماماً:")
        sb.appendLine("""{"config":"<اسم الكونفيج حرفياً>","dns":"<اسم الـDNS حرفياً>","mtu":1420}""")
        sb.appendLine("المعادلة: الزمن 50% + الفقد 30% + التذبذب 20% (أقل = أفضل). MTU رقم بين 1000 و1500.")
        sb.appendLine("كونفيجات WireGuard المتاحة (الاسم | زمن | فقد | تذبذب | خروج):")
        cfgs.take(12).forEach { c ->
            val r = try { app.engine.report.value.rows.firstOrNull { it.id == c.id } } catch (e: Exception) { null }
            sb.appendLine("- ${c.name} | ${r?.latencyMs ?: -1}ms | فقد ${r?.loss ?: -1}% | تذبذب ${r?.jitter ?: -1} | ${r?.exitLoc ?: "غير مفحوص"}")
        }
        sb.appendLine("خوادم DNS المتاحة (الاسم | الزمن المقاس):")
        dns.take(10).forEach { d ->
            val m = try { app.engine.dnsResults.value[d.id]?.best() } catch (e: Exception) { null }
            sb.appendLine("- ${d.name} | ${m?.let { "${it.second.median}ms فقد ${it.second.loss}%" } ?: "غير مقاس"}")
        }
        val mtu = try { app.engine.netInfo.value.direct?.suggestedMtu ?: 0 } catch (e: Exception) { 0 }
        if (mtu > 0) sb.appendLine("MTU المقترح من فحص الشبكة: $mtu")
        return sb.toString()
    }

    data class Applied(val configName: String?, val dnsName: String?, val mtu: Int?)

    /** يطبق نتيجة JSON على الإعدادات (وضع يدوي ليثبت الاختيار). يعيد وصف ما طُبق. */
    fun applyResult(app: WgApp, jsonText: String): Result {
        val o = try {
            val s = jsonText.trim().substringAfter('{', "").substringBeforeLast('}', "")
            if (s.isEmpty()) return Result(false, "رد غير مفهوم من الذكاء")
            JSONObject("{$s}")
        } catch (e: Exception) {
            return Result(false, "تعذّر فهم رد الذكاء كـ JSON")
        }
        val cfgName = o.optString("config", "").takeIf { it.isNotEmpty() }
        val dnsName = o.optString("dns", "").takeIf { it.isNotEmpty() }
        val mtu = o.optInt("mtu", -1).takeIf { it in 1000..1500 }
        val cfg = cfgName?.let { n -> app.store.configs.value.firstOrNull { it.name == n } }
            ?: cfgName?.let { n -> app.store.configs.value.firstOrNull { it.name.contains(n, true) || n.contains(it.name, true) } }
        val dns = dnsName?.let { n -> app.store.dns.value.firstOrNull { it.name == n } }
            ?: dnsName?.let { n -> app.store.dns.value.firstOrNull { it.name.contains(n, true) || n.contains(it.name, true) } }
        if (cfg == null && dns == null && mtu == null)
            return Result(false, "لم يطابق رد الذكاء أي عنصر لديك — جرّب السؤال اليدوي")
        app.store.updateSettings {
            var s = it
            if (cfg != null) s = s.copy(autoConfig = false, selConfigId = cfg.id)
            if (dns != null) s = s.copy(autoDns = false, selDnsId = dns.id)
            if (mtu != null) s = s.copy(autoMtu = false, selMtu = mtu)
            s
        }
        // ملاحظة: اختيار OpenVPN يبقى بضغطة من تبويبه (يحتاج إذن VPN لحظياً)
        val done = listOfNotNull(
            cfg?.let { "الكونفيج: ${it.name}" },
            dns?.let { "DNS: ${it.name}" },
            mtu?.let { "MTU: $it" }
        ).joinToString(" • ")
        return Result(true, "✔ طُبق تلقائياً (وضع يدوي ليثبت): $done — اضغط تشغيل في الرئيسية")
    }

    fun systemPrompt(): String =
        "أنت مستشار شبكات VPN داخل تطبيق أندرويد. تجيب بالعربية فقط، باختصار شديد، " +
            "وتختار دائماً بالأرقام المعطاة لا بالتخمين. إن غابت القياسات قل: افحص أولاً ثم اسألني."
}
