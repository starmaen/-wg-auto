package com.star.wgauto

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * نظام ترخيص بسيط ومقصود بلا تعقيد زائد، مع تشديد عملي على أهم نقطتين طلبهما المالك:
 *
 * 1) الأكواد مربوطة بمعرّف الجهاز (HMAC(deviceId|تاريخ الانتهاء)) — كود صالح لجهاز لا يعمل
 *    على جهاز آخر إطلاقاً، فمشاركة كود بين مستخدمين عديمة الفائدة.
 * 2) عبارة دخول المالك تفتح **أي نسخة على أي جهاز فوراً** بلا كود ولا اتصال إنترنت، ومحفوظة
 *    كتجزئة SHA-256 لا كنص صريح، مع قفل مؤقت بعد محاولات فاشلة متكررة يعيق التخمين العشوائي.
 *
 * ما يرفع صعوبة الاختراق هنا:
 *  - المفتاح السري وتجزئة عبارة المالك غير موجودين كنص صريح في الملف (لا يظهران بأمر
 *    `strings` المباشر على الـ APK) بل بصيغة معكوسة XOR تُفكّ عند الحاجة فقط.
 *  - قفل مؤقت (5 محاولات ثم 10 دقائق) لكل من كود التفعيل وعبارة المالك.
 *  - الكود مربوط بجهاز واحد تحديداً (ليس صالحاً عالمياً كعبارة المالك).
 *
 * ⚠ حدّ صادق يجب معرفته: هذا لا يزال تصميماً من طرف واحد (لا خادم)، فمن يفكّك الشيفرة فعلياً
 * (لا مجرد فحص نصوص) ويتتبع منطق HMAC يستطيع نظرياً استخراج المفتاح بعد جهد حقيقي، أو —
 * الأخطر — تعديل الـ APK نفسه ليجعل نتيجة status.valid دائماً true متجاوزاً الفحص كله. لا يمنع
 * هذا التصميم ذلك الاحتمال؛ منعه يحتاج توقيعاً غير متماثل وتحققاً من سلامة التطبيق (خطوة أعقد
 * يمكن إضافتها لاحقاً إن احتجتها). ما تم هنا يرفع الجهد المطلوب من "نسخ نص واحد" إلى "هندسة
 * عكسية فعلية"، وهو أقصى ما يمكن تقديمه دون تعقيد إضافي حقيقي.
 */
object License {
    private const val XOR_KEY = 0x5A

    // "wgauto-star-syria-2026-change-me" بصيغة معكوسة XOR — غيّرها إلى قيمتك الخاصة وحدّث
    // owner_license_tool.py بنفس القيمة الصريحة هناك (الشرح في نهاية هذا الملف كتعليق).
    private val SECRET_ENC = intArrayOf(
        45, 61, 59, 47, 46, 53, 119, 41, 46, 59, 40, 119, 41, 35, 40, 51, 59, 119, 104, 106, 104,
        108, 119, 57, 50, 59, 52, 61, 63, 119, 55, 63
    )

    // تجزئة SHA-256 لعبارة دخول المالك، بصيغة معكوسة XOR أيضاً (النص الصريح غير موجود هنا إطلاقاً)
    private val OWNER_HASH_ENC = intArrayOf(
        60, 60, 60, 99, 110, 110, 57, 111, 109, 105, 60, 104, 98, 110, 107, 56, 110, 104, 109, 56,
        108, 63, 59, 63, 111, 63, 109, 108, 106, 107, 59, 106, 63, 62, 109, 57, 106, 108, 104, 106,
        98, 99, 106, 105, 62, 110, 106, 99, 57, 57, 98, 57, 104, 108, 57, 105, 57, 59, 98, 110, 105,
        60, 60, 98
    )

    private fun deobfuscate(enc: IntArray): String =
        String(ByteArray(enc.size) { i -> (enc[i] xor XOR_KEY).toByte() })

    private val SECRET: String by lazy { deobfuscate(SECRET_ENC) }
    private val OWNER_SECRET_HASH: String by lazy { deobfuscate(OWNER_HASH_ENC) }

    @SuppressLint("HardwareIds")
    fun deviceId(ctx: Context): String {
        val raw = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        return sha256(raw).take(16).uppercase()
    }

    fun formatId(id: String): String = id.chunked(4).joinToString("-")

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun hmac(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun parseCode(code: String): Pair<String, String>? {
        val c = code.trim().uppercase().replace(" ", "")
        val parts = c.split("-")
        if (parts.size != 2 || parts[0].length != 8 || parts[1].length != 8) return null
        return parts[0] to parts[1]
    }

    private fun cleanDeviceId(raw: String) = raw.replace("-", "").replace(" ", "").trim().uppercase()

    /**
     * توليد كود تفعيل لجهاز آخر — متاح داخل التطبيق نفسه لمن سجّل دخول المالك فقط
     * (تُستدعى من شاشة أداة المالك المخفية، لا من شاشة التفعيل العادية).
     * days: 0 أو أقل = صلاحية دائمة عملياً (100 سنة). يعيد الكود وتاريخ الانتهاء (yyyy-MM-dd).
     */
    fun generateCodeForOwner(deviceIdRaw: String, days: Int): Pair<String, String> {
        val deviceId = cleanDeviceId(deviceIdRaw)
        val effDays = if (days <= 0) 36500 else days
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            add(java.util.Calendar.DAY_OF_YEAR, effDays)
        }
        val ymd = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(cal.time)
        val sig = hmac("$deviceId|$ymd").take(8).uppercase()
        val niceDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(cal.time)
        return "$ymd-$sig" to niceDate
    }

