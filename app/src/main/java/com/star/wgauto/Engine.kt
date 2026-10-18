package com.star.wgauto

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * محرك الاختيار التلقائي.
 *
 * مبادئ التصميم:
 * 1) لا نُعلن "متصل" إلا بعد دليل: واجهة VPN موجودة فعلاً، ومرور البيانات مربوط بها،
 *    والـ IP الخارجي تغيّر عن الـ IP المباشر.
 * 2) الفحص في الخلفية بأنفاق تجريبية خاصة بهذا التطبيق (IncludedApplications) فلا ينقطع اتصال بقية التطبيقات.
 * 3) القياس بمعايير الجودة المعتمدة: الوسيط والتذبذب والفقد، لا زمن عينة واحدة.
 * 4) لا يُطبَّق أي تغيير على DNS النظام دون قياس مقارن، ويُتراجع عنه إن تدهور الحل.
 */
class Engine(ctx: Context, private val store: Store) {

    private class Result(
        val cfg: WgConfig, val mtu: Int, val pathMtu: Int, val dns: DnsServer?,
        val dnsRows: List<DnsRow>, val stat: Stat, val mbps: Double?, val score: Double,
        val exit: ExitInfo?, val mtuRows: List<MtuRow>
    )

    private class LastBest(val cfgId: String, val mtu: Int, val pathMtu: Int, val dnsId: String, val time: Long)
    private class Verified(
        val vpn: NetUtil.Info, val exit: ExitInfo?, val lat: Int,
        val mtu: Int, val dnsIps: List<String>, val diag: String
    )

    private class Probe(
        val vpn: NetUtil.Info?, val mtu: Int, val lat: Int, val exit: ExitInfo?, val note: String,
        val sameCountry: Boolean = false, val jitter: Int = 0, val loss: Int = 0
    )

    /** نتيجة الامتحان لكونفيج واحد: كل البيانات اللازمة للترتيب بالمعادلة الموحدة. */
    private class ExamResult(
        val cfg: WgConfig, val mtu: Int, val lat: Int, val jitter: Int, val loss: Int,
        val exit: ExitInfo?, val sameCountry: Boolean
    ) {
        val examScore: Double get() = Exam.score(lat, jitter, loss)
    }
    private class SideScan(val info: SideInfo, val measures: List<Pair<DnsServer, DnsMeasure>>, val sys: Stat?)

    private val appCtx = ctx.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val backend = GoBackend(appCtx)
    private val cm = appCtx.getSystemService(ConnectivityManager::class.java)
    private val tunnel = object : Tunnel {
        override fun getName(): String = "wgauto"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    val status = MutableStateFlow(Status())
    val logs = MutableStateFlow<List<String>>(emptyList())
    val dnsResults = MutableStateFlow<Map<String, DnsMeasure>>(emptyMap())
    val netInfo = MutableStateFlow(NetInfo())
    val configResults = MutableStateFlow<Map<String, String>>(emptyMap())
    val warpStatus = MutableStateFlow("")
    val report = MutableStateFlow(Report())

    private var runJob: Job? = null
    private var netJob: Job? = null
    private var scanJob: Job? = null
    private var healthFails = 0
    private var lastNetLabel = ""

    /** حالة قاطع الطوارئ الفعلية (مستقلة عن الـ Status لتفادي تسرّبها عند إعادة ضبط الحالة). */
    @Volatile
    private var killSwitchApplied = false

    /** وقت آخر تغيير أجراه التطبيق نفسه على النفق، لتجاهل أحداث الشبكة الناتجة عنه. */
    @Volatile
    private var lastOwnChange = 0L

    /** لا تبدأ إعادة اختيار تلقائية قبل هذا الوقت (يمنع الحلقات). */
    @Volatile
    private var cooldownUntil = 0L

    /** يمنع محاولة تغيير DNS النظام أكثر من مرة كل 30 دقيقة (كل محاولة تفتح نافذة اختبار قصيرة). */
    @Volatile
    private var dnsChangeCooldownUntil = 0L

    /** الوضع اليدوي: المستخدم وحده يشغّل ويفصل — ممنوع أي لمس تلقائي للنفق. */
    private fun isManual() = !store.settings.value.autoConfig

    /** عدّاد الفشل المتتالي + موعد إعادة المحاولة (backoff بدل الحجب الثابت). */
    @Volatile
    private var failCount = 0
    @Volatile
    private var nextRetryAt = 0L

    /** بداية آخر عملية مشغولة — لكشف التجمد وإعادة الضبط. */
    @Volatile
    private var busySince = 0L

    /** آخر فحص ناتج عن حدث شبكة — فاصل 10 ثوانٍ يمنع الحلقات دون قتل الاستجابة. */
    @Volatile
    private var lastNetScanAt = 0L

    private fun retryDelayMs(): Long =
        (30_000L shl failCount.coerceIn(0, 3)).coerceAtMost(240_000L)

    private fun now() = System.currentTimeMillis()

    init {
        // استعادة سجل اليوم الماضي فقط (الأقدم حُذف تلقائياً عند التحميل)
        try {
            logs.value = store.loadScanLogs()
        } catch (e: Exception) {
        }
    }

    fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$t  $msg"
        logs.update { (it + line).takeLast(300) }
        try {
            store.appendScanLog(now(), line)
        } catch (e: Exception) {
        }
    }

    private fun setResult(id: String, text: String) {
        configResults.update { it + (id to text) }
    }

    private fun setProgress(msg: String) {
        report.update { it.copy(progress = msg) }
        status.update { it.copy(message = msg) }
    }

    private fun updateRow(id: String, f: (ConfigRow) -> ConfigRow) {
        report.update { r -> r.copy(rows = r.rows.map { if (it.id == id) f(it) else it }) }
    }

    private fun tunnelIsUp(): Boolean = try {
        backend.getState(tunnel) == Tunnel.State.UP
    } catch (e: Exception) {
        false
    }

    private fun vpnInfo(): NetUtil.Info? = NetUtil.all(cm).lastOrNull { it.isVpn }

    private fun networkLabel(): String = NetUtil.underlying(NetUtil.all(cm))?.label ?: "غير متصل"

    // ---------- إدارة الكونفيجات ----------
    /** يعيد null عند النجاح، أو رسالة الخطأ. */
    fun addConfig(name: String, text: String): String? {
        // تنظيف استيراد (Proton وغيره): BOM + مفاتيح غريبة + DNS داخلي + Keepalive
        // فلا يضطر المستخدم لتغيير الامتداد أو حذف أسطر يدوياً.
        val clean = ConfigText.normalizeForImport(text)
        if (!clean.contains("[Interface]", true) || !clean.contains("[Peer]", true)) {
            return "كونفيج غير صالح: لا يحوي [Interface] و [Peer] — تأكد أن الملف WireGuard وليس OpenVPN"
        }
        try {
            Config.parse(ByteArrayInputStream(clean.toByteArray()))
        } catch (e: Exception) {
            val msg = e.message ?: e.cause?.message ?: e.javaClass.simpleName
            return "كونفيج غير صالح: $msg"
        }
        store.updateConfigs { list ->
            val base = name.ifBlank { "config" }
            var n = base
            var i = 2
            while (list.any { it.name == n }) {
                n = "$base ($i)"
                i++
            }
            // كونفيجات VPNBook تنتهي بعد 7 أيام — تُوسم تلقائياً عند الاستيراد
            val exp = if (VpnBook.isVpnBook(clean, n)) {
                log("📖 كونفيج VPNBook — صالح 7 أيام من الآن")
                now() + VpnBook.EXPIRY_DAYS * 24L * 3600 * 1000
            } else 0L
            list + WgConfig(name = n, text = clean, expiresAt = exp)
        }
        return null
    }

    /** يعيد تنظيف كل الكونفيجات المحفوظة (Proton القديمة): يحذف DNS الداخلي والأسطر الغريبة ويضيف Keepalive. */
    fun repairAllConfigs(): Pair<Int, Int> {
        var ok = 0
        var fail = 0
        var firstErr = ""
        store.updateConfigs { list ->
            list.map { c ->
                try {
                    var clean = ConfigText.normalizeForImport(c.text)
                    // احتياط: إن فشل المنظف الصارم، جرّب التنظيف الخفيف القديم قبل إعلان الفشل
                    try {
                        Config.parse(ByteArrayInputStream(clean.toByteArray()))
                    } catch (e1: Exception) {
                        val legacy = ConfigText.sanitize(c.text)
                        Config.parse(ByteArrayInputStream(legacy.toByteArray()))
                        clean = legacy
                    }
                    Config.parse(ByteArrayInputStream(clean.toByteArray()))
                    if (clean != c.text) ok++
                    c.copy(text = clean)
                } catch (e: Exception) {
                    fail++
                    if (firstErr.isEmpty()) firstErr = e.message?.take(100) ?: e.javaClass.simpleName
                    c
                }
            }
        }
        // تنظيف أسماء .conf.txt المزدوجة من تحميل المتصفح
        fun stripCI(s: String, suf: String): String =
            if (s.endsWith(suf, ignoreCase = true)) s.substring(0, s.length - suf.length) else s
        store.updateConfigs { list ->
            list.map { c ->
                var n = c.name
                repeat(3) {
                    n = stripCI(stripCI(stripCI(stripCI(n, ".txt"), ".conf"), ".ovpn"), ".cfg").trim()
                }
                if (n.isEmpty()) n = c.name
                // إزالة تكرار اللاحقة داخل الاسم
                c.copy(name = n)
            }
        }
        log("إصلاح الكل: عُولج $ok كونفيج • فشل $fail" + if (firstErr.isNotEmpty()) " • أول خطأ: $firstErr" else "")
        return ok to fail
    }

