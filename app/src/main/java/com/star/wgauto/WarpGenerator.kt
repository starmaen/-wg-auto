package com.star.wgauto

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * توليد كونفيج Cloudflare WARP مجاني تلقائياً: مفتاحان (خاص وعام) عبر X25519 (RFC 7748)
 * محلياً بالكامل، ثم تسجيل جهاز جديد عبر واجهة WARP العامة (نفس ما تستخدمه تطبيقات 1.1.1.1
 * وأدوات مفتوحة المصدر معروفة مثل wgcf وwireguard-warp-generator)، ثم بناء ملف .conf جاهز.
 *
 * ⚠ هذه الواجهة غير موثَّقة رسمياً من Cloudflare وقد تتغيّر صيغتها بمرور الوقت. لم يتيسّر هنا
 * اختبارها فعلياً على شبكة حية (لا يوجد اتصال إنترنت في بيئة التطوير)، فإن فشل التسجيل، افحص
 * `lastError` المعروض في الواجهة — سيظهر فيه نص رد الخادم الفعلي، وأرسله لي لتعديل الحقول.
 */
object WarpGenerator {
    private const val REG_URL = "https://api.cloudflareclient.com/v0a2158/reg"
    private const val CLIENT_VERSION = "a-6.30-2158"

    // ---------------- X25519 (RFC 7748) — حساب مباشر بلا مكتبات خارجية ----------------
    private object X25519 {
        private val P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
        private val A24 = BigInteger.valueOf(121665)

        private fun BigInteger.addm(o: BigInteger) = this.add(o).mod(P)
        private fun BigInteger.subm(o: BigInteger) = this.subtract(o).mod(P)
        private fun BigInteger.mulm(o: BigInteger) = this.multiply(o).mod(P)
        private fun BigInteger.sqm() = this.multiply(this).mod(P)

        fun clamp(k: ByteArray): ByteArray {
            val c = k.copyOf(32)
            c[0] = (c[0].toInt() and 248).toByte()
            c[31] = ((c[31].toInt() and 127) or 64).toByte()
            return c
        }

        private fun decodeLE(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

        private fun encodeLE(n: BigInteger): ByteArray {
            val be = n.mod(P).toByteArray()
            val trimmed = if (be.size > 32) be.copyOfRange(be.size - 32, be.size) else be
            val rev = trimmed.reversedArray()
            val out = ByteArray(32)
            System.arraycopy(rev, 0, out, 0, rev.size)
            return out
        }

        /** يشتق نقطة على المنحنى من عددي (سلّم Montgomery وفق RFC 7748 §5). */
        fun scalarMult(kRaw: ByteArray, uBytes: ByteArray): ByteArray {
            val k = decodeLE(clamp(kRaw))
            val x1 = decodeLE(uBytes).mod(P)
            var x2 = BigInteger.ONE
            var z2 = BigInteger.ZERO
            var x3 = x1
            var z3 = BigInteger.ONE
            var swap = 0
            for (t in 254 downTo 0) {
                val kt = if (k.testBit(t)) 1 else 0
                swap = swap xor kt
                if (swap == 1) {
                    val tx = x2; x2 = x3; x3 = tx
                    val tz = z2; z2 = z3; z3 = tz
                }
                swap = kt

                val a = x2.addm(z2)
                val aa = a.sqm()
                val b = x2.subm(z2)
                val bb = b.sqm()
                val e = aa.subm(bb)
                val c = x3.addm(z3)
                val d = x3.subm(z3)
                val da = d.mulm(a)
                val cb = c.mulm(b)
                x3 = da.addm(cb).sqm()
                z3 = x1.mulm(da.subm(cb).sqm())
                x2 = aa.mulm(bb)
                z2 = e.mulm(aa.addm(A24.mulm(e)))
            }
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            val zInv = z2.modPow(P.subtract(BigInteger.TWO), P)
            return encodeLE(x2.mulm(zInv))
        }

        private val BASE = ByteArray(32).also { it[0] = 9 }

        fun generateKeyPair(): Pair<ByteArray, ByteArray> {
            val priv = clamp(ByteArray(32).also { SecureRandom().nextBytes(it) })
            val pub = scalarMult(priv, BASE)
            return priv to pub
        }
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    class WarpResult(val configText: String) 

    /** يسجّل جهازاً جديداً في WARP ويبني نص كونفيج WireGuard جاهزاً. يرمي استثناءً برسالة واضحة عند الفشل. */
    fun register(): WarpResult {
        val (priv, pub) = X25519.generateKeyPair()
        val privB64 = b64(priv)
        val pubB64 = b64(pub)

        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())

        val body = JSONObject().apply {
            put("key", pubB64)
            put("install_id", "")
            put("fcm_token", "")
            put("tos", tos)
            put("type", "Android")
            put("locale", "en_US")
        }.toString()

        val conn = (URL(REG_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "okhttp/3.12.1")
            setRequestProperty("CF-Client-Version", CLIENT_VERSION)
        }
        conn.outputStream.use { it.write(body.toByteArray()) }

        val code = conn.responseCode
        val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()

        if (code !in 200..299) {
            throw IllegalStateException("رفض الخادم التسجيل (HTTP $code): ${raw.take(300)}")
        }

        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw IllegalStateException("رد غير متوقع من خادم WARP: ${raw.take(300)}")
        }

        val result = json.optJSONObject("result") ?: json   // بعض الإصدارات تُرجع الحقول مباشرة بلا غلاف result
        val config = result.optJSONObject("config")
            ?: throw IllegalStateException("لا يحوي الرد بيانات كونفيج: ${raw.take(300)}")

        val iface = config.optJSONObject("interface")
        val addr4 = iface?.optJSONObject("addresses")?.optString("v4")
        val addr6 = iface?.optJSONObject("addresses")?.optString("v6")
        val peers = config.optJSONArray("peers")
        val peer = peers?.optJSONObject(0)
            ?: throw IllegalStateException("لا يحوي الرد عنوان خادم WARP: ${raw.take(300)}")
        val peerPub = peer.optString("public_key")
        val endpointHost = peer.optJSONObject("endpoint")?.optString("host")
            ?: peer.optString("endpoint")

        if (peerPub.isEmpty() || endpointHost.isEmpty()) {
            throw IllegalStateException("بيانات الخادم ناقصة في الرد: ${raw.take(300)}")
        }

        val addresses = listOfNotNull(
            addr4?.takeIf { it.isNotEmpty() }?.let { "$it/32" },
            addr6?.takeIf { it.isNotEmpty() }?.let { "$it/128" }
        )
        if (addresses.isEmpty()) throw IllegalStateException("لا يحوي الرد عنوان الواجهة: ${raw.take(300)}")

        // بلا أسطر DNS أو MTU: التطبيق يديرهما تلقائياً بعد الإضافة، فتركهما هنا يسبّب تعارضاً
        val conf = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $privB64")
            appendLine("Address = ${addresses.joinToString(", ")}")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $peerPub")
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            appendLine("Endpoint = $endpointHost")
        }
        return WarpResult(conf)
    }
}