    /** يتحقق من كود التفعيل لهذا الجهاز تحديداً. يعيد تاريخ الانتهاء (epoch ms) عند النجاح، أو null. */
    private fun verifyCode(deviceId: String, code: String): Long? {
        val (ymd, sig) = parseCode(code) ?: return null
        val expected = hmac("$deviceId|$ymd").take(8).uppercase()
        if (sig != expected) return null
        return try {
            val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }
            fmt.parse(ymd)?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun verifyOwnerPhrase(phrase: String): Boolean = sha256(phrase.trim()) == OWNER_SECRET_HASH

    private const val PREF_ACTIVATED = "lic_activated"
    private const val PREF_EXPIRY = "lic_expiry"
    private const val PREF_OWNER = "lic_owner"

    private const val MAX_ATTEMPTS = 5
    private const val LOCK_MS = 10L * 60 * 1000

    /** الوقت المتبقي من القفل بالمللي ثانية (0 يعني غير مقفل). */
    private fun lockRemaining(store: Store, key: String): Long {
        val lockUntil = store.prefGet("${key}_lock")?.toLongOrNull() ?: 0L
        val remaining = lockUntil - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0L
    }

    private fun recordFail(store: Store, key: String) {
        val n = (store.prefGet("${key}_fails")?.toIntOrNull() ?: 0) + 1
        store.prefPut("${key}_fails", n.toString())
        if (n >= MAX_ATTEMPTS) {
            store.prefPut("${key}_lock", (System.currentTimeMillis() + LOCK_MS).toString())
            store.prefPut("${key}_fails", "0")
        }
    }

    private fun recordSuccess(store: Store, key: String) {
        store.prefPut("${key}_fails", null)
        store.prefPut("${key}_lock", null)
    }

    fun status(store: Store): Status {
        if (store.prefGet(PREF_OWNER) == "1") return Status(true, true, 0L)
        val activated = store.prefGet(PREF_ACTIVATED) == "1"
        val expiry = store.prefGet(PREF_EXPIRY)?.toLongOrNull() ?: 0L
        val valid = activated && (expiry == 0L || expiry > System.currentTimeMillis())
        return Status(valid, false, expiry)
    }

    /** نتيجة محاولة تفعيل أو دخول: نجاح، خطأ، أو قفل مؤقت (مع الوقت المتبقي بالثواني). */
    sealed class Attempt {
        object Ok : Attempt()
        object Wrong : Attempt()
        data class Locked(val secondsLeft: Long) : Attempt()
    }

    fun activate(store: Store, deviceId: String, code: String): Attempt {
        lockRemaining(store, "code").let { if (it > 0) return Attempt.Locked(it / 1000) }
        val expiry = verifyCode(deviceId, code)
        if (expiry == null) {
            recordFail(store, "code")
            return Attempt.Wrong
        }
        recordSuccess(store, "code")
        // نهاية اليوم المحدد، لا بدايته، حتى يبقى صالحاً طوال يوم الانتهاء نفسه
        val endOfDay = expiry + 24L * 3600 * 1000 - 1
        store.prefPut(PREF_ACTIVATED, "1")
        store.prefPut(PREF_EXPIRY, endOfDay.toString())
        return Attempt.Ok
    }

    fun ownerLogin(store: Store, phrase: String): Attempt {
        lockRemaining(store, "owner").let { if (it > 0) return Attempt.Locked(it / 1000) }
        if (!verifyOwnerPhrase(phrase)) {
            recordFail(store, "owner")
            return Attempt.Wrong
        }
        recordSuccess(store, "owner")
        store.prefPut(PREF_OWNER, "1")
        store.prefPut(PREF_ACTIVATED, "1")
        store.prefPut(PREF_EXPIRY, "0")
        return Attempt.Ok
    }

    fun deactivate(store: Store) {
        store.prefPut(PREF_ACTIVATED, null)
        store.prefPut(PREF_EXPIRY, null)
        store.prefPut(PREF_OWNER, null)
    }

    data class Status(val valid: Boolean, val isOwner: Boolean, val expiryAt: Long)

    // ---------- سجل الأكواد الصادرة (محلي على جهاز المالك فقط) ----------
    data class IssuedCode(val name: String, val deviceId: String, val code: String, val expiry: String, val issuedAt: String)

    private const val PREF_HISTORY = "lic_history"

    fun historyList(store: Store): List<IssuedCode> {
        val raw = store.prefGet(PREF_HISTORY) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                IssuedCode(
                    o.optString("name", ""), o.optString("deviceId", ""),
                    o.optString("code", ""), o.optString("expiry", ""), o.optString("issuedAt", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun historyAdd(store: Store, entry: IssuedCode) {
        val list = (historyList(store) + entry).takeLast(200)   // سقف بسيط يمنع تضخّم التخزين
        val arr = org.json.JSONArray()
        list.forEach {
            arr.put(
                org.json.JSONObject()
                    .put("name", it.name).put("deviceId", it.deviceId).put("code", it.code)
                    .put("expiry", it.expiry).put("issuedAt", it.issuedAt)
            )
        }
        store.prefPut(PREF_HISTORY, arr.toString())
    }
}