    /** يسجّل حساب Cloudflare WARP مجانياً محلياً ويضيف الكونفيج الناتج تلقائياً. */
    fun generateWarp() {
        warpStatus.value = "جارٍ التسجيل في WARP…"
        scope.launch {
            mutex.withLock {
                val prevStatus = status.value
                var bootstrapped: WgConfig? = null
                try {
                    // أي نفق VPN نشط (WireGuard أو OpenVPN من تبويب VPN) يصلح للتسجيل عبره:
                    // يجعل Cloudflare يرى IP ذلك النفق بدل IP شبكتك، فيسجَّل WARP على مركز
                    // بيانات أجنبي. (سابقاً كان يُشترط نفق WireGuard فقط فكان يتجاهل OpenVPN الأجنبي).
                    var activeVpn = vpnInfo()

                    // لا يوجد نفق يعمل حالياً: جرّب تشغيل كونفيج أجنبي مثبت مؤقتاً فقط لتسجيل WARP
                    // عبره، ثم أعِد الحالة كما كانت. الأولوية لكونفيج ثبت فعلياً أنه أجنبي ومفيد،
                    // ثم أي أجنبي معروف، ثم أول مفعّل — لأن التسجيل المباشر من شبكتك يعطي PoP محلياً.
                    if (activeVpn == null) {
                        val enabled = store.configs.value.filter { it.enabled && it.name != "WARP" && !it.name.startsWith("WARP-") }
                        val candidate = enabled.firstOrNull { it.lastCountryUseful == true && it.lastCountry.isNotBlank() && !it.lastCountry.equals("SY", true) }
                            ?: enabled.firstOrNull { it.lastCountry.isNotBlank() && !it.lastCountry.equals("SY", true) }
                            ?: enabled.firstOrNull()
                        if (candidate != null) {
                            warpStatus.value = "لا يوجد نفق نشط — تجربة ${candidate.name} مؤقتاً لتوليد WARP عبره…"
                            val under0 = NetUtil.underlying(NetUtil.all(cm))
                            val direct0 = under0?.let { Probes.exitInfo(it.network, fast = true) }
                            // لا نترك DNS فارغاً هنا (السبب الفعلي لفشل حلّ اسم خادم WARP على
                            // كونفيجات بلا سطر DNS خاص بها) — نستخدم نفس DNS الذي يختاره التطبيق
                            // تلقائياً لبقية الاتصالات (أفضل خادم مفعَّل غير مُرشِّح)، لا رقماً
                            // منفصلاً، حتى يبقى الاختيار من مصدر واحد متّسق. 1.1.1.1 احتياط أخير
                            // فقط إن لم يبقَ أي خادم DNS مفعَّل عند المستخدم.
                            val bootDns = store.dns.value.firstOrNull { it.enabled && !it.filtered }
                                ?.ips()?.takeIf { it.isNotEmpty() } ?: listOf("1.1.1.1", "1.0.0.1")
                            val ver0 = connectAndVerify(candidate, null, bootDns, direct0)
                            if (ver0 != null) {
                                bootstrapped = candidate
                                activeVpn = ver0.vpn
                                warpStatus.value = "جارٍ التسجيل في WARP عبر ${candidate.name}…"
                            } else {
                                warpStatus.value = "تعذّر تشغيل ${candidate.name} مؤقتاً — سيُسجَّل WARP مباشرة (قد يعطي IP محلياً)"
                            }
                        }
                    } else {
                        warpStatus.value = "جارٍ التسجيل في WARP عبر النفق النشط…"
                    }

                    val r = WarpGenerator.register(activeVpn?.network)

                    val base = "WARP"
                    var n = base
                    var i = 2
                    while (store.configs.value.any { it.name == n }) {
                        n = "$base-$i"
                        i++
                    }
                    // نولّد أولاً بالنص الافتراضي ثم نختبر بدائل Endpoint ونحفظ الأفضل أجنبياً
                    var bestText = r.configText
                    var bestNote = ""
                    var bestProbe: Probe? = null
                    val under = NetUtil.underlying(NetUtil.all(cm))
                    val direct = under?.let { Probes.exitInfo(it.network, fast = true) }
                    val directCc = direct?.loc ?: ""
                    // التثبيت على دولة واحدة (ألمانيا/فرنسا/هولندا) عند الحاجة — فارغ = أي أجنبي غير سوري
                    val pin = store.settings.value.warpCountry.uppercase().take(2)
                    val pinLabel = if (pin.isNotBlank()) Country.label(pin) else "أي دولة أجنبية (غير سوريا)"
                    warpStatus.value = "جارٍ تجربة ${WarpGenerator.variants(r).size} عناوين WARP — المطلوب: خروج $pinLabel…"
                    // كونفيج مؤقت للفحص (لا نحفظ كل البدائل، فقط الأفضل)
                    val tmpCfg = WgConfig(name = "tmp-warp-probe", text = r.configText)
                    val endpoints = WarpGenerator.variants(r, max = 6)
                    // نتيجة كل بديل: زمن>0 = نفق حي، -1 = فاشل — تُحفظ كذاكرة إصلاح ذاتي لاحقاً
                    val probeRes = LinkedHashMap<String, Int>()
                    var tried = 0
                    var foundForeign = false
                    // جولتان: مفاتيح WARP الجديدة تحتاج 10-60 ثانية لتنتشر على كل نقاط
                    // Cloudflare، فالفشل الفوري لا يعني موت العنوان — ننتظر ونعيد.
                    for (round in 0..1) {
                        if (round == 1) {
                            if (bestProbe?.vpn != null && foundForeign) break
                            if (bestProbe?.vpn != null) break // يعمل محلياً — لا داعي لجولة ثانية
                            warpStatus.value = "انتظار انتشار مفتاح WARP في شبكة Cloudflare (20 ثانية)…"
                            log("WARP: لا TCP في الجولة الأولى — انتظار انتشار المفتاح ثم إعادة")
                            delay(20000)
                        }
                        for (ep in endpoints) {
                            if (foundForeign) break
                            tried++
                            warpStatus.value = "تجربة WARP ($tried): $ep" +
                                if (round == 1) " (إعادة بعد انتشار المفتاح)" else ""
                            val t = tmpCfg.copy(text = r.withEndpoint(ep))
                            val p = probeTunnel(t, listOf(null, 1280), emptyList(), direct)
                            probeRes[ep] = if (p.vpn == null) -1 else p.lat
                            if (p.vpn == null) {
                                log("WARP $ep: ✗ ${p.note}")
                                continue
                            }
                            // TCP يمر لكن بلا خروج مؤكد: مقبول كاحتياط، ونواصل بحثاً عن خروج مؤكد
                            if (p.exit == null) {
                                if (bestProbe?.vpn == null) {
                                    bestText = r.withEndpoint(ep); bestProbe = p; bestNote = ep
                                }
                                log("WARP $ep: TCP ✔ ${p.lat}ms بلا خروج مؤكد — احتياط")
                                continue
                            }
                            val cc = p.exit.loc.uppercase()
                            // السوري مرفوض دائماً — حتى لو كان مختلفاً عن الشبكة المباشرة
                            val isSy = cc == "SY"
                            val pinnedOk = pin.isNotBlank() && cc == pin
                            val genericForeign = cc.isNotBlank() && !isSy && (directCc.isEmpty() || !cc.equals(directCc, true))
                            val foreign = pinnedOk || (pin.isBlank() && genericForeign)
                            log("WARP $ep: ${p.lat}ms • ${Country.ipLine(p.exit.ip, cc)}" + when {
                                isSy -> " (سوري ✘ مرفوض)"
                                foreign -> " (أجنبي ★)"
                                else -> " (محلي)"
                            })
                            if (foreign) {
                                bestText = r.withEndpoint(ep); bestProbe = p; bestNote = ep
                                foundForeign = true
                                break // وجدنا المطلوب — لا نضيع وقتاً وبيانات أكثر
                            }
                            if (isSy) continue // لا نحفظ السوري احتياطاً أبداً
                            if (bestProbe?.vpn == null || bestProbe?.exit == null) {
                                bestText = r.withEndpoint(ep); bestProbe = p; bestNote = ep
                            } else if (p.lat < bestProbe!!.lat &&
                                (bestProbe!!.exit?.loc.equals(directCc, true) == p.exit.loc.equals(directCc, true))) {
                                bestText = r.withEndpoint(ep); bestProbe = p; bestNote = ep
                            }
                        }
                    }
                    val err = addConfig(n, bestText)
                    if (err != null) {
                        warpStatus.value = "✗ $err"
                        return@withLock
                    }

                    // فحص فوري: هل IP الناتج من دولة مختلفة عن دولتك فعلاً؟ Cloudflare Anycast
                    // يعيد غالباً أقرب مركز بيانات جغرافياً بلا تسجيل عبر نفق آخر، فيكون بلا فائدة.
                    warpStatus.value = "جارٍ التحقق من دولة الخروج…"
                    val cfg = store.configs.value.firstOrNull { it.name == n }
                    // حفظ الدولة فوراً لعرض العلم بجانب IP
                    val p = bestProbe
                    if (cfg != null) {
                        // ذاكرة بدائل Endpoint للإصلاح الذاتي: الأجنبي المُثبت أولاً ثم الآخرون بالأسرع، ثم ولم يجدوا أجنبياً تُخزَّن الناجحة TCP فقط
                        val alts = buildList {
                            if (foundForeign && bestNote.isNotEmpty()) add(bestNote)
                            probeRes.filter { it.value >= 0 }.entries.sortedBy { it.value }.forEach { add(it.key) }
                        }.distinct()
                        if (alts.isNotEmpty()) saveWarpAlts(cfg.id, alts)
                    }
                    if (cfg != null && p?.exit != null) {
                        store.updateConfigs { l -> l.map {
                            if (it.id == cfg.id) it.copy(lastCountry = p.exit.loc,
                                lastCountryUseful = directCc.isEmpty() || !p.exit.loc.equals(directCc, true))
                            else it
                        } }
                    }
                    downSafe()
                    when {
                        p?.vpn == null -> {
                            setResult(cfg?.id ?: "", "✗ ${p?.note ?: "تعذّر الاتصال بعد التوليد"} — الكونفيج محفوظ، جرّب فحصه يدوياً لاحقاً")
                            warpStatus.value = "⚠ أُضيف $n لكن تعذّر التحقق منه الآن (${p?.note ?: "الشبكة مشغولة"}) — فعّله يدوياً بعد دقائق (انتشار المفتاح)"
                        }
                        p.exit == null -> {
                            setResult(cfg?.id ?: "", "○ TCP يمر عبر $bestNote (${p.lat}ms) بلا خروج مؤكد — محفوظ، فعّله يدوياً بعد دقائق")
                            warpStatus.value = "○ أُضيف $n — النفق يمرّر TCP لكن بلا خروج مؤكد بعد (المفتاح ينتشر) — فعّله يدوياً بعد دقائق"
                        }
                        p.exit?.loc.equals("SY", true) || p.sameCountry -> {
                            val syW = p.exit?.loc.equals("SY", true)
                            setResult(cfg?.id ?: "", "⚠ ${Country.label(p.exit?.loc ?: "")} — " + (if (syW) "خروج سوري مرفوض، لا يفيد إطلاقاً" else "نفس دولتك، لا يفيد للحجب") + " لكنه محفوظ للسرعة")
                            warpStatus.value =
                                "⚠ أُضيف $n ${Country.label(p.exit?.loc ?: "")} عبر $bestNote — " + (if (syW) "سوري ✘" else "نفس دولتك") + ". " +
                                    "عند الاتصال بـ $n سيُجرى البحث تلقائياً عن بديل Endpoint يعطي خروج مطابق (ذاكرة البدائل). " +
                                    "والحل الأكيد: اتصل بسيرفر ألماني/فرنسي/هولندي من تبويب VPN أولاً ثم ولّد WARP مجدداً وهو يعمل."
                        }
                        else -> {
                            setResult(cfg?.id ?: "", "✓ ${Country.ipLine(p.exit?.ip ?: "", p.exit?.loc ?: "")} • ${p.lat}ms عبر $bestNote")
                            warpStatus.value = "✔ أُضيف $n — خروج فعلي ${Country.label(p.exit?.loc ?: "")} عبر $bestNote"
                        }
                    }
                } catch (e: Exception) {
                    warpStatus.value = "✗ فشل تسجيل WARP: ${e.message ?: e.javaClass.simpleName}"
                    log("WARP: ${e.message}")
                } finally {
                    // فحص WARP يقطع أي نفق كان عاملاً مؤقتاً — نعيد الاتصال بما كان يعمل قبل ذلك.
                    val prevCfg = prevStatus.activeConfigId?.let { id -> store.configs.value.firstOrNull { it.id == id } }
                    if (prevStatus.connected && prevCfg != null && !tunnelIsUp()) {
                        val prevDns = prevStatus.activeDnsId?.let { id -> store.dns.value.firstOrNull { it.id == id } }
                        val backDirect = if (prevStatus.directIp.isNotEmpty()) ExitInfo(prevStatus.directIp, prevStatus.directLoc, 0) else null
                        val back = connectAndVerify(prevCfg, prevStatus.activeMtu, prevDns?.ips() ?: emptyList(), backDirect)
                        if (back != null) {
                            status.update { it.copy(connected = true, latencyMs = back.lat, message = "متصل ✔") }
                            log("↩ عاد الاتصال بـ ${prevCfg.name} بعد فحص WARP")
                        }
                    }
                }
            }
        }
    }

    // ---------- تشغيل / إيقاف ----------
    fun start() {
        scanJob?.cancel()
        healthFails = 0
        failCount = 0
        nextRetryAt = 0
        cooldownUntil = 0
        busySince = 0
        lastNetLabel = networkLabel()
        status.value = Status(running = true, busy = true, message = "جارٍ الفحص الشامل…")
        log("▶ بدء التشغيل: فحص شامل لكل الكونفيجات المفعّلة")
        if (store.settings.value.killSwitch) {
            scope.launch {
                if (RootTools.killSwitchEnable()) {
                    killSwitchApplied = true
                    status.update { it.copy(killSwitchOn = true) }
                } else {
                    log("✗ تعذّر تفعيل قاطع الطوارئ (يتطلب روت)")
                }
            }
        }
        // زر التشغيل يعني دائماً فحصاً شاملاً كاملاً — لا اختصار لآخر ناجح هنا.
        // (المسار السريع محفوظ لإعادة الاتصال التلقائية بعد الانقطاع فقط).
        reselect("تشغيل", allowFast = false)
    }

    /**
     * إيقاف فوري من واجهة المستخدم — لا ينتظر انتهاء أي فحص شبكة قد يكون عالقاً في استدعاء
     * محظور إلغاؤه (مقبس خام، أمر su، إلخ)، وهذا شائع تحديداً على شبكات الجوال البطيئة. ننزل
     * النفق ونحدّث الحالة فوراً وبشكل متزامن، ثم ننظّف ما تبقى في الخلفية بلا أي انتظار من
     * المستخدم.
     */
    fun stop() {
        down()
        if (killSwitchApplied) {
            RootTools.killSwitchDisable()
            killSwitchApplied = false
        }
        busySince = 0
        failCount = 0
        nextRetryAt = 0
        status.value = Status(running = false, message = "متوقف")
        report.update { it.copy(progress = "", chosenConfigId = "", chosenDnsId = "") }
        log("تم الإيقاف")
        val jobs = listOfNotNull(netJob, runJob, scanJob)
        netJob?.cancel()
        runJob?.cancel()
        scanJob?.cancel()
        scope.launch {
            jobs.forEach { it.cancelAndJoin() }
            mutex.withLock { downSafe() }   // تنظيف نهائي بلا عجلة، بعد أن ظهرت حالة "متوقف" فعلاً
            if (store.settings.value.backgroundScan) scanNetwork("بعد الإيقاف")
        }
    }

    /**
     * إغلاق شامل نهائي: إيقاف النفق، إلغاء قاطع الطوارئ، إيقاف الفحص الدوري في الخلفية،
     * واستعادة DNS الخاص للنظام إلى ما كان عليه قبل أي تدخّل من التطبيق.
     */
    /** إغلاق فوري من الواجهة؛ التنظيف الكامل (الروت، DNS) يتابع في الخلفية بلا انتظار. */
    fun shutdownAll(onDone: () -> Unit) {
        down()
        if (killSwitchApplied || RootTools.killSwitchActive()) {
            RootTools.killSwitchDisable()
            killSwitchApplied = false
        }
        store.updateSettings { it.copy(backgroundScan = false) }
        status.value = Status(running = false, message = "أُغلق التطبيق بالكامل")
        report.value = Report()
        netInfo.value = NetInfo()
        log("إغلاق شامل: أُوقف كل شيء")
        val jobs = listOfNotNull(netJob, runJob, scanJob)
        netJob?.cancel()
        runJob?.cancel()
        scanJob?.cancel()
        onDone()
        scope.launch {
            jobs.forEach { it.cancelAndJoin() }
            mutex.withLock {
                downSafe()
                if (store.prefGet("prevDnsSaved") != null) restoreNow()
                log("اكتمل تنظيف الإغلاق الشامل")
            }
        }
    }

    /** إعادة اختيار كاملة (يدوية أو تلقائية). تلغي أي فحص جارٍ. */
    fun reselect(reason: String, allowFast: Boolean = false) {
        if (!status.value.running) return
        runJob?.cancel()
        runJob = scope.launch {
            mutex.withLock {
                try {
                    selectAndConnect(reason, allowFast)
                } finally {
                    val ok = status.value.connected
                    if (ok) failCount = 0
                    status.update { it.copy(busy = false) }
                    busySince = 0
                    cooldownUntil = now() + if (ok) 20_000 else 30_000
                    nextRetryAt = now() + if (ok) 20_000 else retryDelayMs().also { failCount++ }
                }
            }
        }
    }

    /**
     * إعادة اختيار تلقائية (انقطاع/دورية) — تعمل فقط عند استحقاق الموعد
     * (backoff: 30ث ثم 1د ثم 2د ثم 4د كحد أقصى)، لا بالحجب الثابت.
     */
    fun autoReselect(reason: String) {
        if (isManual()) return   // اليدوي: لا إعادة تلقائية إطلاقاً
        if (status.value.busy || now() < nextRetryAt) return
        reselect(reason, allowFast = true)
    }

    /**
     * حارس التجمد: إن بقيت عملية مشغولة أكثر من 4 دقائق (مستحيل في الظروف
     * الطبيعية — كل الاستدعاءات محدودة المهلة)، تُلغى وتُصفَّر الحالة
     * ليعود زر التشغيل مستجيباً دائماً.
     */
    fun watchdog() {
        val st = status.value
        if (!st.busy || busySince == 0L) return
        if (now() - busySince < 240_000) return
        log("⚠ تجمد الفحص أكثر من 4 دقائق — إعادة ضبط قسرية")
        runJob?.cancel()
        netJob?.cancel()
        scanJob?.cancel()
        busySince = 0
        failCount++
        nextRetryAt = now() + retryDelayMs()
        status.update { it.copy(busy = false, message = "أُعيد الضبط بعد تجمد — اضغط تشغيل مجدداً") }
    }

    /**
     * تفعيل فردي مباشر لكونفيج محدد — مسار مستقل تماماً عن الفحص الشامل:
     * لا يقارن بغيره ولا يرتّب أحداً. من يمرّر TCP يُثبَّت (ولو بتحذير)،
     * والفشل هنا يعني سبباً حقيقياً فقط: انتهاء/إبطال المفتاح أو انقطاع الشبكة.
     */
    fun connectManual(cfgId: String) {
        if (status.value.busy) return
        runJob?.cancel()
        runJob = scope.launch {
            mutex.withLock {
                try {
                    connectManualImpl(cfgId)
                } finally {
                    status.update { it.copy(busy = false) }
                    busySince = 0
                    cooldownUntil = now() + if (status.value.connected) 20_000 else 30_000
                }
            }
        }
    }

    private suspend fun connectManualImpl(cfgId: String) {
        val cfg = store.configs.value.firstOrNull { it.id == cfgId }
        if (cfg == null) {
            status.update { it.copy(message = "الكونفيج غير موجود") }
            return
        }
        // بلا تقطيع عبثي: إن كنا متصلين فعلاً بنفس الكونفيج والنفق سليم، لا نهدمه
        val st0 = status.value
        if (st0.connected && st0.activeConfigId == cfgId && tunnelIsUp()) {
            val v = vpnInfo()
            val lat = v?.let { Probes.quickAlive(it.network) }
            if (lat != null) {
                status.update { it.copy(latencyMs = lat, lastScanAt = now(), message = "متصل ✔ (بدون إعادة بناء — النفق سليم ${lat}ms)") }
                log("↩ نفس الكونفيج متصل وسليم — لم يُعَد البناء لتفادي التقطيع")
                return
            }
        }
        status.update { it.copy(running = true, busy = true, connected = false, message = "جارٍ الاتصال بـ ${cfg.name}…") }
        busySince = now()
        report.value = Report(rows = listOf(ConfigRow(cfg.id, cfg.name, state = "testing")))
        val under = NetUtil.underlying(NetUtil.all(cm))
        val direct = under?.let { Probes.exitInfo(it.network, fast = true) }
        if (direct != null) status.update { it.copy(directIp = direct.ip, directLoc = direct.loc) }
        val s = store.settings.value
        val dns: DnsServer? =
            if (s.autoDns) store.dns.value.firstOrNull { it.enabled && !it.filtered }
            else store.dns.value.firstOrNull { it.id == s.selDnsId }
        val startMtu = if (s.autoMtu) null else s.selMtu

        downSafe()
        val raw = connectAndVerify(cfg, startMtu, dns?.ips() ?: emptyList(), direct)
        // WARP: خروج سورياً/مخالف للتثبيت → بحث تلقائي عبر بدائل Endpoint قبل إعلان الاتصال
        val ver = if (raw != null && isWarp(cfg))
            warpForeignAssist(cfg, dns?.ips() ?: emptyList(), direct, raw) ?: raw
        else raw
        if (ver != null) {
            markConnected(cfg, dns, emptyList(), ver, null, networkLabel(), direct, emptyList())
            // تحذير شفاف في كل مرة: شبكات Anycast مثل Cloudflare WARP قد تُعيد توجيهك لمركز
            // بيانات مختلف (ربما دولتك) كلما تغيّرت الشبكة الفعلية — حتى لنفس الكونفيج بلا تغيير.
            val sameCountry = direct != null && ver.exit?.loc?.isNotEmpty() == true &&
                ver.exit.loc.equals(direct.loc, ignoreCase = true)
            val note = if (sameCountry) " ⚠ نفس دولتك (${ver.exit?.loc}) على هذه الشبكة" else ""
            report.update { r ->
                r.copy(rows = r.rows.map {
                    if (it.id == cfg.id) it.copy(state = "ok", latencyMs = ver.lat, sameCountry = sameCountry) else it
                })
            }
            if (sameCountry) status.update { it.copy(message = "متصل ✔ لكن$note") }
            log("✔ تفعيل فردي: ${cfg.name} • ${ver.diag}$note")
            // تكميل الخلفية لهذا الكونفيج فقط: خروج مؤكد + DNS + سرعة — دون مساس بغيره
            postConnectRefine(store.dns.value.filter { it.enabled && (!it.filtered || s.allowFilteredDns) })
        } else {
            // مع قاعدة التثبيت المتساهلة (TCP يكفي)، الفشل هنا حقيقي:
            // مفتاح منتهٍ/مبطل أو شبكة مقطوعة — لا استبعاد ظالم.
            status.update { it.copy(connected = false, message = "فشل تفعيل ${cfg.name}: لا يمر أي TCP (مفتاح منتهٍ؟ أو الشبكة مقطوعة؟)") }
            report.update { r -> r.copy(rows = r.rows.map { if (it.id == cfg.id) it.copy(state = "fail", note = "لا يمر TCP — تحقق من صلاحية الكونفيج والشبكة") else it }) }
            log("✗ فشل التفعيل الفردي: ${cfg.name} (لا TCP — سبب حقيقي)")
        }
    }

    /**
     * فحص خلفي لا يقطع الاتصال الحالي إطلاقاً: يقيس زمن استجابة الخوادم البديلة عبر ping عادي
     * (يمر خلال النفق الحالي نفسه، لا يلمس واجهته). لا يبدّل إلا إن وُجد مرشّح أسرع بوضوح
     * (أقل من 70% من زمن النفق الحالي)، وحتى حينها يُختبر لثوانٍ معدودة فقط: إن لم يتفوّق فعلياً
     * تتم العودة فوراً للكونفيج السابق بدل إكمال فحص شامل لكل الكونفيجات.
     */
    fun opportunisticCheck(reason: String) {
        if (isManual()) return   // اليدوي: ممنوع التبديل التلقائي لكونفيج آخر
        if (status.value.busy || now() < cooldownUntil) return
        val st = status.value
        if (!st.running) return
        if (!st.connected) {
            reselect(reason, allowFast = true)
            return
        }
        runJob?.cancel()
        runJob = scope.launch {
            mutex.withLock {
                try {
                    opportunisticImpl(reason)
                } finally {
                    status.update { it.copy(busy = false) }
                    cooldownUntil = now() + 20_000
                }
            }
        }
    }

    private suspend fun opportunisticImpl(reason: String) {
        val st = status.value
        val cands = store.configs.value.filter { it.enabled && it.id != st.activeConfigId }
        if (cands.isEmpty()) return
        status.update { it.copy(busy = true, message = "فحص خلفي بلا انقطاع…") }
        log("فحص خلفي بلا قطع الاتصال ($reason)")

        val currentLat = st.latencyMs?.takeIf { it > 0 } ?: return
        var promising: WgConfig? = null
        var bestPing = Int.MAX_VALUE
        for (c in cands) {
            currentCoroutineContext().ensureActive()
            val host = Probes.parseEndpoint(c.text)?.first ?: continue
            val rtt = Probes.pingOnce(host) ?: continue
            if (rtt < bestPing) {
                bestPing = rtt
                promising = c
            }
        }
        // عتبة أشد صرامة: لا نخاطر بتبديل نفق يعمل فعلاً إلا لتحسّن كبير وواضح جداً.
        if (promising == null || bestPing >= (currentLat * 0.5).toInt()) {
            log("لا يوجد كونفيج أفضل بوضوح — بقي ${st.activeConfig} كما هو")
            status.update { it.copy(message = "متصل ✔ (لا تحسين متاح)") }
            return
        }

        log("مرشّح واعد: ${promising.name} (~${bestPing}ms عبر ping مقابل ${currentLat}ms حالياً) — تجربة سريعة")
        val savedCfg = store.configs.value.firstOrNull { it.id == st.activeConfigId }
        val savedDns = store.dns.value.firstOrNull { it.id == st.activeDnsId }
        val savedMtu = st.activeMtu
        val direct = if (st.directIp.isNotEmpty()) ExitInfo(st.directIp, st.directLoc, 0) else null
        val dnsAll = store.dns.value.filter { it.enabled && !it.filtered }

        // بلا فرض MTU: الكونفيج كما هو أولاً، والسلّم يقرر — لا رقم ثابت
        val ver = connectAndVerify(promising, null, savedDns?.ips() ?: emptyList(), direct)
        if (ver != null && ver.lat < currentLat) {
            val (dns, dnsRows) = pickDns(dnsAll, ver.vpn.network)
            markConnected(promising, dns, dnsRows, ver, null, networkLabel(), direct, emptyList())
            log("★ تبديل فوري إلى ${promising.name} (${ver.lat}ms) بعد تحسّن فعلي مؤكَّد")
            return
        }

        log("لم يتحسّن فعلياً بعد التجربة — عودة فورية إلى ${savedCfg?.name ?: "السابق"}")
        if (savedCfg != null) {
            val back = connectAndVerify(savedCfg, savedMtu, savedDns?.ips() ?: emptyList(), direct)
            if (back != null) {
                status.update { it.copy(latencyMs = back.lat, message = "متصل ✔ (عاد للسابق)") }
                log("✔ عاد الاتصال بـ ${savedCfg.name}")
            } else {
                log("⚠ تعذّرت العودة للكونفيج السابق — سيُجرى فحص كامل")
                selectAndConnect("فشل الاسترجاع بعد محاولة تحسين", false)
            }
        }
    }

    // ---------- أحداث الشبكة ----------
    /** kind = "vpn": ظهر أو اختفى VPN (من أي تطبيق) — kind = "net": تبدّلت الشبكة الأصلية أو IP. */
    fun onNetworkChanged(kind: String) {
        if (status.value.busy) return
        if (now() - lastOwnChange < 6000) return   // حدث ناتج عن تشغيل/إيقاف نفق التطبيق نفسه
        val s = store.settings.value

        if (kind == "vpn") {
            netJob?.cancel()
            netJob = scope.launch {
                delay(1500)
                if (status.value.busy) return@launch
                if (status.value.running) {
                    // نتأكد مرتين أن نفقنا سقط فعلاً (لأن VPN آخر استلم الاتصال)
                    if (!status.value.connected || tunnelIsUp()) return@launch
                    delay(2500)
                    if (status.value.busy || tunnelIsUp()) return@launch
                    if (vpnInfo() != null) {
                        log("⚠ VPN آخر استبدل نفق التطبيق")
                        status.value = Status(running = false, message = "أوقفه VPN آخر")
                    } else {
                        mutex.withLock { recover("اختفى النفق") }
                        return@launch
                    }
                }
                if (s.backgroundScan) {
                    scanNetwork(if (hasExternalVpn()) "اكتُشف تشغيل VPN خارجي" else "تغيّرت حالة الـ VPN")
                }
            }
            return
        }

        val running = status.value.running
        if (running && !s.reselectOnNetwork) return
        if (!running && !s.backgroundScan) return
        netJob?.cancel()
        netJob = scope.launch {
            delay(3000)   // تجميع التغييرات المتتابعة
            // فاصل 10 ثوانٍ بين فحوصات الشبكة (يمنع الحلقات) — بدل حجب التهدئة الطويل
            if (status.value.busy || now() - lastNetScanAt < 10_000) return@launch
            val st = status.value
            if (!st.running) {
                lastNetScanAt = now()
                scanNetwork("تغيّر الشبكة")
                return@launch
            }
            val label = networkLabel()
            val manual = isManual()
            when {
                !st.connected && s.autoConfig -> {
                    lastNetScanAt = now()
                    reselect("تغيّر الشبكة", allowFast = true)
                }
                // يدوي غير متصل: إعلان فقط — التفعيل قرار المستخدم وحده
                !st.connected -> {
                    log("تغيّرت الشبكة ولا اتصال — الوضع يدوي: فعّل بنفسك عند الحاجة")
                    status.update { it.copy(message = "غير متصل — فعّله يدوياً") }
                }
                label != lastNetLabel && label != "غير متصل" && s.autoConfig -> {
                    lastNetLabel = label
                    lastNetScanAt = now()
                    // تبدّل الشبكة قد يحتاج MTU وDNS مختلفين — إعادة فحص كاملة فورية
                    log("تبدّلت الشبكة إلى $label — إعادة فحص (MTU/DNS جديدان محتملان)")
                    reselect("تبدّل الشبكة إلى $label", allowFast = true)
                }
                label != lastNetLabel && label != "غير متصل" -> {
                    // المسار اليدوي: لا هدم تلقائي إطلاقاً — فحص ثم إعلان فقط
                    lastNetLabel = label
                    log("تبدّلت الشبكة إلى $label — التحقق من النفق اليدوي (بلا هدم)")
                    mutex.withLock {
                        val lat = aliveNow()
                        if (lat != null) {
                            status.update { it.copy(latencyMs = lat, lastScanAt = now()) }
                            log("النفق اليدوي سليم بعد تبدّل الشبكة (${lat}ms) — بقي دون إعادة بناء")
                        } else {
                            log("النفق اليدوي لا يمرر بيانات بعد تبدّل الشبكة — بانتظار تفعيلك اليدوي")
                            status.update { it.copy(connected = false, message = "تبدّلت الشبكة والنفق لا يمرر — أعد التفعيل يدوياً") }
                        }
                    }
                }
                else -> mutex.withLock {
                    // نفس نوع الشبكة (تغيّر IP فقط): نتحقق أن النفق سليم فعلاً
                    val lat = aliveNow()
                    if (lat != null) {
                        status.update { it.copy(latencyMs = lat, lastScanAt = now()) }
                        log("النفق سليم بعد تغيّر الشبكة (${lat}ms)")
                    } else if (manual) {
                        log("تغيّر IP والنفق اليدوي لا يمرر — بانتظار تفعيلك اليدوي")
                        status.update { it.copy(connected = false, message = "تغيّر IP والنفق متوقف — أعد التفعيل يدوياً") }
                    } else {
                        lastNetScanAt = now()
                        recover("تغيّر الشبكة")
                    }
                }
            }
        }
    }

    private fun hasExternalVpn(): Boolean = NetUtil.all(cm).any { it.isVpn } && !tunnelIsUp()

    /** مرور حقيقي عبر واجهة الـ VPN (وليس عبر الشبكة الأصلية). null إن لم يوجد نفق أو لم يمر شيء. */
    private fun aliveNow(): Int? {
        val v = vpnInfo() ?: return null
        if (!tunnelIsUp()) return null
        return Probes.quickAlive(v.network)
    }

    /** فحص صحة دوري خفيف: يتحقق أن النفق موجود فعلاً وأن البيانات تمر عبره. */
    fun healthCheck() {
        val st = status.value
        if (!st.running || st.busy || !st.connected) return
        if (now() < cooldownUntil) return
        val manual = isManual()
        scope.launch {
            mutex.withLock {
                val s2 = status.value
                if (!s2.running || s2.busy || !s2.connected) return@withLock
                if (NetUtil.underlying(NetUtil.all(cm)) == null) return@withLock   // لا شبكة أصلاً
                val v = vpnInfo()
                if (v == null || !tunnelIsUp()) {
                    log("⚠ لا توجد واجهة VPN فعلية رغم الحالة «متصل»")
                    if (manual) {
                        // يدوي: إعلان فقط — إعادة التفعيل بقرار المستخدم وحده
                        status.update { it.copy(connected = false, message = "انقطع النفق — أعد التفعيل يدوياً") }
                    } else {
                        recover("النفق غير موجود")
                    }
                    return@withLock
                }
                val lat = Probes.quickAlive(v.network)
                if (lat != null) {
                    healthFails = 0
                    status.update { it.copy(latencyMs = lat, lastScanAt = now()) }
                } else {
                    healthFails++
                    log("⚠ لا تمر بيانات عبر النفق ($healthFails/6)")
                    // عتبة أعلى من قبل: فحص واحد فاشل بين كل 15 ثانية أمر طبيعي حتى على شبكة
                    // سليمة، وإعادة بناء النفق لمجرد ذلك تسبّب انقطاعات وهمية أثناء عمل فعلي.
                    if (healthFails >= 6) {
                        healthFails = 0
                        if (manual) {
                            status.update { it.copy(connected = false, message = "توقف مرور البيانات — أعد التفعيل يدوياً") }
                            log("⚠ اليدوي لا يُعاد بناؤه تلقائياً — بانتظار المستخدم")
                        } else {
                            recover("انقطاع الاتصال")
                        }
                    }
                }
            }
        }
    }

    /**
     * يُستدعى داخل القفل.
     * آلي: يعيد بناء نفس النفق مع التحقق، فإن فشل يجري اختياراً كاملاً.
     * يدوي: لا يلمس النفق إطلاقاً — يُعلن الانقطاع وينتظر المستخدم.
     */
    private suspend fun recover(reason: String) {
        if (!tunnelIsUp() && NetUtil.all(cm).any { it.isVpn }) {
            log("⚠ VPN آخر استلم الاتصال — أوقف التطبيق نفقه")
            if (killSwitchApplied) {
                RootTools.killSwitchDisable()
                killSwitchApplied = false
                log("قاطع الطوارئ أُوقف تلقائياً (لم يعد التطبيق يتحكم بالنفق)")
            }
            status.value = Status(running = false, message = "أوقفه VPN آخر")
            return
        }
        if (isManual()) {
            log("⚠ ($reason) — الوضع يدوي: لا إعادة بناء تلقائية")
            status.update { it.copy(connected = false, message = "انقطع ($reason) — أعد التفعيل يدوياً") }
            return
        }
        val st = status.value
        val cfg = store.configs.value.firstOrNull { it.id == st.activeConfigId }
        val dns = store.dns.value.firstOrNull { it.id == st.activeDnsId }
        if (cfg != null) {
            log("إعادة بناء النفق ($reason)")
            val direct = if (st.directIp.isNotEmpty()) ExitInfo(st.directIp, st.directLoc, 0) else null
            val ver = connectAndVerify(cfg, st.activeMtu, dns?.ips() ?: emptyList(), direct)
            if (ver != null) {
                status.update {
                    it.copy(
                        latencyMs = ver.lat, exitIp = ver.exit?.ip ?: it.exitIp,
                        exitLoc = ver.exit?.loc ?: it.exitLoc, vpnIface = ver.vpn.iface,
                        activeMtu = ver.mtu, diag = ver.diag
                    )
                }
                log("✔ عاد النفق")
                return
            }
        }
        try {
            selectAndConnect("استرجاع: $reason", false)
        } finally {
            status.update { it.copy(busy = false) }
            cooldownUntil = now() + if (status.value.connected) 20_000 else 60_000
        }
    }

    // ---------- فحص الشبكة (مستقل عن أي VPN) ----------
    /** يفحص جهة واحدة مربوطة بشبكة محددة. mtuIface: واجهة الفحص (روت) أو null للفحص العادي. */
    private suspend fun scanSide(
        n: NetUtil.Info, dnsList: List<DnsServer>, doMtu: Boolean,
        root: Boolean, mtuIface: String?, vpnSide: Boolean, encrypted: Boolean, allowFiltered: Boolean
    ): SideScan {
        val (res, sys) = coroutineScope {
            val sysJob = async { Probes.systemResolveStat(n.network) }
            val list = dnsList.map { d -> async { d to Probes.dnsMeasure(d, n.network, encrypted) } }.awaitAll()
            list to sysJob.await()
        }
        // الأفضل: أدنى درجة (وسيط + نصف التذبذب + 4×الفقد) بين الخوادم المسموح باختيارها
        val best = res
            .filter { allowFiltered || !it.first.filtered }
            .mapNotNull { (d, m) -> m.best()?.let { Triple(d, it.first, it.second) } }
            .filter { it.third.loss <= 50 }
            .minByOrNull { it.third.score }
        val lat = Probes.tcpStat(n.network, 4)?.median
        currentCoroutineContext().ensureActive()
        val p = if (doMtu) Probes.pathMtu(InetAddress.getByName("1.1.1.1"), root, mtuIface) else null
        val suggested = when {
            p == null -> 0
            vpnSide -> p
            else -> MtuCatalog.tunnelMtuFromPath(p, false)
        }
        return SideScan(
            SideInfo(
                label = n.label, iface = n.iface, bestDns = best?.first?.name ?: "—",
                bestDnsId = best?.first?.id ?: "", dnsMs = best?.third?.median ?: -1,
                dnsProto = best?.second ?: "", dnsLoss = best?.third?.loss ?: 100,
                sysDnsMs = sys?.median ?: -1, latencyMs = lat ?: -1,
                pathMtu = p ?: 0, suggestedMtu = suggested, ifaceMtu = n.mtu
            ),
            res, sys
        )
    }

    /**
     * يفحص الجهتين: الشبكة الأصلية مباشرة (متجاوزاً أي VPN)، وشبكة الـ VPN إن وُجد
     * (تطبيقنا أو تطبيق آخر). ثم يثبّت القيم المثلى بالروت إن فُعّل الخيار وثبت التحسّن قياساً.
     */
    fun scanNetwork(reason: String) {
        if (status.value.busy) return
        scanJob?.cancel()
        scanJob = scope.launch {
            mutex.withLock {
                val s = store.settings.value
                val nets = NetUtil.all(cm)
                val under = NetUtil.underlying(nets)
                if (under == null) {
                    netInfo.value = NetInfo()
                    return@withLock
                }
                val vpn = nets.firstOrNull { it.isVpn }
                val owner = when {
                    vpn == null -> ""
                    status.value.connected && tunnelIsUp() -> "التطبيق"
                    else -> "خارجي"
                }
                val root = s.useRoot || s.applyRoot
                val dnsList = store.dns.value.filter { it.enabled }
                log("فحص الشبكة: $reason")

                val direct = scanSide(
                    under, dnsList,
                    doMtu = vpn == null || root,
                    root = root,
                    mtuIface = if (vpn == null) null else under.iface,
                    vpnSide = false,
                    encrypted = s.applyRoot,
                    allowFiltered = s.allowFilteredDns
                )
                dnsResults.value = direct.measures.associate { it.first.id to it.second }

                val viaVpn = if (vpn != null) {
                    scanSide(vpn, dnsList, true, false, null, true, false, s.allowFilteredDns)
                } else null

                netInfo.value = NetInfo(
                    direct = direct.info,
                    vpn = viaVpn?.info,
                    vpnOwner = owner,
                    time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                )
                status.update { it.copy(lastScanAt = now()) }
                log(
                    "الأصلية (${direct.info.label}): DNS ${direct.info.bestDns} ${direct.info.dnsMs}ms" +
                        (if (direct.info.sysDnsMs >= 0) " (الحالي ${direct.info.sysDnsMs}ms)" else "") +
                        " • MTU ${direct.info.pathMtu}" +
                        (viaVpn?.let { " | VPN ($owner): MTU ${it.info.pathMtu}" } ?: "")
                )

                if (s.applyRoot) applyValues(direct, viaVpn, owner, vpn?.iface ?: "", under, s.allowFilteredDns)
            }
        }
    }

    // ---------- تثبيت القيم بالروت (بعد قياس مقارن، مع تراجع تلقائي) ----------
    private suspend fun applyValues(
        direct: SideScan, viaVpn: SideScan?, owner: String, vpnIface: String,
        under: NetUtil.Info, allowFiltered: Boolean
    ) {
        // 1) MTU لواجهة VPN خارجي: نخفضه إلى أكبر حزمة تمر فعلاً (لا نرفعه أبداً)
        if (owner == "خارجي" && vpnIface.isNotEmpty() && viaVpn != null && viaVpn.info.pathMtu in 1000..1500) {
            val cur = RootTools.getIfaceMtu(vpnIface)
            if (cur != null && viaVpn.info.pathMtu < cur) {
                val ok = RootTools.setIfaceMtu(vpnIface, viaVpn.info.pathMtu)
                log(
                    if (ok) "✔ MTU على $vpnIface: $cur → ${viaVpn.info.pathMtu}"
                    else "✗ تعذّر ضبط MTU على $vpnIface"
                )
            }
        }

        // 2) DNS النظام (Private DNS): خادم DoT مقاس فعلاً وبفقد 0% وأفضل من الحالي بوضوح.
        // تهدئة 30 دقيقة بين محاولتين: كل محاولة تفتح نافذة اختبار قصيرة قد تُحسّ كانقطاع، فلا
        // داعي لتكرارها في كل فحص خلفية.
        if (now() < dnsChangeCooldownUntil) return
        val cand = direct.measures
            .filter { allowFiltered || !it.first.filtered }
            .mapNotNull { (d, m) -> m.dot?.takeIf { it.loss == 0 && d.dot.isNotEmpty() }?.let { d to it } }
            .minByOrNull { it.second.score }
        if (cand == null) {
            log("DNS النظام: لا يوجد خادم DoT مقاس بنجاح كامل — لم يُغيَّر شيء")
            return
        }
        val base = direct.sys
        if (base != null && cand.second.score >= base.score * 0.85) {
            log("DNS النظام: الحالي (${base.median}ms) قريب من ${cand.first.name} (${cand.second.median}ms) — لم يُغيَّر")
            return
        }
        if (store.prefGet("appliedDot") == cand.first.dot) return

        if (store.prefGet("prevDnsSaved") == null) {
            val (mode, spec) = RootTools.getPrivateDns()
            store.prefPut("prevMode", mode)
            store.prefPut("prevSpec", spec)
            store.prefPut("prevDnsSaved", "1")
        }
        dnsChangeCooldownUntil = now() + 30 * 60 * 1000
        if (!RootTools.setPrivateDns(cand.first.dot)) {
            log("✗ تعذّر ضبط DNS النظام")
            return
        }
        log("⏳ تطبيق DNS تجريبياً: ${cand.first.name} — يُتحقق خلال 5 ثوانٍ كحد أقصى")
        var confirmed = false
        try {
            // مهلة صارمة 5 ثوانٍ: لا يُترك النظام معلَّقاً على DNS لا يعمل أطول من ذلك.
            val after = withTimeoutOrNull(5000) {
                delay(1000)
                Probes.systemResolveStat(under.network)
            }
            if (after == null || after.loss > 25) {
                log(
                    if (after == null) "✗ عُلِّق التحقق (>5 ثوانٍ) بعد تطبيق ${cand.first.name} — تراجعتُ فوراً"
                    else "✗ فشل الحل بعد تطبيق ${cand.first.name} — تراجعتُ عن التغيير"
                )
            } else {
                confirmed = true
                store.prefPut("appliedDot", cand.first.dot)
                log(
                    "✔ DNS النظام: ${cand.first.name} (${cand.first.dot}) — DoT ${cand.second.median}ms " +
                        "مقابل الحالي ${base?.median ?: "؟"}ms، وبعد التطبيق ${after.median}ms"
                )
            }
        } finally {
            // يعمل حتى لو أُلغيت هذه العملية أو استُبدلت بفحص آخر أثناء الانتظار — لا يبقى
            // النظام عالقاً على DNS غير مؤكَّد مهما حدث.
            if (!confirmed) {
                withContext(NonCancellable) { restoreNow() }
            }
        }
    }

    private fun restoreNow(): Boolean {
        if (store.prefGet("prevDnsSaved") == null) return true
        val ok = RootTools.restorePrivateDns(store.prefGet("prevMode") ?: "", store.prefGet("prevSpec") ?: "")
        store.prefPut("appliedDot", null)
        store.prefPut("prevDnsSaved", null)
        return ok
    }

    /** يعيد إعداد Private DNS إلى ما كان قبل تدخل التطبيق. */
    fun restoreSystemDns() {
        scope.launch {
            if (store.prefGet("prevDnsSaved") == null) {
                log("لا يوجد إعداد DNS سابق محفوظ للاستعادة")
                return@launch
            }
            log(if (restoreNow()) "تمت استعادة إعداد DNS الأصلي للنظام" else "تعذّرت استعادة DNS")
        }
    }

    /** اختبار DNS يدوي: كل الخوادم بالبروتوكولات الثلاثة على الشبكة الأصلية. */
    fun testDnsNow() {
        scope.launch {
            val under = NetUtil.underlying(NetUtil.all(cm)) ?: return@launch
            val list = store.dns.value
            val res = coroutineScope {
                list.map { d -> async { d.id to Probes.dnsMeasure(d, under.network, true) } }.awaitAll()
            }
            dnsResults.value = res.toMap()
        }
    }

    /** آخر خطأ فعلي من backend.setState لعرضه بدل رسالة عامة. */
    @Volatile
    private var lastUpError = ""

    /**
     * محدد سرعة المصافحات: سيرفرات Proton المجانية تحجب مؤقتاً من يرشقها
     * بعشرات المصافحات المتتالية (وهذا ما كان يُفشل الفحص الدقيق واليدوي بعد
     * فحص شامل). أي رفع نفق ينتظر 900ms على الأقل منذ السابق.
     */
    @Volatile
    private var lastTunnelUpAt = 0L

    // ---------- التحكم بالنفق: رفع وإنزال آمن مع التحقق ----------
    /** appOnly = true: نفق تجريبي خاص بهذا التطبيق فقط (لا يقطع بقية التطبيقات). */
    private fun up(c: WgConfig, mtu: Int?, dns: List<String>, appOnly: Boolean, quiet: Boolean = false): Boolean {
        lastOwnChange = now()
        // المحاولة 1: النص الكامل (مع IPv6 إن وُجد)
        val first = tryUp(c, mtu, dns, appOnly, ipv4Only = false)
        if (first == null) return true // نجح
        // المحاولة 2 تلقائياً: إن فشل بسبب العنوان (Cannot set address) نعيد بـ IPv4 فقط
        // هذا يحل كونفيجات Proton على أجهزة/شبكات بلا IPv6 بدون أي تدخل يدوي.
        if (first.contains("address", true) || first.contains("عنوان", true) ||
            first.contains("EINVAL", true) || first.contains("addAddress", true)
        ) {
            log("↻ ${c.name}: فشل العنوان — إعادة المحاولة IPv4 فقط")
            val second = tryUp(c, mtu, dns, appOnly, ipv4Only = true)
            if (second == null) {
                log("✔ ${c.name}: نجح بـ IPv4 فقط")
                return true
            }
            lastUpError = second
            log("خطأ في ${c.name}: $second")
            return false
        }
        lastUpError = first
        log("خطأ في ${c.name}: $first")
        return false
    }

    /** يعيد null عند النجاح أو نص الخطأ. */
    private fun tryUp(c: WgConfig, mtu: Int?, dns: List<String>, appOnly: Boolean, ipv4Only: Boolean): String? {
        return try {
            var text = ConfigText.override(c.text, mtu, dns, if (appOnly) appCtx.packageName else null)
            if (ipv4Only) text = ConfigText.stripIpv6(text)
            val cfg = Config.parse(ByteArrayInputStream(text.toByteArray()))
            backend.setState(tunnel, Tunnel.State.UP, cfg)
            lastOwnChange = now()
            lastUpError = ""
            null
        } catch (e: Exception) {
            lastOwnChange = now()
            e.message?.take(140) ?: e.javaClass.simpleName
        }
    }

    private fun down() {
        lastOwnChange = now()
        try {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
        } catch (e: Exception) {
            // تجاهل
        }
        lastOwnChange = now()
    }

    /**
     * ينزل النفق وينتظر زوال واجهة الـ VPN فعلاً. هذا ضروري: رفع نفق جديد قبل اكتمال إغلاق
     * خدمة الـ VPN السابقة يجعل النظام يهدم النفق الجديد بعد لحظات (نفق «وهمي»).
     */
    private suspend fun downSafe() {
        val wasUp = tunnelIsUp()
        down()
        if (wasUp) {
            val t0 = now()
            while (now() - t0 < 4000 && NetUtil.all(cm).any { it.isVpn }) delay(150)
            delay(250)
        }
        lastOwnChange = now()
    }

    private suspend fun waitVpnUp(maxMs: Long = 3500): NetUtil.Info? {
        val t0 = now()
        while (now() - t0 < maxMs) {
            val v = vpnInfo()
            if (v != null && tunnelIsUp()) return v
            delay(150)
        }
        return null
    }

    /** يرفع نفقاً ويتأكد أن واجهة VPN أُنشئت فعلاً (بمحاولتين). */
    private suspend fun bringUp(
        c: WgConfig, mtu: Int?, dns: List<String>, appOnly: Boolean, quiet: Boolean = false
    ): NetUtil.Info? {
        downSafe()
        for (attempt in 1..2) {
            val gap = now() - lastTunnelUpAt
            if (gap < 900) delay(900 - gap)
            if (!up(c, mtu, dns, appOnly, quiet)) return null
            lastTunnelUpAt = now()
            val v = waitVpnUp()
            if (v != null) {
                lastOwnChange = now()
                return v
            }
            if (!quiet) log("⚠ لم تُنشأ واجهة VPN (محاولة $attempt)")
            downSafe()
        }
        return null
    }

    private fun fallbackDns(): List<String> =
        store.dns.value.firstOrNull { it.enabled && !it.filtered }?.ips()?.takeIf { it.isNotEmpty() }
            ?: listOf("1.1.1.1", "1.0.0.1")

    /**
     * يرفع النفق الكامل ويتحقق من كل الطبقات.
     * الترتيب (لا نفرض MTU): القيمة المجرّبة الناجحة أولاً (من الفحص السريع)،
     * ثم الكونفيج كما هو تماماً (مثل التطبيق الرسمي)، ثم قيم عامة.
     * strict=true (للتمريرة الأولى): يشترط تحققاً كاملاً (TCP+HTTPS+تغيّر IP) —
     * من لا يحققه يُتجاوز للتالي بدل تثبيته. strict=false: من يمرّر TCP يُثبَّت
     * (ولو بتحذير) — الرفض فقط عند انعدام TCP (منتهٍ/مقطوع فعلاً).
     */
    private suspend fun connectAndVerify(
        cfg: WgConfig, mtu: Int?, dns: List<String>, direct: ExitInfo?, strict: Boolean = false
    ): Verified? {
        // 1000 إضافية: شبكات الجوال (خصوصاً مع NAT64/464XLAT عند بعض المشغّلين) غالباً ما
        // تحتاج MTU أقل من المعتاد على الواي فاي. الدرجات مضغوطة عمداً (بلا 1120 الهامشية)
        // حتى لا يتجمد التثبيت: المجرّب أولاً، ثم الأصلي، ثم 1280، ثم 1000.
        // الزوج (mtu, useOwnDns): useOwnDns=true يعني DNS الكونفيج الأصلي كما هو.
        // المجرّب أولاً: إن جاء mtu جاهزاً من الفحص السريع فهو أثبت نجاحه قبل ثوانٍ.
        val rungs: List<Pair<Int?, Boolean>> =
            listOfNotNull(mtu?.let { it to false }) +
                listOf(null to true) +
                listOf(1280 to false, 1000 to false).filter { it.first != mtu }.distinct() +
                listOf(null to false)
        for ((idx, rung) in rungs.withIndex()) {
            val (m, ownDns) = rung
            if (idx > 0) delay(700)
            val label = if (ownDns && m == null) "الأصلي للكونفيج" else (m?.toString() ?: "الافتراضي")
            setProgress("تثبيت ${cfg.name} (MTU $label)…")
            var ips: List<String> = if (ownDns) emptyList() else dns
            for (round in 0..1) {
                val v = bringUp(cfg, m, ips, appOnly = false) ?: break
                delay(800)
                val lat = Probes.quickAlive(v.network, 3000)
                if (lat == null) {
                    log("✗ MTU $label: لا يتصل TCP عبر النفق")
                    break
                }
                val exit = Probes.exitInfo(v.network, fast = true)
                val effMtu = m ?: ConfigText.configuredMtu(cfg.text) ?: 1280
                if (exit == null) {
                    // النفق يحمل TCP لكن HTTPS لا يمر.
                    if (strict) {
                        // التمريرة الصارمة: يُتجاوز للتالي بحثاً عن مكتمل مضمون
                        log("○ MTU $label: TCP يمر لكن HTTPS لا يمر — يُتجاوز لمكتمل أفضل")
                        break
                    }
                    // المتساهلة: يُثبَّت أساسياً ولا يُستبعد — الجاني غالباً حجب 443 لا الكونفيج
                    log("⚠ MTU $label: TCP يمر لكن HTTPS لا يمر — تثبيت أساسي (تحقق يدوياً من التصفح)")
                    val dnsStat0 = Probes.systemResolveStat(v.network, 1)
                    val diag0 = "TCP ✔ ${lat}ms • HTTPS ⚠ (لا يمر، قد يكون 443 محجوباً) • DNS " +
                        (dnsStat0?.let { "✔ ${it.median}ms" } ?: "✗") + " • MTU $label"
                    return Verified(v, null, lat, effMtu, ips, diag0)
                }
                if (direct != null && exit.ip == direct.ip) {
                    log("✗ الـ IP لم يتغيّر (${exit.ip}) — النفق لا يحمل الحركة")
                    return null
                }
                val dnsStat = Probes.systemResolveStat(v.network, 1)
                if (dnsStat == null && round == 0 && !ownDns) {
                    val alt = fallbackDns()
                    if (alt != ips) {
                        log("⚠ DNS النفق لا يستجيب — أجرّب ${alt.joinToString()}")
                        ips = alt
                        continue
                    }
                }
                // متوسط محاولتين لنفس المرجع (1.1.1.1) لعرض عادل بلا تحيّز أول اتصال
                val httpsAvg = Probes.httpsLatencyAvg(v.network, tries = 2) ?: exit.ms
                val diag = "TCP ✔ ${lat}ms • HTTPS ✔ ${httpsAvg}ms • DNS " +
                    (dnsStat?.let { "✔ ${it.median}ms" } ?: "✗ (لا يحل الأسماء)") + " • MTU $label"
                return Verified(v, exit.copy(ms = httpsAvg), lat, effMtu, ips, diag)
            }
        }
        return null
    }

    /**
     * امتحان كامل لكونفيج واحد بنفق تجريبي (خاص بالتطبيق — بلا قطع):
     * واجهة + TCP + خروج اختياري + عينات جودة (زمن/تذبذب/فقد).
     * vpn=null يعني راسباً مع note. الناجح يحمل درجته للترتيب النهائي.
     * ملاحظة: الخروج (HTTPS) اختياري — من يمرّر TCP ناجح، وبلا خروج يُقبل
     * بلا دولة مؤكدة (تُكتشف بعد التثبيت) بدل استبعاده.
     */
    private suspend fun probeTunnel(c: WgConfig, mtus: List<Int?>, dns: List<String>, direct: ExitInfo?): Probe {
        var note = "لم تُنشأ واجهة النفق"
        for (m in mtus) {
            // قيمة null = الكونفيج كما هو (MTU وDNS الأصليان) — لا نفرض شيئاً أولاً
            val useDns = if (m == null) emptyList() else dns
            val effMtu = m ?: ConfigText.configuredMtu(c.text) ?: 1280
            val v = bringUp(c, m, useDns, appOnly = true, quiet = true)
                ?: return Probe(null, effMtu, -1, null,
                    if (lastUpError.isNotEmpty()) "لم تُنشأ الواجهة: $lastUpError" else "لم تُنشأ واجهة النفق (تأكد من إذن VPN وعدم وجود VPN آخر)")
            val lat = Probes.quickAlive(v.network, 2500)
            if (lat == null) {
                note = "الخادم لا يستجيب (لا تمر بيانات)"
                // فشل TCP قد يكون MTU في حالات نادرة — نجرّب الدرجة التالية قبل الحكم
                continue
            }
            val exit = Probes.exitInfo(v.network, fast = true)
            if (direct != null && exit != null && exit.ip == direct.ip) {
                return Probe(null, effMtu, lat, exit, "الـ IP لم يتغيّر — لا يحمل الحركة")
            }
            // عينات الجودة للمعادلة (3 عينات سريعة تكفي للترتيب)
            val st = Probes.tcpStat(v.network, 3)
            val sameCountry = direct != null && exit?.loc?.isNotEmpty() == true &&
                exit.loc.equals(direct.loc, ignoreCase = true)
            return Probe(
                v, effMtu, st?.median ?: lat, exit,
                if (exit == null) "ناجح بلا خروج مؤكد (تُكتشف الدولة بعد التثبيت)" else "",
                sameCountry, st?.jitter ?: 0, st?.loss ?: 0
            )
        }
        return Probe(null, mtus.lastOrNull() ?: 1280, -1, null, note)
    }

    // ---------- فحص يدوي لكونفيج (بنفق تجريبي، بلا سجل) ----------
    fun testAll() = testConfigs(store.configs.value.filter { it.enabled }.map { it.id })

    fun testConfigs(ids: List<String>) {
        if (ids.isEmpty()) return
        // فحص صامت بلا قطع: إن كان VPN يعمل، لا نرفع أنفاقاً — نكتفي بping المنفذ
        // عبر المسار الحالي. لا يقطع الاتصال إطلاقاً.
        if (status.value.running) {
            scope.launch {
                for (id in ids) {
                    val c = store.configs.value.firstOrNull { it.id == id } ?: continue
                    val ep = Probes.parseEndpoint(c.text)
                    if (ep == null) {
                        setResult(id, "✗ لا يوجد Endpoint صالح في الكونفيج")
                        continue
                    }
                    setResult(id, "فحص صامت (بلا قطع)…")
                    val rtt = Probes.pingOnce(ep.first)
                    if (rtt == null) {
                        setResult(id, "⚠ صامت: الخادم لا يرد ping — قد يكون محجوباً أو ميتاً (افحصه لاحقاً بدون VPN)")
                    } else {
                        val cc = store.configs.value.firstOrNull { it.id == id }?.lastCountry ?: ""
                        val flag = if (cc.isNotEmpty()) " ${Country.label(cc)}" else ""
                        setResult(id, "○ صامت: ping ${ep.first} ≈ ${rtt}ms$flag — الفحص الكامل بعد إيقاف VPN")
                    }
                }
            }
            return
        }
        ids.forEach { setResult(it, "بانتظار الفحص…") }
        scope.launch {
            mutex.withLock {
                if (status.value.running) return@withLock
                val dns = store.dns.value.filter { it.enabled && !it.filtered }.firstOrNull()
                val under = NetUtil.underlying(NetUtil.all(cm))
                val direct = under?.let { Probes.exitInfo(it.network, fast = true) }
                for (id in ids) {
                    val c = store.configs.value.firstOrNull { it.id == id } ?: continue
                    setResult(id, "جارٍ الفحص…")
                    val p = probeTunnel(c, listOf(null, 1280), dns?.ips() ?: emptyList(), direct)
                    val v = p.vpn
                    if (v == null) {
                        setResult(id, "✗ ${p.note}")
                        continue
                    }
                    val st = Probes.tcpStat(v.network, 3)
                    val epHost = Probes.parseEndpoint(c.text)?.first ?: ""
                    // حفظ الدولة فوراً ليظهر العلم بجانب الكونفيج دائماً
                    if (p.exit?.loc?.isNotEmpty() == true) {
                        val cc = p.exit.loc
                        store.updateConfigs { l -> l.map {
                            if (it.id == id) it.copy(lastCountry = cc,
                                lastCountryUseful = direct == null || !cc.equals(direct.loc, true))
                            else it
                        } }
                    }
                    val storedCc = store.configs.value.firstOrNull { it.id == id }?.lastCountry ?: ""
                    val locLine = if (p.exit != null) Country.ipLine(p.exit.ip, p.exit.loc)
                        else if (storedCc.isNotEmpty()) "${Country.label(storedCc)} (محفوظة)"
                        else epHost
                    // بلا تحميل في الفحص الفردي أيضاً — الزمن والفقد يكفيان للترتيب
                    setResult(
                        id,
                        "✓ ${st?.median ?: p.lat}ms ±${st?.jitter ?: 0} • فقد ${st?.loss ?: 0}% • MTU ${p.mtu}\n$locLine"
                    )
                }
                downSafe()
            }
        }
    }

    // ---------- الاختيار عند التشغيل ----------
    private fun readLastBest(label: String): LastBest? {
        val p = store.prefGet("lastBest_$label")?.split("|") ?: return null
        if (p.size < 5) return null
        return LastBest(p[0], p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: 0, p[3], p[4].toLongOrNull() ?: 0L)
    }

    private fun saveLastBest(label: String, cfgId: String, mtu: Int, pathMtu: Int, dnsId: String) {
        store.prefPut("lastBest_$label", "$cfgId|$mtu|$pathMtu|$dnsId|${now()}")
    }

    // ---------- ذاكرة الامتحان لكل شكلة شبكة (ترتيب مسبق = وقت وبيانات أقل) ----------
    /** آخر زمن معروف لكل كونفيج على هذه الشكلة: "id:lat|id:lat" — زمن>0 ناجح، -1 فاشل، غياب=لم يُفحص. */
    private fun readExamMap(label: String): Map<String, Int> {
        val raw = store.prefGet("examLbl_${label.hashCode()}") ?: return emptyMap()
        return raw.split("|").mapNotNull { p ->
            val kv = p.split(":", limit = 2)
            if (kv.size == 2) kv[0] to (kv[1].toIntOrNull() ?: return@mapNotNull null) else null
        }.toMap()
    }

    private fun writeExamMap(label: String, m: Map<String, Int>) {
        val s = if (m.size <= 120) m.entries.joinToString("|") { "${it.key}:${it.value}" }
        else m.entries.sortedByDescending { it.value >= 0 }.take(120)
            .joinToString("|") { "${it.key}:${it.value}" }
        store.prefPut("examLbl_${label.hashCode()}", s)
    }

    // ---------- ذاكرة بدائل WARP + إصلاح الخروج عند كل اتصال ----------
    private fun isWarp(c: WgConfig): Boolean =
        c.name == "WARP" || c.name.startsWith("WARP-")

    private fun warpAltsKey(id: String) = "warpAlts_$id"

    private fun readWarpAlts(id: String): List<String> =
        store.prefGet(warpAltsKey(id))?.split("|")?.filter { it.contains(":") } ?: emptyList()

    private fun saveWarpAlts(id: String, eps: List<String>) {
        store.prefPut(warpAltsKey(id), eps.filter { it.contains(":") }.distinct().joinToString("|"))
    }

    private fun swapEndpointLine(text: String, ep: String): String =
        text.replace(Regex("(?i)^Endpoint\\s*=.*$", RegexOption.MULTILINE), "Endpoint = $ep")

    /** هل خروج مطابق؟ السوري مرفوض دائماً؛ وعند تثبيت دولة يُقبل المطابق فقط. */
    private fun warpExitOk(loc: String?, direct: ExitInfo?): Boolean {
        val pin = store.settings.value.warpCountry.uppercase().take(2)
        if (loc.isNullOrBlank() || loc.equals("SY", true)) return false
        if (pin.isNotBlank()) return loc.uppercase() == pin
        return direct == null || !loc.equals(direct.loc, true)
    }

    /**
     * إصلاح ذاتي لكونفيج WARP عند كل اتصال: إن كان الخروج سورياً أو مخالفاً للتثبيت،
     * تبدّل الـ Endpoint على بدائله المحفوظة (أو القائمة العامة) ويعيد الاختبار حتى يتحقق
     * خروج مطابق — بلا إعادة توليد ولا بيانات تسجيل جديدة. يُثبت البديل الناجح فعلياً في
     * الكونفيج، وإن لم يوجد يُعيد الـ Endpoint الأصلي ويوضّح ذلك للمستخدم.
     */
    private suspend fun warpForeignAssist(
        cfg: WgConfig, dns: List<String>, direct: ExitInfo?, firstVer: Verified
    ): Verified? {
        if (!isWarp(cfg)) return null
        if (warpExitOk(firstVer.exit?.loc, direct)) return null
        if (readWarpAlts(cfg.id).isEmpty()) saveWarpAlts(cfg.id, WarpGenerator.ENDPOINT_CANDIDATES)
        val alts = readWarpAlts(cfg.id)
        if (alts.isEmpty()) return null
        val pin = store.settings.value.warpCountry.uppercase().take(2)
        val wanted = if (pin.isNotBlank()) Country.label(pin) else "أجنبي غير سوري"
        val current = Probes.parseEndpoint(cfg.text)?.first ?: ""
        val origText = cfg.text
        log("WARP: خروج غير مطابق (${firstVer.exit?.loc ?: "؟"}) — تجربة بدائل Endpoint للوصول لـ $wanted")
        var i = 0
        for (ep in alts) {
            if (i >= 6) break
            if (ep.substringBefore(":") == current.substringBefore(":")) continue
            i++
            val swapped = swapEndpointLine(origText, ep)
            store.updateConfigs { l -> l.map { if (it.id == cfg.id) it.copy(text = swapped) else it } }
            status.update { it.copy(message = "WARP: بديل $i من ${alts.size} — $ep…") }
            val tried = withTimeoutOrNull(45_000) {
                connectAndVerify(cfg.copy(text = swapped), firstVer.mtu, dns, direct, strict = false)
            }
            if (tried != null && warpExitOk(tried.exit?.loc, direct)) {
                log("✔ WARP: بديل يعطي خروج المطلوب: $ep → ${tried.exit?.loc} • ${tried.lat}ms")
                saveWarpAlts(cfg.id, listOf(ep) + alts.filter { it != ep })
                return tried
            }
        }
        store.updateConfigs { l -> l.map { if (it.id == cfg.id) it.copy(text = origText) else it } }
        status.update { it.copy(message = "WARP: لا بديل يعطي خروج $wanted على هذه الشبكة — أُبقي الـ Endpoint الأصلي") }
        log("✗ WARP: لا بديل يعطي خروج $wanted ($i محاولة) — أُعيد الـ Endpoint الأصلي")
        return null
    }

    private fun markConnected(
        cfg: WgConfig, dns: DnsServer?, dnsRows: List<DnsRow>,
        ver: Verified, mbps: Double?, label: String, direct: ExitInfo?, mtuRows: List<MtuRow>
    ) {
        val verified = ver.exit != null && direct != null && ver.exit.ip != direct.ip
        status.update {
            it.copy(
                connected = true, activeConfig = cfg.name, activeConfigId = cfg.id,
                activeDns = dns?.name ?: "حسب الكونفيج", activeDnsId = dns?.id,
                activeMtu = ver.mtu, latencyMs = ver.lat, mbps = mbps,
                message = if (verified) "متصل ✔ (تم التحقق من الـ IP)" else "متصل (لم يُتحقق من الـ IP)",
                verified = verified, exitIp = ver.exit?.ip ?: "", exitLoc = ver.exit?.loc ?: "",
                directIp = direct?.ip ?: "", directLoc = direct?.loc ?: "", vpnIface = ver.vpn.iface,
                diag = ver.diag, lastScanAt = now()
            )
        }
        report.update {
            it.copy(
                chosenConfigId = cfg.id, chosenDnsId = dns?.id ?: "", chosenDnsProto = "UDP",
                chosenMtu = ver.mtu, chosenPathMtu = 0,
                dnsRows = dnsRows.ifEmpty { it.dnsRows },
                mtuRows = mtuRows.ifEmpty { it.mtuRows }, progress = "تم الاتصال"
            )
        }
        saveLastBest(label, cfg.id, ver.mtu, 0, dns?.id ?: "")
        // حفظ دولة الخروج لكل كونفيج لعرض العلم لاحقاً بدون فحص جديد
        ver.exit?.let { e ->
            if (e.loc.isNotEmpty()) {
                store.updateConfigs { l -> l.map {
                    if (it.id == cfg.id) it.copy(lastCountry = e.loc,
                        lastCountryUseful = direct == null || !e.loc.equals(direct.loc, true))
                    else it
                } }
            }
        }
        healthFails = 0
    }

    private suspend fun selectAndConnect(reason: String, allowFast: Boolean) {
        val s = store.settings.value
        val allCfg = store.configs.value.filter { it.enabled }
        // المنتهية (VPNBook بعد 7 أيام) خارج الاختيار التلقائي — ولّد بديلاً بدل إضاعة الفحص
        val expired = allCfg.filter { VpnBook.expired(it.expiresAt, now()) }
        if (expired.isNotEmpty()) {
            log("⚠ ${expired.size} كونفيج منتهي الصلاحية مُستبعَد: ${expired.take(3).joinToString { it.name }}")
        }
        val freshCfg = allCfg.filter { !VpnBook.expired(it.expiresAt, now()) }
        if (allCfg.isEmpty()) {
            log("لا توجد كونفيجات مفعّلة")
            status.update { it.copy(connected = false, busy = false, message = "أضف كونفيجاً وفعّله") }
            return
        }
        // خوادم DNS المسموح باختيارها تلقائياً (المُرشِّحة/الحاجبة تحتاج إذناً صريحاً)
        val dnsAll = store.dns.value.filter { it.enabled && (!it.filtered || s.allowFilteredDns) }
        val defDns: DnsServer? =
            if (s.autoDns) null else store.dns.value.firstOrNull { it.id == s.selDnsId }
        val testDns: DnsServer? = defDns ?: dnsAll.firstOrNull()
        val label = networkLabel()
        val under = NetUtil.underlying(NetUtil.all(cm))

        status.update { it.copy(busy = true, connected = false, message = "جارٍ الفحص ($reason)…") }
        busySince = now()

        // الـ IP المباشر (مرجع التحقق): عبر الشبكة الأصلية متجاوزاً أي نفق
        val direct = under?.let { Probes.exitInfo(it.network) }
        if (direct != null) status.update { it.copy(directIp = direct.ip, directLoc = direct.loc) }

        // ---- مسار سريع: آخر كونفيج نجح على هذا النوع من الشبكات (غير منتهٍ حصراً)
        if (allowFast) {
            val lb = readLastBest(label)
            val cfg = lb?.let { l -> freshCfg.firstOrNull { it.id == l.cfgId } }
            val dns = lb?.let { l -> store.dns.value.firstOrNull { it.id == l.dnsId } }
            if (lb != null && cfg != null && now() - lb.time < 6 * 3600 * 1000L &&
                (s.autoConfig || cfg.id == s.selConfigId)
            ) {
                setProgress("اتصال سريع: ${cfg.name}")
                log("اتصال سريع بآخر كونفيج ناجح على $label: ${cfg.name}")
                val ver = connectAndVerify(cfg, lb.mtu, dns?.ips() ?: emptyList(), direct)
                if (ver != null) {
                    markConnected(cfg, dns, emptyList(), ver, null, label, direct, emptyList())
                    log("✔ متصل: ${cfg.name} • ${ver.diag} • IP ${ver.exit?.ip ?: "؟"} ${ver.exit?.loc ?: ""}")
                    return
                }
                log("✗ فشل الاتصال السريع، سيجري فحص كامل")
            }
        }

        val candidates =
            if (s.autoConfig) freshCfg.ifEmpty {
                log("⚠ كل الكونفيجات منتهية — تُفحص رغم ذلك كمحاولة أخيرة")
                allCfg
            }
            else listOf(allCfg.firstOrNull { it.id == s.selConfigId } ?: allCfg.first())

        report.value = Report(
            rows = candidates.map { ConfigRow(it.id, it.name) },
            progress = "الفحص في الخلفية — اتصال بقية التطبيقات لا يتأثر"
        )
        // ترتيب مسبق من ذاكرة الامتحان نفس الشكلة: الأسرع المعروف أولاً ثم غير المفحوص ثم
        // الفاشل سابقاً — يوفر وقتاً وبيانات: غالباً أول مرشح أو ثانيه هو الفائز.
        val examMap = if (s.autoConfig) readExamMap(label) else emptyMap()
        val orderedCandidates = if (s.autoConfig && examMap.isNotEmpty()) {
            // الناجح بالأسرع أولاً، ثم غير المفحوص، ثم الفاشل سابقاً
            fun key(id: String): Int =
                examMap[id]?.let { lat -> if (lat > 0) lat else 1_000_000_000 } ?: 500_000_000
            candidates.sortedBy { key(it.id) }
        } else candidates
        if (orderedCandidates != candidates) {
            log("ترتيب مسبق من ذاكرة الامتحان: " + orderedCandidates.take(3).joinToString { it.name })
        }
        log("بدء الفحص: $reason (${candidates.size} كونفيج)")
        downSafe()

        // MTU: الكونفيج كما هو أولاً (لا نفرض)، ثم 1280 العامة — الامتحان وحده يحدد الناجح
        val startMtus: List<Int?> = if (s.autoMtu) listOf(null, 1280) else listOf(s.selMtu)

        // ---- الامتحان: فحص واحد شامل للجميع (واجهة + TCP + خروج + جودة) عبر النفق التجريبي.
        // الكل يحضر والكل يُرتَّب بالمعادلة الموحدة (الأول والثاني...) — لا مرحلة ثانية
        // تُسقط الناجحين ولا تُوقف عند مرشح فاشل.
        val alive = ArrayList<ExamResult>()
        val sameCountryFallback = ArrayList<ExamResult>()
        var sameCountryLoc = ""
        var earlyExit = false
        val newExam = LinkedHashMap<String, Int>()
        for ((i, c) in orderedCandidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            // فاصل بين المرشحين: يمنع رشق السيرفر بمصافحات متتالية فيُحجب مؤقتاً
            if (i > 0) delay(600)
            setProgress("فحص سريع ${i + 1}/${candidates.size}: ${c.name}")
            updateRow(c.id) { it.copy(state = "testing") }
            try {
            // ملاحظة: لا فحص UDP مسبق هنا — سيرفر WireGuard يتجاهل رسائل المصافحة
            // المزيفة بلا MAC صحيح (حماية من التضخيم)، فأي فحص مزيف يعطي
            // "محجوب" كاذباً. الاختبار الحقيقي الوحيد هو رفع النفق نفسه.
            val p = probeTunnel(c, startMtus, testDns?.ips() ?: emptyList(), direct)
            if (p.vpn == null) {
                // رسالة أوضح: النفق يُبنى لكن لا تمر بيانات = مصافحة WireGuard لم تكتمل (مفتاح/حجب)
                var note = p.note
                if (note.contains("لا يتصل TCP")) {
                    note = "النفق يُبنى لكن المصافحة لا تكتمل — غالباً UDP محجوب أو المفتاح غير مسجل في السيرفر"
                }
                updateRow(c.id) { it.copy(state = "fail", note = note) }
                log("✗ ${c.name}: $note")
                newExam[c.id] = -1
                continue
            }
            newExam[c.id] = p.lat
            updateRow(c.id) {
                val exam = Exam.score(p.lat, p.jitter, p.loss)
                it.copy(
                    state = "ok", latencyMs = p.lat, jitter = p.jitter, loss = p.loss, mtu = p.mtu,
                    score = 1.0 / (1 + exam / 100.0),
                    exitIp = p.exit?.ip ?: "", exitLoc = p.exit?.loc ?: "", sameCountry = p.sameCountry
                )
            }
            if (p.sameCountry && s.autoConfig) {
                sameCountryLoc = p.exit?.loc ?: ""
                log("⚠ ${c.name}: نفس دولتك ($sameCountryLoc) — لا يفيد لتجاوز الحجب، مستبعَد من الاختيار التلقائي")
                sameCountryFallback += ExamResult(c, p.mtu, p.lat, p.jitter, p.loss, p.exit, true)
                continue
            }
            log("✓ ${c.name}: ${p.lat}ms ±${p.jitter} • فقد ${p.loss}% • MTU ${p.mtu} • ${p.exit?.ip ?: "؟"} ${p.exit?.loc ?: ""}")
            alive += ExamResult(c, p.mtu, p.lat, p.jitter, p.loss, p.exit, p.sameCountry)
            // توقف مبكر (توفير وقت وبيانات): مرشح أجنبي ممتاز كفاية لا يضاهيه ما بعده
            // (زمن/فقد/تذبذب منخفضة جداً) — نقطع الفحص ونثبته فوراً بلا فحص البقية.
            val exScore = Exam.score(p.lat, p.jitter, p.loss)
            if (s.autoConfig && exScore < 45.0) {
                earlyExit = true
                log("🏅 ${c.name}: ممتاز كفاية (درج ${"%.0f".format(exScore)}) — تخطي فحص بقية المرشحين لتوفير الوقت والبيانات")
                setProgress("ممتاز كفاية: ${c.name} — تخطي بقية الفحص")
            }
            } catch (e: Exception) {
                // مرشح واحد لا يُسقط الامتحان كله — يُسجَّل ويُتابَع البقية
                updateRow(c.id) { it.copy(state = "fail", note = "خطأ فحص: ${e.message?.take(60)}") }
                log("✗ ${c.name}: خطأ فحص (${e.message?.take(60)}) — متابعة البقية")
            }
            if (earlyExit) break
        }
        downSafe()
        // حفظ ذاكرة الامتحان (الناجح زمنه والفاشل -1) — للأتمتة فقط؛ الاختيار اليدوي منفصل عنها لا تتأثر به
        if (s.autoConfig) writeExamMap(label, examMap + newExam)

        if (alive.isEmpty() && sameCountryFallback.isNotEmpty()) {
            log(
                "⚠ كل الكونفيجات المتاحة من نفس دولتك ($sameCountryLoc) — لن تساعد على تجاوز الحجب. " +
                    "يُنصح بإضافة كونفيج من دولة أخرى. سيتصل التطبيق مؤقتاً بأفضلها."
            )
            status.update { it.copy(message = "تنبيه: كل الكونفيجات من دولتك ($sameCountryLoc) — لا تفيد لتجاوز الحجب") }
            alive += sameCountryFallback
        }

        if (alive.isEmpty()) {
            setProgress("لم ينجح أي كونفيج")
            val m = if (isManual()) "لا يمر أي TCP — تحقق من الشبكة ثم أعد التفعيل يدوياً"
                else "لا يوجد كونفيج يحمل الحركة فعلاً — إعادة محاولة تلقائية قادمة"
            status.update { it.copy(connected = false, message = m) }
            log("لم ينجح أي كونفيج من ${candidates.size} (راجع سبب كل كونفيج في نتيجة الفحص)")
            return
        }

        // ---- الترتيب النهائي بالمعادلة الموحدة مباشرة من الامتحان (الأول والثاني...).
        // لا فحص ثانٍ: كل ناجح يحمل (زمن/تذبذب/فقد/MTU/خروج). DNS والسرعة يُقاسان
        // مرة واحدة عبر نفق الفائز بعد التثبيت — توفيراً للوقت والبيانات والمصافحات.
        val ordered = alive.sortedBy { it.examScore }.map { e ->
            val sc = 1.0 / (1 + e.examScore / 100.0)
            Result(e.cfg, e.mtu, 0, testDns, emptyList(), Stat(e.lat, e.jitter, e.loss), null, sc, e.exit, emptyList())
        }
        log("الترتيب النهائي: " + ordered.take(5).mapIndexed { i, r ->
            "${Exam.rankMedal(i)} ${r.cfg.name} (${"%.1f".format(100 * (1 / r.score - 1))})"
        }.joinToString(" • "))

        // ---- التثبيت بتمريرتين — الاختيار للمضمون لا للأول في القائمة:
        // التمريرة 1: أول مكتمل التحقق فعلاً (TCP+HTTPS+تغيّر IP).
        // التمريرة 2 (احتياط فقط): أول أساسي (TCP وحده) بتثبيت تحذيري.
        // مرشح واحد عاطل لا يوقف القائمة: يُسجَّل ويُجرَّب التالي.
        // مهلة 75 ثانية لكل مرشح كحد أقصى حتى لا يتجمد التثبيت أبداً.
        var installed = false
        for (pass in listOf(true, false)) {
            if (installed) break
            if (pass) setProgress("تثبيت: البحث عن مكتمل التحقق…")
            else {
                setProgress("لا مكتمل — تثبيت أفضل أساسي…")
                log("لا يوجد مكتمل التحقق — التمريرة الاحتياطية (TCP يكفي)")
            }
            for (r in ordered) {
                currentCoroutineContext().ensureActive()
                try {
                setProgress("تثبيت الاتصال: ${r.cfg.name}" + if (pass) "" else " (احتياط)")
                val ver0 = withTimeoutOrNull(75_000) {
                    connectAndVerify(r.cfg, r.mtu, r.dns?.ips() ?: emptyList(), direct, strict = pass)
                }
                if (ver0 == null) {
                    log("✗ فشل التحقق بعد التثبيت: ${r.cfg.name} — التالي في الترتيب")
                    continue
                }
                // WARP: خروج سورياً/مخالف للتثبيت → بحث تلقائي عبر بدائل Endpoint
                val ver = if (isWarp(r.cfg))
                    warpForeignAssist(r.cfg, r.dns?.ips() ?: emptyList(), direct, ver0) ?: ver0
                else ver0
                markConnected(r.cfg, r.dns, r.dnsRows, ver, null, label, direct, r.mtuRows)
                log(
                    (if (pass) "★ الأفضل (مكتمل مضمون): " else "★ الأفضل (أساسي احتياط): ") +
                        "${r.cfg.name} • ${ver.diag} • DNS ${r.dns?.name ?: "حسب الكونفيج"} • IP ${ver.exit?.ip ?: "؟"}"
                )
                // التكميل في الخلفية للفائز فقط: DNS + خروج مؤكد + سرعة — بلا مصافحات جديدة
                postConnectRefine(dnsAll)
                installed = true
                return
                } catch (e: Exception) {
                    log("✗ خطأ تثبيت ${r.cfg.name} (${e.message?.take(60)}) — التالي في الترتيب")
                }
            }
        }
        downSafe()
        setProgress("تعذّر تثبيت الاتصال")
        val m2 = if (isManual()) "تعذّر التثبيت — بدّل الشبكة أو الكونفيج وحاول يدوياً"
            else "تعذّر تثبيت الاتصال — إعادة محاولة تلقائية قادمة"
        status.update { it.copy(connected = false, message = m2) }
    }

    /**
     * ضبط MTU بلا أي تحميل: ننطلق من القيمة الناجحة ونجرّب الأعلى، ونقبل ما يمرّ
     * فيه HTTPS بزمن مقارب للمرجع (≤ +10%). لا تحميل إطلاقاً توفيراً للبيانات —
     * السرعة تُقاس لاحقاً للفائز فقط.
     */
    private suspend fun tuneMtu(r: Result, dns: DnsServer?): Pair<Int, List<MtuRow>> {
        val ladder = listOf(1420, 1380, 1340, 1280, 1200, 1120, 1040, 1000)
        val cands = ladder.filter { it > r.mtu }.sorted().take(2).sortedDescending()
        val rows = ArrayList<MtuRow>()
        rows += MtuRow(r.mtu, null)
        var chosen = r.mtu
        val ref = r.exit?.ms ?: 0
        for (m in cands) {
            currentCoroutineContext().ensureActive()
            val v = bringUp(r.cfg, m, dns?.ips() ?: emptyList(), appOnly = true, quiet = true)
            val exit = v?.let { Probes.exitInfo(it.network, fast = true) }
            if (exit == null) {
                rows += MtuRow(m, null)
                log("MTU $m: لا يمر HTTPS")
                continue
            }
            val h = v?.let { Probes.httpsLatencyAvg(it.network, tries = 2) } ?: exit.ms
            rows += MtuRow(m, null)
            if (ref > 0 && h > ref * 1.1) {
                log("MTU $m: HTTPS أبطأ ${h}ms مقابل ${ref}ms — بقي $chosen")
                continue
            }
            log("MTU $m: HTTPS ✔ ${h}ms — المختار")
            chosen = m
            break
        }
        downSafe()
        return chosen to rows.sortedByDescending { it.mtu }
    }

    /**
     * تكميل ما بعد التثبيت في الخلفية للفائز فقط (بلا مصافحات جديدة إطلاقاً):
     * خروج مؤكد + أفضل DNS عبر النفق العامل + السرعة. يرقّي حالة
     * "متصل (لم يُتحقق)" إلى "متصل ✔" متى توفر الدليل.
     */
    private fun postConnectRefine(dnsAll: List<DnsServer>) {
        scope.launch {
            val v = vpnInfo() ?: return@launch
            if (!tunnelIsUp()) return@launch
            val net = v.network
            val st = status.value
            val directIp = st.directIp
            // 1) الخروج المؤكد (3 خدمات، مهلة مريحة — لا استعجال هنا)
            try {
                val ex = Probes.exitInfo(net)
                if (ex != null) {
                    val verified = directIp.isEmpty() || ex.ip != directIp
                    status.update {
                        it.copy(
                            exitIp = ex.ip, exitLoc = ex.loc, verified = verified,
                            message = if (verified) "متصل ✔ (تم التحقق من الـ IP)" else it.message
                        )
                    }
                    report.update { rp -> rp.copy(rows = rp.rows.map {
                        if (it.id == rp.chosenConfigId) it.copy(exitIp = ex.ip, exitLoc = ex.loc) else it
                    }) }
                    if (ex.loc.isNotEmpty()) {
                        val cid = report.value.chosenConfigId
                        store.updateConfigs { l -> l.map {
                            if (it.id == cid) it.copy(lastCountry = ex.loc,
                                lastCountryUseful = directIp.isEmpty() || !ex.loc.equals(st.directLoc, true))
                            else it
                        } }
                    }
                    log("تأكيد الخروج: ${Country.ipLine(ex.ip, ex.loc)}")
                }
            } catch (e: Exception) { }
            if (!tunnelIsUp()) return@launch
            // 2) أفضل DNS عبر النفق العامل نفسه
            try {
                val (dns, rows) = pickDns(dnsAll, net)
                if (dns != null) {
                    status.update { it.copy(activeDns = dns.name, activeDnsId = dns.id) }
                    report.update { it.copy(chosenDnsId = dns.id, dnsRows = rows) }
                    log("DNS المحدّث عبر النفق: ${dns.name}")
                }
            } catch (e: Exception) { }
            if (!tunnelIsUp()) return@launch
            // 3) السرعة مرة واحدة للفائز فقط
            try {
                val wid = report.value.chosenConfigId
                val sp = Probes.throughputMbps(4000, net)
                if (sp != null && tunnelIsUp()) {
                    status.update { it.copy(mbps = sp) }
                    report.update { rp -> rp.copy(rows = rp.rows.map {
                        if (it.id == wid) it.copy(mbps = sp) else it
                    }) }
                    log("السرعة عبر النفق: ${"%.1f".format(sp)} Mbps")
                }
            } catch (e: Exception) { }
        }
    }
    private suspend fun pickDns(list: List<DnsServer>, network: Network?): Pair<DnsServer?, List<DnsRow>> =
        coroutineScope {
            val res = list.map { d -> async(Dispatchers.IO) { d to Probes.dnsMeasure(d, network, false) } }.awaitAll()
            dnsResults.value = dnsResults.value + res.associate { it.first.id to it.second }
            val rows = res
                .sortedBy { it.second.udp?.score ?: Int.MAX_VALUE }
                .map { (d, m) ->
                    DnsRow(d.id, d.name, m.udp?.median ?: -1, "UDP", m.udp?.jitter ?: 0, m.udp?.loss ?: 100)
                }
            // أولاً: من فقدُه ≤20% فقط (سليم فعلاً). إن لم يوجد، نقبل ≤50% كحل أخير بدل لا شيء.
            val best = res.filter { (it.second.udp?.loss ?: 100) <= 20 }
                .minByOrNull { it.second.udp?.score ?: Int.MAX_VALUE }?.first
                ?: res.filter { (it.second.udp?.loss ?: 100) <= 50 }
                    .minByOrNull { it.second.udp?.score ?: Int.MAX_VALUE }?.first
            if (best != null) {
                val b = res.first { it.first.id == best.id }.second.udp
                log("DNS المختار عبر النفق: ${best.name} (${b?.median}ms فقد ${b?.loss}%) — الفقد العالي مرفوض تلقائياً")
            }
            best to rows
        }
}
